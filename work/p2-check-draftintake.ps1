param([int]$Port = 18097)
$ErrorActionPreference = 'Stop'
$Base = "http://127.0.0.1:$Port"
function New-Session([string]$title) {
  $body = [Text.Encoding]::UTF8.GetBytes((@{ title = $title } | ConvertTo-Json -Compress))
  $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers @{'X-Operator'='tester'} -ContentType 'application/json; charset=utf-8' -Body $body
  return [long]$s.session.id
}
$tmp = [IO.Path]::GetTempFileName()
function Ask([long]$sid, [string]$m) {
  [IO.File]::WriteAllText($tmp, (@{sessionId=$sid; message=$m} | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding($false)))
  $lines = & curl.exe -s -N --max-time 180 -X POST "$Base/api/chat" -H 'X-Operator: tester' -H 'Content-Type: application/json' --data-binary "@$tmp"
  $text = ''; $tools = @(); $plan = ''; $budget = ''
  foreach ($l in $lines) {
    if ($l -notlike 'data:*') { continue }
    $j = $l.Substring(5).Trim() | ConvertFrom-Json
    if ($j.text) { $text += $j.text }
    if ($j.name) { $tools += $j.name }
    if ($j.turnTokens) { $budget = "turn=$($j.turnTokens) used=$($j.tokensUsed) $($j.phase)" }
    if ($j.tool) { $plan = "tool=$($j.tool) source=$($j.source) intent=$($j.intent)" }
    elseif ($j.reply) { $plan = "reply source=$($j.source)" }
  }
  Write-Output ("USER  > " + $m)
  Write-Output ("PLAN  > " + $plan + "  " + $budget)
  Write-Output ("TOOLS > " + ($tools -join ','))
  Write-Output ("BOT   > " + $text.TrimEnd())
  Write-Output ''
}
$sid = New-Session 'draft intake check'
Ask $sid '起草个规则：按订单、资源对账'
Ask $sid 'bill_left'
Ask $sid 'bill_right'
Ask $sid '资源就用状态吧'
Ask $sid '比金额'
Ask $sid '确认'
