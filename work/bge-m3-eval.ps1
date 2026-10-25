# bge-m3 真实检索质量评测（B5）
#
# 前置：
#   1) ollama 已 pull bge-m3，且 11434 在跑；
#   2) jar 起来时带上：
#        --recon.recall.embedding.url=http://127.0.0.1:11434/v1/embeddings
#        --recon.recall.embedding.model=bge-m3
#        --recon.recall.embedding.dim=1024
# 用法： pwsh -NoProfile -File work\bge-m3-eval.ps1
#
# 为什么要有 mode 参数：生产的自动降级只会告诉你"最后命中哪层"，
# 要回答"向量到底比全文好在哪"，必须在同一批问题上分别打通三层。

$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$h = @{ 'X-Operator' = 'alice'; 'Content-Type' = 'application/json' }

# ---------------------------------------------------------------- 语料：一份模拟对账口径手册
$docs = @(
  @{ title = '口径-四舍五入'; text = '金额比对按分位四舍五入后再比较，一分钱以内的差异不计入差异。' },
  @{ title = '口径-汇率换算'; text = '跨币种对账按结算日的中间价折算，允许百分之零点五的汇率波动。' },
  @{ title = '口径-手续费剔除'; text = '平台手续费在结算侧单独列示，比对时从结算金额中扣除手续费。' },
  @{ title = '口径-时间窗对齐'; text = 'T 日账单与 T+1 日结算对齐，跨日订单按实际入账日归属账期。' },
  @{ title = '口径-重复入账'; text = '同一业务键出现两条以上记录时，按幂等键去重后保留最早一条。' },
  @{ title = '口径-单边缺失'; text = '左侧缺失记为对方未入账，右侧缺失记为我方漏推，两类分别归类。' },
  @{ title = '口径-跨月处理'; text = '月末最后一天二十三点五十九分之后的订单计入次月账期。' },
  @{ title = '口径-幂等键定义'; text = '以客户号、订单号、支付流水号三段拼接作为幂等键用于去重。' },
  @{ title = '口径-聚合口径'; text = '按客户汇总金额时先剔除退款单，再对其余订单求和。' },
  @{ title = '口径-状态映射'; text = '账单状态 PAID 与 PARTIAL 在结算侧统一映射为 SETTLED 语义。' },
  @{ title = '口径-容差设置'; text = '金额绝对容差零点零一元，相对容差千分之一，两者任一超限即判差异。' },
  @{ title = '口径-分片规则'; text = '按订单号末两位取模分片，保证同一笔订单始终落在同一个分片。' },
  @{ title = '口径-去重键上报'; text = '重复键只上报一条差异，其余重复记录计入明细附表供人工复核。' },
  @{ title = '口径-审批要求'; text = '试算结论为通过时才可自动批准，否则必须由人工签字确认后生效。' }
)

# ---------------------------------------------------------------- 20 条口语问法（不照抄原文）
$cases = @(
  @{ q = '差一分钱算差异吗'; want = '口径-四舍五入' },
  @{ q = '美元和人民币怎么换算着对'; want = '口径-汇率换算' },
  @{ q = '平台抽的佣金要不要扣掉再比'; want = '口径-手续费剔除' },
  @{ q = '昨天的单今天才到账怎么算'; want = '口径-时间窗对齐' },
  @{ q = '同一个订单出现两次怎么办'; want = '口径-重复入账' },
  @{ q = '只有一边有记录是什么情况'; want = '口径-单边缺失' },
  @{ q = '月底的单子算哪个月的账'; want = '口径-跨月处理' },
  @{ q = '怎么判断两条记录是同一笔'; want = '口径-幂等键定义' },
  @{ q = '客户总额要不要扣掉退款'; want = '口径-聚合口径' },
  @{ q = '支付成功和已结算是一回事吗'; want = '口径-状态映射' },
  @{ q = '最多能容忍多少误差'; want = '口径-容差设置' },
  @{ q = '数据是怎么切片的'; want = '口径-分片规则' },
  @{ q = '重复的差异要报几次'; want = '口径-去重键上报' },
  @{ q = '什么情况下必须人工签字'; want = '口径-审批要求' },
  @{ q = '金额比较前做不做精度处理'; want = '口径-四舍五入' },
  @{ q = '入账时间和账期怎么对应'; want = '口径-时间窗对齐' },
  @{ q = '幂等键由哪些字段组成'; want = '口径-幂等键定义' },
  @{ q = '退款单参与汇总吗'; want = '口径-聚合口径' },
  @{ q = '对账结果怎么分片并行'; want = '口径-分片规则' },
  @{ q = '没通过试算能自动批吗'; want = '口径-审批要求' }
)

