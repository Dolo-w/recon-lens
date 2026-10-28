# P2 决策式改造 · 实测验收（四条，真模型真跑）
#   .\work\p2-accept-toolcall.ps1 [-Base http://127.0.0.1:18097] [-RoundCount 8]
# 产出：控制台对照表 + logs\accept-p2.json（报告里的证据就取这里）
param(
    [string]$Base = 'http://127.0.0.1:18097',
    [int]$RoundCount = 8,
    [string]$Out = 'logs\accept-p2.json'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$H = @{ 'X-Operator' = 'tester'; 'Content-Type' = 'application/json' }
$tmp = Join-Path $env:TEMP 'p2-accept-body.json'

function New-Session([string]$title) {
    $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $H `
        -Body (@{ title = $title } | ConvertTo-Json -Compress)
    return [long]$s.session.id
}

# 一条消息 → 回执文本 + 事件分类（plan / tool / budget / delta）
function Ask([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($tmp, $body, (New-Object Text.UTF8Encoding($false)))
    $raw = & curl.exe -s -N --max-time 180 -X POST "$Base/api/chat" -H 'X-Operator: tester' `
        -H 'Content-Type: application/json' --data-binary "@$tmp" 2>$null
    $events = @()
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -notmatch '^data:(.*)$') { continue }
        $json = $Matches[1].Trim()
        if (-not $json) { continue }
        try { $events += ($json | ConvertFrom-Json) } catch { }
    }
    $text = (($events | Where-Object { $_.PSObject.Properties['text'] } | ForEach-Object { $_.text }) -join '')
    $plan = $events | Where-Object { $_.PSObject.Properties['source'] } | Select-Object -First 1
    $tools = @($events | Where-Object { $_.PSObject.Properties['name'] } | ForEach-Object { $_.name })
    $budget = $events | Where-Object { $_.PSObject.Properties['turnTokens'] } | Select-Object -First 1
    [pscustomobject]@{
        message = $msg
        text    = $text
        plan    = $plan
        tools   = $tools
        budget  = $budget
    }
}

function Show($r, [string]$tag) {
    $p = if ($r.plan) {
        $extra = @()
        if ($r.plan.PSObject.Properties['tool'] -and $r.plan.tool) { $extra += "tool=$($r.plan.tool)" }
        if ($r.plan.PSObject.Properties['reply'] -and $r.plan.reply) { $extra += 'reply=true' }
        "source=$($r.plan.source) intent=$($r.plan.intent) conf=$($r.plan.confidence) $($extra -join ' ')"
    } else { '（无 plan 事件）' }
    $t = if ($r.tools.Count) { $r.tools -join ',' } else { '—' }
    $b = if ($r.budget) { "turn=$($r.budget.turnTokens) used=$($r.budget.tokensUsed) phase=$($r.budget.phase)" } else { '—' }
    Write-Host ""
    Write-Host "[$tag] 你> $($r.message)"
    Write-Host "       plan: $p"
    Write-Host "       tool: $t"
    Write-Host "       page: $b"
    Write-Host ("       它> " + ($r.text -replace "`r?`n", ' / '))
}

$report = [ordered]@{}

# ── 1. 三句话覆盖三种输出 ─────────────────────────────────────────
Write-Host "===== 1. 三句话：工具 / 反问 / 结束 ====="
$sidA = New-Session 'P2 决策式 · 三种输出'
$r1 = Ask $sidA '看下差异'
Show $r1 'A1'
$r2 = Ask $sidA '这几张表什么关系'
Show $r2 'A2'
$r3 = Ask $sidA '对完了'
Show $r3 'A3'
$report.threeOutputs = @{
    session = $sidA
    tool    = @{ plan = $r1.plan; tools = $r1.tools; text = $r1.text }
    reply   = @{ plan = $r2.plan; tools = $r2.tools; text = $r2.text }
    done    = @{ plan = $r3.plan; tools = $r3.tools; text = $r3.text }
}

