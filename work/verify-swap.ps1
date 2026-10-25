$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18090'
$operator = 'alice'

$session = Invoke-RestMethod -Method Post -Uri "$base/api/chat/sessions" -Headers @{ 'X-Operator' = $operator } -ContentType 'application/json' -Body (@{ title = '换边与一次说全' } | ConvertTo-Json)
$id = $session.session.id
"session=$id"

function Send([string]$msg) {
    $body = @{ sessionId = $id; message = $msg } | ConvertTo-Json -Compress
    $null = & curl.exe -sN -X POST "$base/api/chat" -H "Content-Type: application/json" -H "X-Operator: $operator" -d $body
    $detail = Invoke-RestMethod "$base/api/chat/sessions/$id`?limit=200" -Headers @{ 'X-Operator' = $operator }
    $last = ($detail.turns | Where-Object role -eq 'assistant' | Select-Object -Last 1).content
    "--- 用户: $msg"
    "--- 助手: $last"
    ''
}

Send '起草个规则，用订单和状态对金额'
Send '就这两张'
Send '翻一下顺序'