"== 1) 入库 $($docs.Count) 篇口径 =="
foreach ($d in $docs) {
    $body = @{ title = $d.title; source = 'eval-handbook'; text = $d.text } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest "$base/api/docs" -Method Post -Body $body -Headers $h -UseBasicParsing -SkipHttpErrorCheck
    if ($r.StatusCode -notin 200, 201) { throw "入库失败 $($d.title): $($r.Content)" }
}
"入库完成"

"`n== 2) 三层分别评测（recall@1 / recall@3）=="
$modes = @('vector', 'fulltext', 'keyword')
$summary = @{}
foreach ($mode in $modes) {
    $top1 = 0; $top3 = 0; $missed = @()
    foreach ($c in $cases) {
        $url = "$base/api/docs/search?q=" + [uri]::EscapeDataString($c.q) + "&limit=3&mode=$mode"
        $r = Invoke-WebRequest $url -Headers $h -UseBasicParsing -SkipHttpErrorCheck
        $o = $r.Content | ConvertFrom-Json
        $titles = @($o.items | ForEach-Object { $_.title })
        if ($titles.Count -ge 1 -and $titles[0] -eq $c.want) { $top1++; $top3++ }
        elseif ($titles -contains $c.want) { $top3++ }
        else { $missed += "$($c.q) → 期望 $($c.want)，实得 $($titles -join '/')" }
    }
    $summary[$mode] = [pscustomobject]@{ mode = $mode; got1 = $top1; got3 = $top3; total = $cases.Count; missed = $missed }
}

# 融合：现在生产路径本身就是"向量+关键词+全文"融合（DegradingDocSearch），
# 这里量的是"不强制模式"时的真实效果
$h1 = 0; $h3 = 0
foreach ($c in $cases) {
    $url = "$base/api/docs/search?q=" + [uri]::EscapeDataString($c.q) + "&limit=3"
    $o = (Invoke-WebRequest $url -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
    $titles = @($o.items | ForEach-Object { $_.title })
    if ($titles.Count -ge 1 -and $titles[0] -eq $c.want) { $h1++ }
    if ($titles -contains $c.want) { $h3++ }
}

foreach ($mode in $modes) {
    $s = $summary[$mode]
    "  [{0,-8}] recall@1 = {1}/{2} ({3:P0})   recall@3 = {4}/{2} ({5:P0})" -f `
        $s.mode, $s.got1, $s.total, ($s.got1 / $s.total), $s.got3, ($s.got3 / $s.total)
}
"  [{0,-8}] recall@1 = {1}/{2} ({3:P0})   recall@3 = {4}/{2} ({5:P0})" -f `
    '生产(融合)', $h1, $cases.Count, ($h1 / $cases.Count), $h3, ($h3 / $cases.Count)

"`n== 3) 生产路径（自动降级）实际走哪层 =="
$o = (Invoke-WebRequest "$base/api/docs/search?q=$([uri]::EscapeDataString('差一分钱算差异吗'))&limit=3" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
"  retriever=$($o.retriever)"
"  mode=$($o.mode) degraded=$($o.degraded) reason=$($o.reason)"

"`n== 4) 错例（按 mode）=="
foreach ($mode in $modes) {
    $s = $summary[$mode]
    if ($s.missed.Count -gt 0) {
        "  [$($s.mode)]"
        $s.missed | Select-Object -First 5 | ForEach-Object { "    - $_" }
    }
}