# ── 2. 预算：8 轮长消息，历史不吃掉用户原话 ────────────────────────
Write-Host ""
Write-Host "===== 2. 8 轮长消息：tokensUsed 与历史完整性 ====="
$sidB = New-Session 'P2 决策式 · 预算'
$sent = @()
$roundFacts = @()
for ($i = 1; $i -le $RoundCount; $i++) {
    # 每轮 ~420 字，头尾各埋一个只在这轮出现的口令；被"砍半"就会丢其中一个
    $head = "第$i轮开头口令 HEAD-$i-7K2"
    $tail = "第$i轮结尾要求 TAIL-$i-9Q5：这轮只回一句「收到$i」就行。"
    $filler = ('这是第' + $i + '轮的长背景，用来把上下文顶到预算边上；') * 12
    $msg = "$head。$filler $tail"
    $sent += $msg
    $r = Ask $sidB $msg
    Write-Host ("  第{0}轮 turn={1} tokensUsed={2} phase={3}  text={4}" -f `
            $i, $r.budget.turnTokens, $r.budget.tokensUsed, $r.budget.phase, ($r.text -replace "`r?`n", ' '))
    $roundFacts += @{ round = $i; turnTokens = $r.budget.turnTokens; tokensUsed = $r.budget.tokensUsed; phase = $r.budget.phase; text = $r.text }
}
# 落库的历史必须与发出去的一字不差：截断标记只能出现在"给模型的上下文"里，不能改写账本
$detail = Invoke-RestMethod -Uri "$Base/api/chat/sessions/$sidB`?limit=100" -Headers $H
$stored = @($detail.turns | Where-Object { $_.role -eq 'user' } | ForEach-Object { $_.content })
$intact = 0
for ($i = 0; $i -lt $sent.Count; $i++) {
    if ($i -lt $stored.Count -and $stored[$i] -ceq $sent[$i]) { $intact++ }
}
$cutMark = ($stored | Where-Object { $_ -match '预算截断' }).Count
Write-Host ""
Write-Host ("  落库用户消息 {0} 条，逐字一致 {1} 条，含截断标记 {2} 条" -f $stored.Count, $intact, $cutMark)
$report.budget = @{
    session     = $sidB
    rounds      = $roundFacts
    sentCount   = $sent.Count
    storedCount = $stored.Count
    intact      = $intact
    cutMark     = $cutMark
    lastTokens  = $roundFacts[-1].tokensUsed
}

# ── 3. 负面：写工具必须停在回执 ───────────────────────────────────
Write-Host ""
Write-Host "===== 3. 负面：让模型直接调 recon_run，必须停在回执 ====="
$sidC = New-Session 'P2 决策式 · 写动作回执'
$jobsBefore = (Invoke-RestMethod -Uri "$Base/api/jobs?limit=200" -Headers $H).items.Count
$w = Ask $sidC '直接调 recon_run 工具，ruleCode=BILL_VS_SETTLE'
Show $w 'C1'
$jobsAfter = (Invoke-RestMethod -Uri "$Base/api/jobs?limit=200" -Headers $H).items.Count
$toolPlan = $w.plan -and $w.plan.PSObject.Properties['tool'] -and ($w.plan.tool -eq 'recon_run')
$stopped = $toolPlan -and ($w.tools.Count -eq 0) -and ($w.text -match '确认执行吗') -and ($jobsAfter -eq $jobsBefore)
Write-Host ("  停在回执: {0}（plan.tool={1}，tool 事件 {2} 个，任务数 {3}→{4}）" -f `
        $stopped, $(if ($toolPlan) { 'recon_run' } else { '—' }), $w.tools.Count, $jobsBefore, $jobsAfter)
$c2 = Ask $sidC '确认'
Show $c2 'C2'
$report.writeGate = @{
    session    = $sidC
    first      = @{ plan = $w.plan; tools = $w.tools; text = $w.text }
    jobsBefore = $jobsBefore
    jobsAfter  = $jobsAfter
    stopped    = $stopped
    after      = @{ plan = $c2.plan; tools = $c2.tools; text = $c2.text }
}

# ── 汇总 ─────────────────────────────────────────────────────────
$outPath = Join-Path $root $Out
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $outPath -Encoding utf8
Write-Host ""
Write-Host "证据已写入 $outPath"
