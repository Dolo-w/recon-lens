param([string]$Base = 'http://127.0.0.1:18090')

$h = @{ 'X-Operator' = 'alice'; 'Content-Type' = 'application/json' }

function New-Session([string]$title) {
    $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $h -Body (@{ title = $title } | ConvertTo-Json -Compress)
    return $s.session.id
}

function Send([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    & curl.exe -s -N -X POST "$Base/api/chat" -H 'X-Operator: alice' -H 'Content-Type: application/json' --data-binary $body 2>$null | Out-Null
}

function Last-Reply([long]$sid) {
    $d = Invoke-RestMethod -Uri "$Base/api/chat/sessions/$sid`?limit=200" -Headers $h
    $assistant = @($d.turns | Where-Object { $_.role -eq 'assistant' })
    if ($assistant.Count -eq 0) { return '' }
    return $assistant[-1].content
}

function Ask([long]$sid, [string]$msg) {
    Send $sid $msg
    $r = Last-Reply $sid
    Write-Host "用户> $msg"
    Write-Host ("助手> " + $r)
    return $r
}

Write-Host '=== 场景 A：确认落草稿，回执要是人话 ==='
$a = New-Session '人话回执'
foreach ($m in @('起草个规则', '左 bill_left 右 bill_right', 'order_no', '比金额')) { Ask $a $m | Out-Null }
$reply = Ask $a '确认'
Write-Host ("含 recon_ : " + [bool]($reply -match 'recon_'))
Write-Host ("含 POST /api : " + [bool]($reply -match 'POST /api'))

Write-Host ''
Write-Host '=== 场景 B：拿业务键当比对字段，要当场拦住 ==='
$b = New-Session '比对字段护栏'
foreach ($m in @('起草个规则', '左 bill_left 右 bill_right', 'order_no')) { Ask $b $m | Out-Null }
$blocked = Ask $b '比 order_no'
Write-Host ("含 业务键 : " + [bool]($blocked -match '业务键'))
