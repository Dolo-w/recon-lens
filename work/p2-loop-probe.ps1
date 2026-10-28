# 自由循环（recon.chat.mode=loop）的人工探针：
# 一段真实对话，看"多跳 / 自己查列 / 写回执"三件事在真模型下是什么样。
#
# 用法： .\work\p2-loop-probe.ps1 -Port 18098
# 前置： work\p2-real.ps1 -Mode loop （起 18098）
param(
    [int]$Port = 18098,
    [string]$Key = "vv",
    [string]$OutFile = "logs\loop-probe.json"
)

$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:$Port"

function New-Session([string]$title) {
    $body = @{ title = $title } | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)
    $r = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" `
        -ContentType 'application/json; charset=utf-8' -Body $bytes `
        -Headers @{ "X-Operator" = "tester" }
    # POST /api/chat/sessions 回的是 {"session": {...}}，不是裸对象
    return [long]$r.session.id
}

function Ask([long]$sessionId, [string]$message) {
    $tmp = [IO.Path]::GetTempFileName()
    $payload = @{ sessionId = $sessionId; message = $message } | ConvertTo-Json -Compress
    [IO.File]::WriteAllBytes($tmp, [Text.Encoding]::UTF8.GetBytes($payload))
    $raw = & curl.exe -s -N --max-time 240 `
        -H "X-Operator: tester" -H "Content-Type: application/json; charset=utf-8" `
        --data-binary "@$tmp" "$Base/api/chat"
    Remove-Item $tmp -Force

    $sb = New-Object Text.StringBuilder
    $plans = @()
    $tools = @()
    $budget = $null
    foreach ($line in ($raw -split "`n")) {
        if (-not $line.StartsWith("data:")) { continue }
        $json = $line.Substring(5).Trim()
        if (-not $json) { continue }
        try { $ev = $json | ConvertFrom-Json } catch { continue }
        $keys = $ev.PSObject.Properties.Name
        if ($keys -contains "source") {
            $plans += [ordered]@{
                source = $ev.source; intent = $ev.intent; confidence = $ev.confidence
                tool = $ev.tool; reply = $ev.reply; reason = $ev.reason
            }
        } elseif ($keys -contains "turnTokens") {
            $budget = [ordered]@{ turn = $ev.turnTokens; used = $ev.tokensUsed; phase = $ev.phase }
        } elseif ($keys -contains "name") {
            $tools += $ev.name
        } elseif ($keys -contains "text") {
            [void]$sb.Append($ev.text)
        }
    }
    return [ordered]@{
        message = $message
        text    = $sb.ToString()
        plans   = $plans
        tools   = $tools
        budget  = $budget
        hops    = $plans.Count
    }
}

function Show($r) {
    Write-Host ""
    Write-Host "USER > $($r.message)" -ForegroundColor Cyan
    $i = 0
    foreach ($p in $r.plans) {
        $i++
        $what = if ($p.tool) { "tool=" + $p.tool } elseif ($p.reply) { "reply" } else { "intent=" + $p.intent }
        Write-Host ("  plan{0}: {1} {2} conf={3}" -f $i, $p.source, $what, $p.confidence) -ForegroundColor DarkGray
    }
    if ($r.tools.Count -gt 0) { Write-Host ("  tools: " + ($r.tools -join " → ")) -ForegroundColor Yellow }
    if ($r.budget) { Write-Host ("  budget: turn={0} used={1} phase={2}" -f $r.budget.turn, $r.budget.used, $r.budget.phase) -ForegroundColor DarkGray }
    Write-Host ("BOT > " + $r.text) -ForegroundColor Green
}

Write-Host "→ $Base  自由循环探针" -ForegroundColor White
$sid = New-Session "loop 探针"
Write-Host "session = $sid" -ForegroundColor DarkGray

# 一段真实对话：起草（带一个不存在的字段）→ 补参数 → 看差异 → 问关系 → 跑一次 → 确认 → 收尾
$script = @(
    "起草个规则：按订单、资源对账",
    "bill_left",
    "资源就用状态吧",
    "看一下最近一次任务的差异",
    "这几张表什么关系",
    "用 BILL_VS_SETTLE 跑一次对账",
    "确认",
    "对完了"
)

$results = @()
foreach ($line in $script) {
    $r = Ask $sid $line
    Show $r
    $results += $r
}

$summary = [ordered]@{
    port     = $Port
    session  = $sid
    turns    = $results.Count
    hops     = ($results | ForEach-Object { $_.hops })
    tools    = ($results | ForEach-Object { $_.tools })
    turnsCost= ($results | ForEach-Object { if ($_.budget) { $_.budget.turn } else { 0 } })
    results  = $results
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile -Encoding UTF8
Write-Host ""
Write-Host "证据写入 $OutFile" -ForegroundColor DarkGray
