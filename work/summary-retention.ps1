# 摘要保持率判分 v2（B3）：20 条探测 · 按事实类型分层 · 场景随机化
#
# 为什么不硬编码探针：任务号、业务键每跑一次都变，硬编码的"期望值"会变成维护负担，
# 最后一定被改成"跑通就行"。这里改成**从这次会话的原文里现场推导期望值**，
# 再拿摘要去对——测的是"同一批事实在压缩前后的存活率"，而不是"标答背得对不对"。
#
# 为什么场景要随机：固定消息、固定顺序跑久了，摘要会"刚好适配"这一种排列；
# 换个顺序或换条规则就掉。每次随机选规则、随机决定要不要起草/看数据、随机打乱
# 无依赖块之间的顺序，并把 seed 打出来——复现用 -Seed。
#
# 指标与门槛：
#   压缩比      ≥ 3:1（原文 <3000 字的短会话按 2.5:1：事实行/结论等固定结构占比大）
#   确定性保持率 A 层 100%（id 类事实一个都不许丢；B 层只报不卡）
#   模型保持率   ≥ 70%（只给摘要，能不能答对；20 条下 95% CI 约 ±19 点，门槛贴太高会被单条抖动影响）
#   基线回归     只与同 seed 的历史比：A 层掉一条 / 模型跌 >10 点 / 压缩比跌 >1.5 → 退出码 2
#
# 用法： pwsh -NoProfile -File work\summary-retention.ps1 [-Seed 42]
# 需要： DEEPSEEK_API_KEY（模型探针）；不设则只跑确定性探针。

param([int]$Seed = 0)

$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$h = @{ 'X-Operator' = 'alice' }
$json = @{ 'Content-Type' = 'application/json' }

$RATIO_GATE = 3.0
# 短会话：事实行 + 〔结论〕/〔未决〕 + 段头这些固定结构约占 350-450 字，
# 原文三千字以内时 3:1 在结构上不可达；门槛按 2.5:1 判，长短会话都报原值。
# 用"原文 <3000 字"而不是"≤6 轮"：同样 10 条 turn，带起草的会话原文能多一倍。
$RATIO_GATE_SHORT = 2.5
$SHORT_CHARS = 3000
$RETENTION_GATE = 0.70

$rnd = if ($Seed -gt 0) { [Random]::new($Seed) } else { [Random]::new((Get-Random -Maximum 100000)) }

function Ask-Chat([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest "$base/api/chat" -Method Post -Body $body -ContentType 'application/json' `
        -Headers $h -UseBasicParsing -TimeoutSec 120
    $text = ''
    foreach ($line in ($r.Content -split "`n")) {
        if ($line -like 'data:*') {
            $d = $line.Substring(5).Trim()
            try { $o = $d | ConvertFrom-Json } catch { continue }
            if ($o.text) { $text += $o.text }
        }
    }
    return $text
}

# ---------------------------------------------------------------- 场景：随机化，但保持依赖顺序
$rulePool = @('BILL_VS_SETTLE', 'CUSTOMER_STATUS_RECON', 'CUSTOMER_SUM_RECON')
$chosenRule = $rulePool[$rnd.Next($rulePool.Count)]
$withData = $rnd.Next(2) -eq 0
$withDraft = $rnd.Next(3) -ne 0          # 2/3 的概率连起草一起测
$draftPhrases = @(
    '按订单和状态对账，比金额',
    '按客户汇总对账，比金额',
    '按客户和状态对账，比状态'
)
$draftPhrase = $draftPhrases[$rnd.Next($draftPhrases.Count)]

# 无依赖块打乱（看规则/看数据可以插在任意位置）；有依赖的固定：run → (explain|diffs) → … → 起草 → 试算
$middle = @('explain', 'diffs', 'rules')
if ($withData) { $middle += 'data' }
$middle = @($middle | Sort-Object { $rnd.Next() })

"== 1) 造一段有事实的会话（随机场景，seed=$Seed）=="
"  规则=$chosenRule  看数据=$withData  起草=$withDraft  起草话术=$draftPhrase  块顺序=$($middle -join '→')"

