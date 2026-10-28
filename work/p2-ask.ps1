param([int]$Port = 18097, [string]$Operator = 'alice', [long]$Session = 0, [Parameter(Mandatory=$true)][string]$Message)

$Base = "http://127.0.0.1:$Port"
if ($Session -le 0) { throw "need -Session" }
$payload = @{ sessionId = $Session; message = $Message } | ConvertTo-Json
$tmp = Join-Path $env:TEMP ("ask-" + [guid]::NewGuid().ToString('N') + ".json")
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
