# P2 意图理解（模型兜底）实测脚本
# 前置：jar 已起在 18080，且进程环境里有 DEEPSEEK_API_KEY
# 用法（必须是 pwsh 7）：& ./work/p2-smoke.ps1

$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$h = @{ 'X-Operator' = 'alice' }
$pass = 0; $fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; "  [OK]   $name  $detail" }
    else { $script:fail++; "  [FAIL] $name  $detail" }
}

function NewSession($title) {
    $r = Invoke-WebRequest "$base/api/chat/sessions" -Method Post -Body (@{ title = $title } | ConvertTo-Json -Compress) `
        -ContentType 'application/json' -Headers $h -UseBasicParsing -SkipHttpErrorCheck
    ($r.Content | ConvertFrom-Json).session.id
}

function Ask($sid, $msg) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-WebRequest "$base/api/chat" -Method Post `
        -Body (@{ sessionId = [long]$sid; message = $msg } | ConvertTo-Json -Compress) `
        -ContentType 'application/json' -Headers $h -UseBasicParsing -TimeoutSec 120 -SkipHttpErrorCheck
    $sw.Stop()
    $plan = $null; $intent = ''; $text = ''
    foreach ($line in ($r.Content -split "`n")) {
        if ($line -notlike 'data:*') { continue }
        $p = $line.Substring(5).Trim()
        try { $o = $p | ConvertFrom-Json } catch { continue }
        if ($o.source) { $plan = $o }
        if ($o.intent) { $intent = $o.intent }
        if ($o.text) { $text += $o.text }
    }
    [pscustomobject]@{ plan = $plan; intent = $intent; text = $text; ms = $sw.ElapsedMilliseconds }
}

"== P2 意图理解实测 =="

# A1 规划器状态
$p = (Invoke-WebRequest "$base/api/chat/planner" -UseBasicParsing).Content | ConvertFrom-Json
Check 'A1 模型已装配' ($p.modelEnabled -eq $true -and $p.planner -like '*deepseek*') $p.planner

# A2 词表快速路径：意图判定不该花一次模型调用
$sid = NewSession 'P2 smoke'
$a = Ask $sid '跑一下 BILL_VS_SETTLE'
Check 'A2 关键词快速路径' ($a.plan.source -eq 'KEYWORD' -and $a.intent -eq 'RUN') ("source=$($a.plan.source) intent=$($a.intent) $($a.ms)ms")
# A3 整轮时延：意图是 20–30ms 的词表命中，但「人话总结」会再花一次模型往返（约 0.6–0.9s）。
#    这是 P2 有意引入的成本：关掉 recon.chat.llm.summarize 就回到纯词表时延。
Check 'A3 快速路径整轮 < 2.5s（含人话总结）' ($a.ms -lt 2500) "$($a.ms)ms"

# A4 词表失手 → 模型兜底，并且给候选
$b = Ask $sid '这摊子事儿你帮我瞅瞅'
Check 'A4 模型兜底' ($b.plan.source -eq 'LLM') ("source=$($b.plan.source) conf=$($b.plan.confidence) $($b.ms)ms")
Check 'A5 没听懂时给候选' ($b.plan.options.Count -ge 1) ("options=$($b.plan.options -join ',')")
Check 'A6 候选落在 HELP 上' ($b.intent -eq 'HELP') "intent=$($b.intent)"

# A7 模型理解省略句（词表这里只会回 HELP）
$c = Ask $sid '结算和账单的口径差在哪'
Check 'A7 模型理解省略句' ($c.plan.source -eq 'LLM' -and $c.intent -eq 'EXPLAIN_JOB') ("source=$($c.plan.source) intent=$($c.intent) conf=$($c.plan.confidence)")
Check 'A8 用了槽位里的任务' ($c.text -like '*任务 #*') $c.text.Substring(0, [Math]::Min(40, $c.text.Length))
Check 'A9 模型单轮 < 5s' ($c.ms -lt 5000) "$($c.ms)ms"

# A10 两套并存：常见问法仍然是词表
$d = Ask $sid '有哪些规则'
Check 'A10 常见问法仍走词表' ($d.plan.source -eq 'KEYWORD' -and $d.intent -eq 'LIST_RULES') ("source=$($d.plan.source) $($d.ms)ms")

# A11 轮次信封里记了意图（看板与审计用）
$turns = ((Invoke-WebRequest "$base/api/chat/sessions/${sid}?limit=100" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json).turns
$withIntent = @($turns | Where-Object { $_.toolCalls -like '*"intent"*' })
Check 'A11 轮次信封带 intent' ($withIntent.Count -ge 4) ("turns=$($turns.Count) withIntent=$($withIntent.Count)")

# A12 页面仍然是那一个单页，且带上了理解依据
$page = (Invoke-WebRequest "$base/" -UseBasicParsing)
Check 'A12 页面 200' ($page.StatusCode -eq 200) "bytes=$($page.RawContentLength)"
Check 'A13 页面有理解依据渲染' ($page.Content -like '*renderPlan*') 'renderPlan found'

# A14 统计接口能看到意图分布
$st = (Invoke-WebRequest "$base/api/chat/stats" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
Check 'A14 意图分布可查' ($null -ne $st.byIntent) ("byIntent keys=$($st.byIntent.PSObject.Properties.Name -join ',')")

"== 结果：$pass 通过 / $fail 失败 =="
if ($fail -gt 0) { exit 1 }
