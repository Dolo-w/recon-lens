param([int]$Port = 18097, [string]$Operator = 'alice', [string]$Message = '起草个规则：按订单、客户、日期对账，比金额，左表 bill_left 右表 bill_right')

$Base = "http://127.0.0.1:$Port"
$headers = @{ 'X-Operator' = $Operator }
$body = '{"operator":"' + $Operator + '"}'
$r = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -ContentType 'application/json; charset=utf-8' -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body))
$sessionId = [long]$r.session.id
Write-Host "session=$sessionId"

$payload = @{ sessionId = $sessionId; message = $Message } | ConvertTo-Json
$tmp = Join-Path $env:TEMP ("guess-" + [guid]::NewGuid().ToString('N') + ".json")
[IO.File]::WriteAllBytes($tmp, [Text.Encoding]::UTF8.GetBytes($payload))

Write-Host "USER> $Message"
$out = & curl.exe -s -N --max-time 150 -H "Content-Type: application/json; charset=utf-8" -H "X-Operator: $Operator" --data-binary "@$tmp" "$Base/api/chat"
foreach ($line in $out) {
    if ($line -match '^data:(.*)$') {
        $o = $Matches[1] | ConvertFrom-Json
        if ($o.text) { Write-Host ("它> " + $o.text) }
        elseif ($o.name) { Write-Host ("  [tool] " + $o.name) }
        elseif ($o.source) { Write-Host ("  [plan] " + $o.source + " " + $o.reason) }
    }
}
Remove-Item $tmp -ErrorAction SilentlyContinue
