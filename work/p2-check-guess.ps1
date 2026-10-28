param([int]$Port = 18097)
$ErrorActionPreference = 'Stop'
$Base = "http://127.0.0.1:$Port"
function New-Session([string]$title) {
  $body = [Text.Encoding]::UTF8.GetBytes((@{ title = $title } | ConvertTo-Json -Compress))
  $s = Invoke-RestMethod -Method Post -Uri "$Base/api/chat/sessions" -Headers @{'X-Operator'='alice'} -ContentType 'application/json; charset=utf-8' -Body $body
  return [long]$s.session.id
}
$tmp = [IO.Path]::GetTempFileName()
function Ask([long]$sid, [string]$m) {
  [IO.File]::WriteAllText($tmp, (@{sessionId=$sid; message=$m} | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding($false)))
  $lines = & curl.exe -s -N --max-time 180 -X POST "$Base/api/chat" -H 'X-Operator: alice' -H 'Content-Type: application/json' --data-binary "@$tmp"
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

$sid = New-Session 'alice 模型猜列2'
Ask $sid '起草个规则：左 bill_left 右 bill_right'
Ask $sid '日期'
Ask $sid '是'
Ask $sid '订单号'
Ask $sid '比金额'