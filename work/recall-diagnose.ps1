# 召回率归因实验：到底是模型弱、语料歧义、还是"被检索的文本缺了标题"
#
# 做法：直接调 ollama /v1/embeddings，对同一批语料算两种表示：
#   A) content            —— 现在线上用的（只有正文）
#   B) title + content    —— 把标题并进被检索文本
# 同一批 20 条问法，比较 recall@1/@3，并打印错例的分数与差距。
#
# 用法： pwsh -NoProfile -File work\recall-diagnose.ps1

$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$ollama = 'http://127.0.0.1:11434/v1/embeddings'
$h = @{ 'X-Operator' = 'alice' }
$json = @{ 'Content-Type' = 'application/json' }

function Embed([string[]]$texts) {
    $body = @{ model = 'bge-m3'; input = $texts } | ConvertTo-Json -Compress -Depth 4
    $r = Invoke-WebRequest $ollama -Method Post -Body $body -Headers $json -UseBasicParsing -TimeoutSec 120
    $o = $r.Content | ConvertFrom-Json
    return @($o.data | Sort-Object { $_.index } | ForEach-Object { ,@($_.embedding) })
}

function Cosine($a, $b) {
    $dot = 0.0; $na = 0.0; $nb = 0.0
    for ($i = 0; $i -lt $a.Count; $i++) {
        $dot += $a[$i] * $b[$i]; $na += $a[$i] * $a[$i]; $nb += $b[$i] * $b[$i]
    }
    return $dot / ([Math]::Sqrt($na) * [Math]::Sqrt($nb))
}

# ---- 语料与问法（与 bge-m3-eval.ps1 同一套）----
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
$cases = @(
  '差一分钱算差异吗', '美元和人民币怎么换算着对', '平台抽的佣金要不要扣掉再比', '昨天的单今天才到账怎么算',
  '同一个订单出现两次怎么办', '只有一边有记录是什么情况', '月底的单子算哪个月的账', '怎么判断两条记录是同一笔',
  '客户总额要不要扣掉退款', '支付成功和已结算是一回事吗', '最多能容忍多少误差', '数据是怎么切片的',
  '重复的差异要报几次', '什么情况下必须人工签字', '金额比较前做不做精度处理', '入账时间和账期怎么对应',
  '幂等键由哪些字段组成', '退款单参与汇总吗', '对账结果怎么分片并行', '没通过试算能自动批吗'
)
$want = @(
  '口径-四舍五入','口径-汇率换算','口径-手续费剔除','口径-时间窗对齐','口径-重复入账','口径-单边缺失',
  '口径-跨月处理','口径-幂等键定义','口径-聚合口径','口径-状态映射','口径-容差设置','口径-分片规则',
  '口径-去重键上报','口径-审批要求','口径-四舍五入','口径-时间窗对齐','口径-幂等键定义','口径-聚合口径',
  '口径-分片规则','口径-审批要求'
)

$contentTexts = @($docs | ForEach-Object { $_.text })
$titledTexts = @($docs | ForEach-Object { $_.title + '：' + $_.text })

"== 1) 两种语料表示各算一次向量 =="
$embContent = Embed $contentTexts
$embTitled  = Embed $titledTexts
$embQueries = Embed $cases

function Evaluate($docEmb, [string]$label) {
    $hit1 = 0; $hit3 = 0; $fails = @()
    for ($q = 0; $q -lt $cases.Count; $q++) {
        $scores = @()
        for ($d = 0; $d -lt $docs.Count; $d++) {
            $scores += [pscustomobject]@{ title = $docs[$d].title; score = (Cosine $embQueries[$q] $docEmb[$d]) }
        }
        $ranked = @($scores | Sort-Object score -Descending)
        if ($ranked[0].title -eq $want[$q]) { $hit1++ }
        if (($ranked[0..2] | ForEach-Object { $_.title }) -contains $want[$q]) { $hit3++ }
        else {
            $fails += "    {0} → 期望 {1}；top1={2}({3:N3}) top2={4}({5:N3})" -f `
                $cases[$q], $want[$q], $ranked[0].title, $ranked[0].score, $ranked[1].title, $ranked[1].score
        }
    }
    "  [{0,-14}] recall@1 = {1}/{2} ({3:P0})   recall@3 = {4}/{2} ({5:P0})" -f `
        $label, $hit1, $cases.Count, ($hit1 / $cases.Count), $hit3, ($hit3 / $cases.Count)
    return $fails
}

"`n== 2) 对照 =="
$failsA = Evaluate $embContent '仅正文'
$failsB = Evaluate $embTitled  '标题+正文'

"`n== 3) 仅正文的错例（分数与差距）=="
$failsA | Select-Object -First 8 | ForEach-Object { $_ }

"`n== 4) 同一问题在两种表示下的 top1 比较（前 8 条）=="
for ($q = 0; $q -lt 8; $q++) {
    $sa = @(); $sb = @()
    for ($d = 0; $d -lt $docs.Count; $d++) {
        $sa += [pscustomobject]@{ t = $docs[$d].title; s = (Cosine $embQueries[$q] $embContent[$d]) }
        $sb += [pscustomobject]@{ t = $docs[$d].title; s = (Cosine $embQueries[$q] $embTitled[$d]) }
    }
    $ta = ($sa | Sort-Object s -Descending)[0]
    $tb = ($sb | Sort-Object s -Descending)[0]
    "  {0,-22} 仅正文={1}({2:N3})  标题+正文={3}({4:N3})" -f $cases[$q], $ta.t, $ta.s, $tb.t, $tb.s
}
