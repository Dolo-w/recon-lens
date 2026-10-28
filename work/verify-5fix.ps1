param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Description,
    [string]$RuleCode = 'CRC32_PROBE_RECON'
)

# 通过 streamable HTTP（端点 /mcp）驱动一次 Agent 起草，打印工具返回的文本。
# 用法： pwsh -File work\verify-5fix.ps1 -Port 18092 -Description '按客户号逐笔比对账单与结算金额'
$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port/mcp"
$session = $null

function Send($body, $id) {
    $h = @{ 'Accept' = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
    if ($session) { $h['Mcp-Session-Id'] = $session }
    $raw = $body | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-WebRequest -Uri $base -Method Post -Headers $h -Body ([System.Text.Encoding]::UTF8.GetBytes($raw)) -UseBasicParsing
    $sid = $r.Headers['Mcp-Session-Id']
    if ($sid) { $script:session = "$sid" }
    $text = $r.Content
    if ($text -match 'data:\s*(\{.*\})') { $text = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

$init = Send @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
    protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'verify-5fix'; version = '1.0' } } } 1
Write-Host ("initialize ok, protocol=" + $init.result.protocolVersion)

Send @{ jsonrpc = '2.0'; method = 'notifications/initialized' } $null | Out-Null

$call = Send @{ jsonrpc = '2.0'; id = 2; method = 'tools/call'; params = @{
    name = 'recon_draft_rule'
    arguments = @{ businessDescription = $Description; ruleCode = $RuleCode } } } 2

$text = ($call.result.content | Where-Object { $_.type -eq 'text' } | Select-Object -First 1).text
Write-Host '---- 工具返回 ----'
Write-Host $text
