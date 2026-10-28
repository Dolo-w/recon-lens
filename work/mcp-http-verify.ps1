# 通过 streamable HTTP（端点 /mcp）驱动一次 Agent 起草，用于批 6 的实测取证。
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18087/mcp'
$headers = @{ 'Accept' = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
$session = $null

function Send($body, $id) {
    $h = @{ 'Accept' = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
    if ($session) { $h['Mcp-Session-Id'] = $session }
    $raw = $body | ConvertTo-Json -Depth 10 -Compress
    try {
        $r = Invoke-WebRequest -Uri $base -Method Post -Headers $h -Body ([System.Text.Encoding]::UTF8.GetBytes($raw)) -UseBasicParsing
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            Write-Host "HTTP $([int]$resp.StatusCode): $($sr.ReadToEnd())"
        }
        throw
    }
    $sid = $r.Headers['Mcp-Session-Id']
    if ($sid) { $script:session = "$sid" }
    $text = $r.Content
    if ($text -match 'data:\s*(\{.*\})') { $text = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

$init = Send @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
    protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'batch6-verify'; version = '1.0' } } } 1
Write-Host ("initialize ok, protocol=" + $init.result.protocolVersion + " session=" + $session)

Send @{ jsonrpc = '2.0'; method = 'notifications/initialized' } $null | Out-Null

$call = Send @{ jsonrpc = '2.0'; id = 2; method = 'tools/call'; params = @{
    name = 'recon_draft_rule'
    arguments = @{ businessDescription = '按订单号逐笔比对账单和结算的金额'; ruleCode = 'BILL_BAD' } } } 2

$content = $call.result.content
$text = ($content | Where-Object { $_.type -eq 'text' } | Select-Object -First 1).text
Write-Host "=== recon_draft_rule 返回 ==="
Write-Host $text
if ($call.result.structuredContent) {
    Write-Host "=== structuredContent ==="
    $call.result.structuredContent | ConvertTo-Json -Depth 8
}
