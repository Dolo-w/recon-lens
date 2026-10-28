# P2 写动作统一回执的现场校验（意图形态 = 老路）
#
# 背景：改造前，消息里出现大写规则编码时 needsWriteConfirm 会豁免回执——
# 于是"用 BILL_VS_SETTLE 跑一次"（模型给 action=RUN）直接执行，
# 而"直接调 recon_run 工具"（模型给 action=tool）会停住。同一件事两种行为。
# 2026-10-28 统一：写动作参数齐了就必须先回执，不看形态、不看原话点没点名。
#
# 用法：.\work\p2-check-writegate.ps1 [-Port 18097]

param([int]$Port = 18097)

$ErrorActionPreference = 'Stop'
$Base = "http://127.0.0.1:$Port"
$H = @{ 'X-Operator' = 'tester' }
$tmp = [IO.Path]::GetTempFileName()

function New-Session([string]$title) {
    $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $H `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes((@{ title = $title } | ConvertTo-Json -Compress)))
    return [long]$s.session.id
}

function Ask([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($tmp, $body, (New-Object Text.UTF8Encoding($false)))
    $raw = & curl.exe -s -N --max-time 180 -X POST "$Base/api/chat" -H 'X-Operator: tester' `
        -H 'Content-Type: application/json' --data-binary "@$tmp" 2>$null
    # SSE 的每条 data: 就是一个扁平对象：{"text":…} / {"name":…} / {"source":…} / {"turnTokens":…}
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
    [pscustomobject]@{ message = $msg; text = $text; plan = $plan; tools = $tools; events = $events }
}

function JobCount {
    return (Invoke-RestMethod -Uri "$Base/api/jobs?limit=200" -Headers $H).items.Count
}

$sid = New-Session 'P2 写动作统一回执'
$jobsBefore = JobCount

# 这句话以前会直接执行（意图形态 + 原话点名了规则编码）
$ask = Ask $sid '用 BILL_VS_SETTLE 给最近的任务跑一次对账'
$jobsAfterAsk = JobCount
$stopped = ($ask.text -match '确认执行吗') -and ($ask.tools.Count -eq 0) -and ($jobsAfterAsk -eq $jobsBefore)

Write-Host ''
Write-Host ('[1] 意图形态的写动作停在回执：{0}' -f $(if ($stopped) { 'PASS' } else { 'FAIL' }))
Write-Host ('    plan.intent={0}  confidence={1}  tool={2}' -f `
        $ask.plan.intent, $ask.plan.confidence, $(if ($ask.plan.tool) { $ask.plan.tool } else { '—' }))
Write-Host ('    tool 事件 {0} 个；任务数 {1} → {2}' -f $ask.tools.Count, $jobsBefore, $jobsAfterAsk)
Write-Host ('    回执：{0}' -f ($ask.text -replace '\s*$', ''))

# 确认后必须真的执行，且不能再问一次
$yes = Ask $sid '确认'
$jobsAfterYes = JobCount
$executed = ($yes.tools -contains 'recon_run') -and ($jobsAfterYes -eq ($jobsAfterAsk + 1)) -and ($yes.text -notmatch '确认执行吗')

Write-Host ''
Write-Host ('[2] 确认后按暂存参数执行：{0}' -f $(if ($executed) { 'PASS' } else { 'FAIL' }))
Write-Host ('    tool 事件：{0}；任务数 {1} → {2}' -f ($yes.tools -join ','), $jobsAfterAsk, $jobsAfterYes)
Write-Host ('    正文首行：{0}' -f (($yes.text -split "`n")[1]))

Remove-Item $tmp -Force

if ($stopped -and $executed) { Write-Host "`n结论：两条写路径行为一致（都先回执）" }
else { Write-Host "`n结论：仍有不一致，见上"; exit 1 }
