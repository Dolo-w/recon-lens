param([string]$Base = 'http://127.0.0.1:18090', [string]$Key = 'demo-approval-key')

$chatHeaders = @{ 'X-Operator' = 'alice'; 'Content-Type' = 'application/json' }
$reviewHeaders = @{ 'X-Api-Key' = $Key; 'X-Operator' = 'alice'; 'Content-Type' = 'application/json' }

function New-Session([string]$title) {
    $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers $chatHeaders -Body (@{ title = $title } | ConvertTo-Json -Compress)
    return $s.session.id
}
function Send([long]$sid, [string]$msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    & curl.exe -s -N -X POST "$Base/api/chat" -H 'X-Operator: alice' -H 'Content-Type: application/json' --data-binary $body 2>$null | Out-Null
}
function Last-Reply([long]$sid) {
    $d = Invoke-RestMethod -Uri "$Base/api/chat/sessions/$sid`?limit=200" -Headers $chatHeaders
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

$sid = New-Session '审批闭环'
foreach ($m in @('起草个规则', '左 bill_left 右 bill_right', 'order_no', '比金额')) { Ask $sid $m | Out-Null }
$confirmText = Ask $sid '确认'
$code = ([regex]::Match($confirmText, '规则 ([A-Z][A-Z0-9_]{2,39})')).Groups[1].Value
if (-not $code) { throw "没从确认回复里认出规则编码：$confirmText" }
Write-Host "RECOGNIZED CODE=$code"

$submit = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$code/1/submit" -Headers $reviewHeaders -Body '{}'
Write-Host ("提交审核 → " + ($submit | ConvertTo-Json -Compress))

$token = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$code/1/token" -Headers $reviewHeaders -Body '{}'
Write-Host ("取审批票 → " + $token.approvalToken)

$approve = Invoke-RestMethod -Method Post -Uri "$Base/api/rule-reviews/$code/1/approve" -Headers $reviewHeaders -Body (@{ token = $token.approvalToken; note = '演示人工批准' } | ConvertTo-Json -Compress)
Write-Host ("批准 → " + ($approve | ConvertTo-Json -Compress))

$rev = Invoke-RestMethod -Uri "$Base/api/rule-reviews/$code/1" -Headers $reviewHeaders
Write-Host ("复核状态 → lifecycle=" + $rev.lifecycle)

$run = Ask $sid "跑一下 $code"
Write-Host ("跑一下里含任务号: " + [bool]($run -match '#\d+'))
