# 冒烟：起草→确认→看〔当前有效〕结论板
$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$op = 'alice'

$created = Invoke-RestMethod -Method Post -Uri "$base/api/chat/sessions" -Headers @{ 'X-Operator' = $op } -ContentType 'application/json' -Body '{"title":"claim 验证"}'
$session = $created.session

function Say([string]$text) {
  $body = '{"sessionId":' + $script:session.id + ',"message":' + (ConvertTo-Json $text -Compress) + '}'
  & curl.exe -s -N -X POST "$base/api/chat" -H "Content-Type: application/json" -H "X-Operator: $op" --data $body | Out-Null
}

Say '起草个规则吧'
Say '左表 bill_left，右表 bill_right'
Say '用订单号连'
Say '比金额'
Say '确认'

$claims = Invoke-RestMethod -Uri "$base/api/chat/sessions/$($session.id)/claims" -Headers @{ 'X-Operator' = $op }
Write-Output ("session={0}" -f $session.id)
Write-Output $claims.block
Write-Output ("items={0}" -f $claims.items.Count)

$turns = Invoke-RestMethod -Uri "$base/api/chat/sessions/$($session.id)?limit=50" -Headers @{ 'X-Operator' = $op }
$turns.turns | Where-Object { $_.role -eq 'assistant' } | Select-Object -Last 1 | ForEach-Object {
  $flat = $_.content -replace "`r?`n", ' '
  Write-Output ("last=" + $flat.Substring(0, [Math]::Min(140, $flat.Length)))
}