$s = (Invoke-WebRequest "$base/api/chat/sessions" -Method Post `
        -Body (@{ title = "摘要保持率 seed=$Seed" } | ConvertTo-Json -Compress) -ContentType 'application/json' `
        -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
$sid = $s.session.id

$script:turns = @()
function Turn([string]$msg) {
    $reply = Ask-Chat $sid $msg
    $script:turns += [pscustomobject]@{ user = $msg; answer = $reply }
}

Turn "跑一下 $chosenRule"
foreach ($block in $middle) {
    switch ($block) {
        'explain' { Turn '为什么差这么多' }
        'diffs'   { Turn '看差异' }
        'rules'   { Turn '有哪些规则' }
        'data'    { Turn '看数据' }
    }
}
if ($withDraft) {
    Turn "起草个规则，$draftPhrase"
    Turn '就这两个吧'
    Turn '确认'
    Turn '那条草稿试算通过了吗'
}

"sessionId=$sid，轮次 $($script:turns.Count)"

# ---------------------------------------------------------------- 事实抽取（从原文导期望值）
$all = ($script:turns | ForEach-Object { $_.user + "`n" + $_.answer }) -join "`n"

function First-Match([string]$pattern) {
    $m = [regex]::Match($all, $pattern)
    return $(if ($m.Success) { $m.Groups[1].Value } else { $null })
}

$runText = ($script:turns | Where-Object { $_.user -like '跑一下*' } | Select-Object -First 1).answer
$rulesText = ($script:turns | Where-Object { $_.user -like '有哪些规则*' } | Select-Object -First 1).answer
$trialText = ($script:turns | Where-Object { $_.user -like '*试算*' } | Select-Object -First 1).answer
$draftReply = ($script:turns | Where-Object { $_.answer -match '已落库为草稿' } | Select-Object -Last 1).answer

$ruleCode = First-Match '([A-Z][A-Z0-9_]{3,})'
# 任务号只从"跑对账"那一条回复里取：一次会话里起草/试算也会建任务，
# 从全文取第一个 #N 会取到别的任务上（实测把 #52 和 #17 混了）。
$jobId = [regex]::Match($runText, '#(\d+)').Groups[1].Value
$diffCount = [regex]::Match($runText, '差异\s*(\d+)').Groups[1].Value
$highCount = [regex]::Match($runText, 'HIGH\s*(\d+)').Groups[1].Value
$matched = [regex]::Match($runText, '匹配\s*(\d+)').Groups[1].Value
$rulesCount = [regex]::Match($rulesText, '(\d+)\s*条对账规则').Groups[1].Value
$draftCode = [regex]::Match($draftReply, '规则\s*([A-Z][A-Z0-9_]{3,})\s*已落库').Groups[1].Value
$keys = @([regex]::Matches($all, '[A-Z]\d{3}\|ORD\d+') | ForEach-Object { $_.Value } | Select-Object -Unique)
$tables = @([regex]::Matches($all, '\bbill_(left|right)\b') | ForEach-Object { $_.Value } | Select-Object -Unique)

# 每条探针 = 分组 + 问题 + 可接受答案的多个写法 + **层级**
#   A 层（硬门槛）：id 类事实 —— 规则编码、任务号、业务键、起草的编码/状态。一条都不许丢。
#   B 层（尽力而为）：长列表里的数字/枚举（容差、试算结论）—— 截断本来就会丢一部分，
#                     报出来是为了看趋势，不拿它判生死。
# 为什么要分层：截断版的承诺是"首尾 + 坐标 + 关键 id"，不是"每个数字都记住"。
# 把 B 层也卡 100%，等于要求摘要不压缩。
$probes = @()
function Probe([string]$tier, [string]$group, [string]$q, [string[]]$wants) {
    $clean = @($wants | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($clean.Count -gt 0) {
        $script:probes += [pscustomobject]@{ tier = $tier; group = $group; q = $q; wants = $clean }
    }
}

Probe 'A' '实体' '这次跑的是哪条规则？只回答规则编码' @($ruleCode)
Probe 'A' '实体' '跑对账那个任务号是几号？只回答数字' @($jobId)
if ($withDraft) {
    Probe 'A' '实体' '刚才起草的规则编码是什么？' @($draftCode)
    Probe 'A' '实体' '起草的规则现在生效了吗？' @('不生效', '没生效', '草稿')
}
Probe 'A' '业务键' '摘要里列出的差异业务键有哪些？照抄一个' @($keys)
Probe 'B' '数字' '一共几条差异？只回答数字' @($diffCount)
Probe 'B' '数字' '有几条高风险差异？只回答数字' @($highCount)
Probe 'B' '数字' '匹配上多少条？只回答数字' @($matched)
Probe 'B' '数字' '规则库里有几条规则？只回答数字' @($rulesCount)
if ($tables -contains 'bill_left') { Probe 'B' '表' '这次对账用的是哪两张表？' @('bill_left') }
if ($tables -contains 'bill_right') { Probe 'B' '表' '右侧那张表叫什么？' @('bill_right') }

# 口径类探针不写死：换条规则，"比什么字段、容差多少"全变。按**当前规则**取值——
# 早先从"整段原文"里挑候选，问题问的是"这条规则"，答案却可能来自别家规则
# （CUSTOMER_SUM_RECON 的 0.5% 与 BILL_VS_SETTLE 的 0.01% 同时被当成可接受答案）。
switch ($chosenRule) {
    'BILL_VS_SETTLE'         { $fields = @('amount','金额'); $tols = @('0.01%'); $dims = @('customer_id','order_no') }
    'CUSTOMER_STATUS_RECON'  { $fields = @('amount','金额','order_count','订单数'); $tols = @('0.01%','0.01'); $dims = @('customer_id','status') }
    'CUSTOMER_SUM_RECON'     { $fields = @('amount','金额'); $tols = @('0.5%','0.005'); $dims = @('customer_id') }
    default                  { $fields = @('amount','金额'); $tols = @('0.01%'); $dims = @('customer_id','order_no') }
}
Probe 'B' '口径' '这条规则比的是什么字段？' $fields
Probe 'B' '口径' '容差或允许偏差是多少？' $tols
Probe 'B' '口径' '对账维度是什么？' $dims
Probe 'B' '规则' "规则 $chosenRule 比的是什么字段？" $fields

if ($withDraft) {
    $trialWant = @()
    if ($trialText -match 'INCONCLUSIVE|样本|不足') { $trialWant = @('INCONCLUSIVE', '样本', '不足') }
    elseif ($trialText -match '没跑过|未跑|没试算') { $trialWant = @('没跑过', '未跑', '还没试算') }
    Probe 'B' '状态' '试算结论是什么？' $trialWant
    Probe 'B' '状态' '试算的结论能直接批吗？' @('不建议', '不能', '需人工', '签字')
}
Probe 'B' '差异类型' '这次出现过哪种差异类型？' @('AMOUNT_MISMATCH', '金额', '重复', '缺失', 'DUPLICATE_KEY')

"抽出 $($probes.Count) 条探针（分组：$(($probes | Group-Object group | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join '，')）"

# ---------------------------------------------------------------- 压缩
"`n== 2) 压缩 =="
$before = (Invoke-WebRequest ("$base/api/chat/sessions/" + $sid + "?limit=200") -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
$beforeChars = ($before.turns | ForEach-Object { $_.content.Length } | Measure-Object -Sum).Sum
$sum = (Invoke-WebRequest ("$base/api/chat/sessions/" + $sid + "/summary") -Method Post -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
$summary = $sum.summary
$ratio = [math]::Round($beforeChars / [math]::Max($summary.Length, 1), 2)
"被压 $($before.turns.Count) 条 / $beforeChars 字 → 摘要 $($summary.Length) 字，压缩比 $ratio`:1"
$ratioGate = if ($beforeChars -lt $SHORT_CHARS) { $RATIO_GATE_SHORT } else { $RATIO_GATE }

# ---------------------------------------------------------------- 确定性探针
"`n== 3) 确定性：摘要里还找得到吗 =="
$factOk = 0
$aOk = 0; $aTotal = 0
foreach ($p in $probes) {
    $ok = $false
    foreach ($w in $p.wants) { if ($summary.Contains($w)) { $ok = $true; break } }
    if ($p.tier -eq 'A') { $aTotal++; if ($ok) { $aOk++ } }
    if ($ok) { $factOk++ } else { "  [MISS][$($p.tier)/$($p.group)] $($p.q) → 期望含 $(($p.wants) -join ' | ')" }
}
"  确定性命中 $factOk/$($probes.Count)（A 层 $aOk/$aTotal）"

# 模型探针只在"摘要里确实有这条事实"的探针上跑：
# 摘要没写的东西，模型答不出是理所当然，拿它算保持率等于用没上的课出考卷。
$eligible = @($probes | Where-Object {
    $hit = $false
    foreach ($w in $_.wants) { if ($summary.Contains($w)) { $hit = $true; break } }
    $hit
})
"  可答探针 $($eligible.Count)/$($probes.Count)（其余是摘要本身就没写的 B 层事实）"

# ---------------------------------------------------------------- 模型探针
"`n== 4) 模型：只给摘要能不能答对 =="
$key = $env:DEEPSEEK_API_KEY
$modelOk = $null
$missed = @()
if ([string]::IsNullOrWhiteSpace($key)) {
    "  跳过（没有 DEEPSEEK_API_KEY）"
} else {
    $modelOk = 0
    $apiErrors = 0
    foreach ($p in $eligible) {
        $payload = @{
            model = 'deepseek-chat'; temperature = 0
            messages = @(
                @{ role = 'system'; content = '只根据给定材料回答，材料没有的信息回答"不知道"。不要编造。' },
                @{ role = 'user'; content = "材料：`n$summary`n`n问题：$($p.q)" }
            )
        } | ConvertTo-Json -Compress -Depth 6
        try {
            $resp = Invoke-WebRequest 'https://api.deepseek.com/chat/completions' -Method Post -Body $payload `
                -ContentType 'application/json' -Headers @{ Authorization = "Bearer $key" } `
                -UseBasicParsing -TimeoutSec 60
            $answer = ($resp.Content | ConvertFrom-Json).choices[0].message.content
        } catch {
            # 调用失败要和"答错"分开算：否则一次网络抖动会被读成"摘要退化"
            $apiErrors++
            $answer = ''
        }
        $hit = $false
        foreach ($w in $p.wants) { if ($answer -match [regex]::Escape($w)) { $hit = $true; break } }
        if ($hit) {
            $modelOk++
        } else {
            $missed += "  [MISS][$($p.group)] $($p.q) → 期望 $(($p.wants) -join ' | ')，答：$(($answer -replace "`r?`n",' '))"
        }
    }
    $missed | ForEach-Object { $_ }
    "  模型命中 $modelOk/$($eligible.Count)"
    if ($apiErrors -gt 0) {
        # 调用失败不算摘要的账：标记这次模型结论不可信，门槛跳过
        "  [WARN] 有 $apiErrors 次模型调用失败，本次模型保持率不作数"
        $modelOk = $null
    }
}

# ---------------------------------------------------------------- 结论
$modelRate = $null
"
== 5) 结论 =="
"  A 层确定性保持率 = $aOk/$aTotal（门槛 100%）"
"  全部确定性保持率 = $factOk/$($probes.Count)"
if ($null -ne $modelOk) {
    $modelRate = [math]::Round($modelOk / [Math]::Max($eligible.Count, 1), 2)
    "  模型保持率       = $modelOk/$($eligible.Count) ($modelRate)   门槛 $RETENTION_GATE"
}
"  压缩比           = $ratio`:1   门槛 $ratioGate`:1"

# 分组明细：哪一类事实最容易在压缩中丢
"`n== 6) 分组命中 =="
foreach ($g in ($probes | Group-Object group)) {
    $hit = 0
    foreach ($p in $g.Group) {
        foreach ($w in $p.wants) { if ($summary.Contains($w)) { $hit++; break } }
    }
    "  {0,-6} {1}/{2}" -f $g.Name, $hit, $g.Count
}

# ---------------------------------------------------------------- 基线回归
# 绝对值达标只说明"这次没坏"；"比上次差了多少"才抓得住悄悄退化。
# 容差是刻意的：单次跑有抖动（模型答题本身不稳定），但 A 层 id 掉一条就是真退化，零容忍。
"`n== 7) 基线回归 =="
$historyFile = Join-Path $PSScriptRoot 'summary-retention-history.json'
$history = @()
if (Test-Path $historyFile) {
    try { $history = @(Get-Content $historyFile -Raw | ConvertFrom-Json) } catch { $history = @() }
}
$history = @($history | Where-Object { $_ -ne $null })
$regressions = @()
$aRate = $aOk / [Math]::Max($aTotal, 1)
# 只比"同 seed"的历史：场景是随机生成的，跨场景比模型保持率是拿苹果比橘子（同样的
# 摘要质量，18 条探针和 12 条探针的 88% 不是一个东西）。
$recent = @()
if ($history.Count -gt 0 -and $Seed -ne 0) {
    $recent = @($history | Where-Object { [int]$_.seed -eq $Seed } | Select-Object -Last 3)
}
if ($Seed -eq 0) {
    "  随机跑（seed=0）不与历史比较：每次场景不同；要回归判据请用 -Seed N"
} elseif ($recent.Count -eq 0) {
    "  seed=$Seed 首次跑，建立基线"
} else {
    $aRates = @($recent | ForEach-Object { [double]$_.aOk / [Math]::Max([int]$_.aTotal, 1) } | Sort-Object)
    $modelRates = @($recent | Where-Object { $null -ne $_.modelOk } |
            ForEach-Object {
                $den = $(if ($null -ne $_.eligible) { [int]$_.eligible } else { [int]$_.total })
                [double]$_.modelOk / [Math]::Max($den, 1)
            } | Sort-Object)
    $ratios = @($recent | ForEach-Object { [double]$_.ratio } | Sort-Object)
    $prevA = $aRates[[int][Math]::Floor($aRates.Count / 2)]
    $prevRatio = $ratios[[int][Math]::Floor($ratios.Count / 2)]
    $prevModel = $null
    if ($modelRates.Count -ge 2) { $prevModel = $modelRates[[int][Math]::Floor($modelRates.Count / 2)] }

    "  近 $($recent.Count) 次中位数：A 层 {0:P0}，模型 {1}，压缩比 {2}" -f `
        $prevA, $(if ($null -eq $prevModel) { '-' } else { '{0:P0}' -f $prevModel }), $prevRatio

    if (($prevA - $aRate) -gt 0.001) {
        $regressions += ("A 层 id 保持率低于近期中位数：{0:P0} → {1:P0}" -f $prevA, $aRate)
    }
    if (($null -ne $modelOk) -and ($null -ne $prevModel) -and (($prevModel - $modelRate) -gt 0.10)) {
        $regressions += ("模型保持率低于近期中位数超过 10 个点：{0:P0} → {1:P0}" -f $prevModel, $modelRate)
    }
    if (($prevRatio - $ratio) -gt 1.5) {
        $regressions += "压缩比低于近期中位数超过 1.5：$prevRatio → $ratio"
    }
    if ($regressions.Count -eq 0) { "  与近期同 seed 水平持平" }
    foreach ($r in $regressions) { "  [REGRESSION] $r" }
}

$history += [pscustomobject]@{
    at      = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    seed    = $Seed
    ratio   = $ratio
    aOk     = $aOk
    aTotal  = $aTotal
    factOk  = $factOk
    total   = $probes.Count
    modelOk = $modelOk
    eligible = $eligible.Count
}
# 只留最近 20 次：这是"趋势判据"，不是审计日志
$history = @($history | Select-Object -Last 20)
($history | ConvertTo-Json -Depth 4) | Set-Content $historyFile -Encoding UTF8
"  结果已追加到 $historyFile（保留最近 20 次）"

$pass = ($ratio -ge $ratioGate) -and ($aOk -eq $aTotal)
if ($null -ne $modelOk) {
    $pass = $pass -and (([math]::Round($modelOk / [Math]::Max($eligible.Count, 1), 2)) -ge $RETENTION_GATE)
}
if (-not $pass) { "`nFAIL（绝对门槛未过）"; exit 1 }
if ($regressions.Count -gt 0) { "`nREGRESSION（绝对值达标，但比上次差）"; exit 2 }
"`nPASS"