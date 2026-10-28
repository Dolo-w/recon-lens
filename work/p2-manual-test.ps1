# P2 手工验收：对话记忆 → 起草 → 同步试算 → 签发确认码 → 人工批准 → 跑一次
# 用法： .\work\p2-manual-test.ps1
#        .\work\p2-manual-test.ps1 -Base http://127.0.0.1:18097 -Key vv
param(
    [string]$Base = 'http://127.0.0.1:18097',
    [string]$Key = 'vv',
    [string]$Code = 'MANUAL_TEST_RECON'
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$chat = @{ 'X-Operator' = 'tester'; 'Content-Type' = 'application/json' }
$review = @{ 'X-Api-Key' = $Key; 'X-Operator' = 'tester'; 'Content-Type' = 'application/json' }

function Ask([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    $raw = & curl.exe -s -N -X POST "$Base/api/chat" -H 'X-Operator: tester' -H 'Content-Type: application/json' --data-binary $body 2>$null
    $lines = $raw -split "`n"
    $plan = ($lines | Where-Object { $_ -match '"intent":"' } | Select-Object -First 1)
    $text = ($lines | Where-Object { $_ -match '^data:\{"text"' } | ForEach-Object {
                ($_ -replace '^data:', '') | ConvertFrom-Json
            } | ForEach-Object { $_.text }) -join ''
    Write-Host "你> $msg"
    Write-Host ("它> " + ($text -replace "`n", ' '))
    if ($plan) { Write-Host ("   [plan] " + ($plan -replace '^data:', '')) }
    return $text
}

Write-Host "===== 1. 会话与记忆（后两句都省略了主语）====="
$s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $chat -Body (@{ title = 'P2 手工验收' } | ConvertTo-Json -Compress)
$sid = $s.session.id
Ask $sid '最近跑过哪些对账任务？' | Out-Null
Ask $sid '那单两边对得上吗？' | Out-Null
Ask $sid '为什么差这么多？' | Out-Null

Write-Host ""
Write-Host "===== 2. 起草一条规则（走 MCP，确定性，便于后面验审批）====="
$script:session = $null
function McpSend($body) {
    $h = @{ 'Accept' = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
    if ($script:session) { $h['Mcp-Session-Id'] = $script:session }
    $raw = $body | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-WebRequest -Uri "$Base/mcp" -Method Post -Headers $h -Body ([System.Text.Encoding]::UTF8.GetBytes($raw)) -UseBasicParsing
    $newSid = $r.Headers['Mcp-Session-Id']
    if ($newSid) { $script:session = "$newSid" }
    $t = $r.Content
    if ($t -match 'data:\s*(\{.*\})') { $t = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($t)) { return $null }
    return $t | ConvertFrom-Json
}

McpSend @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{ protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'p2-manual'; version = '1.0' } } } | Out-Null
McpSend @{ jsonrpc = '2.0'; method = 'notifications/initialized' } | Out-Null

$yaml = Get-Content -Raw (Join-Path $here 'manual-test-rule.yml')
$call = McpSend @{ jsonrpc = '2.0'; id = 2; method = 'tools/call'; params = @{ name = 'recon_define_rule'; arguments = @{ ruleBody = $yaml } } }
$texts = @($call.result.content | Where-Object { $_.type -eq 'text' })
$first = ($texts | Select-Object -First 1).text
($first -split "`n" | Select-Object -First 3) | ForEach-Object { "   " + $_ }

$cat = Invoke-RestMethod -Uri "$Base/api/rules/$Code" -Headers $chat
$ver = ($cat.versions | Select-Object -Last 1).version
Write-Host ("   当前最新版本：v" + $ver + "（lifecycle=" + ($cat.versions | Select-Object -Last 1).lifecycle + "）")

Write-Host ""
Write-Host "===== 3. 同步试算（闸门 2，同步返回）====="
$trial = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$Code/$ver/trial" -Headers $review -Body '{}'
$verdict = $trial.state.verdict
Write-Host ("   verdict=" + $verdict + "  coverage=" + $trial.state.coverage + "  reasons=" + ($trial.state.reasons -join ','))
if ($verdict -ne 'PASS') {
    Write-Host "   ⛔ 试算没通过，闸门 2 挡住了 —— 这不是 bug，是它在工作。"
    exit 0
}

Write-Host ""
Write-Host "===== 4. 提交审核 + 领审批 token（人工通道，带审批 key）====="
$null = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$Code/$ver/submit" -Headers $review -Body '{}'
$tk = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$Code/$ver/token" -Headers $review -Body '{}'
Write-Host ("   token 已签发：绑定 (code, version, 内容哈希)，改一个字段就作废")

Write-Host ""
Write-Host "===== 5. 人工批准（把 token 原样传回去）====="
$ap = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$Code/$ver/approve" -Headers $review -Body (@{ token = $tk.approvalToken; note = '手工验收批准' } | ConvertTo-Json -Compress)
$after = Invoke-RestMethod -Uri "$Base/api/rules/$Code" -Headers $chat
Write-Host ("   批准后 active=" + $after.active + "，生效版本=" + (($after.versions | Where-Object { $_.lifecycle -eq 'ACTIVE' }) | ForEach-Object { "v" + $_.version }))

Write-Host ""
Write-Host "===== 6. 用刚批准的那条规则跑一次 ====="
$out = Ask $sid "用 $Code 跑一次"
Write-Host ("   回复里含任务号: " + [bool]($out -match '#\d+'))

Write-Host ""
Write-Host "===== 7. 审计轨迹（两个 header 都要）====="
$runs = Invoke-RestMethod -Uri "$Base/api/audit/runs" -Headers @{ 'X-Operator' = 'tester'; 'X-Api-Key' = $Key }
$runs.items | Select-Object -First 3 | ForEach-Object { "   run #" + $_.id + " " + $_.status + " · " + $_.goal }
Write-Host ""
Write-Host "单次轨迹： curl.exe -s `"$Base/api/audit/runs/1`" -H `"X-Operator: tester`" -H `"X-Api-Key: $Key`""


