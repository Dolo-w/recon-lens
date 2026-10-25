param(
    [string]$Base = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
$script:curEvent = $null

function Send-Chat {
    param([string]$Operator, [string]$Message, [Nullable[long]]$SessionId = $null)
    $body = @{ message = $Message }
    if ($SessionId) { $body.sessionId = $SessionId }
    $path = Join-Path $env:TEMP ("chat_req_" + [guid]::NewGuid().ToString('N') + ".json")
    $body | ConvertTo-Json -Compress | Set-Content -Path $path -Encoding utf8 -NoNewline
    $raw = & curl.exe -s -N -X POST "$Base/api/chat" -H "X-Operator: $Operator" `
        -H "Content-Type: application/json" --data-binary "@$path"
    Remove-Item $path -ErrorAction SilentlyContinue
    return ($raw -join "`n")
}

function Show-Events {
    param([string]$Text)
    $events = @()
    foreach ($line in ($Text -split "`n")) {
        if ($line -match '^event:(\S+)') { $script:curEvent = $Matches[1] }
        elseif ($line -match '^data:(.*)$' -and $script:curEvent) {
            $events += [pscustomobject]@{ Event = $script:curEvent; Data = $Matches[1] }
            $script:curEvent = $null
        }
    }
    return $events
}

function Show-Result {
    param([string]$Label, [string]$Text, [int]$TextLimit = 400)
    $events = Show-Events $Text
    Write-Host "== $Label ==" -ForegroundColor Cyan
    $sb = ""
    foreach ($e in $events) {
        if ($e.Event -eq 'delta') { $sb += ($e.Data | ConvertFrom-Json).text }
        elseif ($e.Event -eq 'error') { Write-Host ("  [error] " + ($e.Data | ConvertFrom-Json).message) -ForegroundColor Red }
        else { Write-Host ("  [" + $e.Event + "] " + $e.Data) -ForegroundColor DarkGray }
    }
    if ($sb.Length -gt 0) {
        $shown = if ($sb.Length -gt $TextLimit) { $sb.Substring(0, $TextLimit) + " …" } else { $sb }
        Write-Host ("  [正文] " + ($shown -replace "`n", "`n         "))
    }
    $done = $events | Where-Object { $_.Event -eq 'done' } | Select-Object -First 1
    if ($done) { Write-Host ("  -> sessionId = " + (($done.Data | ConvertFrom-Json).sessionId)) -ForegroundColor Yellow }
    return $events
}

Write-Host "`n### 场景 1：新建会话 + 帮助" -ForegroundColor Green
$ev1 = Show-Events (Send-Chat -Operator 'zhang' -Message '你能做什么')
$sessionId = [long](($ev1 | Where-Object Event -eq 'done' | Select-Object -First 1).Data | ConvertFrom-Json).sessionId
Write-Host "  新建会话 sessionId = $sessionId，事件序列：" ($ev1.Event -join ' → ') -ForegroundColor Yellow

Write-Host "`n### 场景 2：同一会话 列出规则" -ForegroundColor Green
Show-Result "zhang / 有哪些规则" (Send-Chat -Operator 'zhang' -Message '有哪些规则' -SessionId $sessionId) 200 | Out-Null

Write-Host "`n### 场景 3：跑一次" -ForegroundColor Green
Show-Result "zhang / 跑一下 BILL_VS_SETTLE" (Send-Chat -Operator 'zhang' -Message '跑一下 BILL_VS_SETTLE' -SessionId $sessionId) 500 | Out-Null

Write-Host "`n### 场景 4：省略任务号追问（槽位）" -ForegroundColor Green
Show-Result "zhang / 为什么差这么多" (Send-Chat -Operator 'zhang' -Message '为什么差这么多' -SessionId $sessionId) 500 | Out-Null

Write-Host "`n### 场景 5：语义差异类型 → 规则真实类型" -ForegroundColor Green
Show-Result "zhang / 值不等的差异有哪些" (Send-Chat -Operator 'zhang' -Message '值不等的差异有哪些' -SessionId $sessionId) 500 | Out-Null

Write-Host "`n### 场景 6：新会话里没有任务号就要求给号（不猜最近一个）" -ForegroundColor Green
$ev6 = Show-Events (Send-Chat -Operator 'zhang' -Message '看差异')
$text6 = ""
$ev6 | Where-Object Event -eq 'delta' | ForEach-Object { $text6 += ($_.Data | ConvertFrom-Json).text }
Write-Host ("  intent=" + (($ev6 | Where-Object Event -eq 'intent').Data)) -ForegroundColor DarkGray
Write-Host ("  [正文] " + ($text6 -replace "`n", " "))

Write-Host "`n### 场景 7：交接给 lisi，之后原 owner 只能看不能写" -ForegroundColor Green
$ho = & curl.exe -s -X POST "$Base/api/chat/sessions/$sessionId/handover" -H "X-Operator: zhang" `
    -H "Content-Type: application/json" -d '{"toOperator":"lisi"}'
Write-Host ("  交接结果: " + $ho)
$ev7 = Show-Events (Send-Chat -Operator 'zhang' -Message '再看一次差异' -SessionId $sessionId)
$err7 = $ev7 | Where-Object Event -eq 'error' | Select-Object -First 1
if ($err7) { Write-Host ("  原 owner 写入 -> [error] " + ($err7.Data | ConvertFrom-Json).message) -ForegroundColor Red }
else { Write-Host "  原 owner 竟然写进去了（不符合预期）" -ForegroundColor Red }
$ev7b = Show-Events (Send-Chat -Operator 'lisi' -Message '看差异' -SessionId $sessionId)
Write-Host ("  新 owner 写入 -> intent=" + (($ev7b | Where-Object Event -eq 'intent' | Select-Object -First 1).Data)) -ForegroundColor Yellow

Write-Host "`n### 场景 8：越权与会话不存在都是 404" -ForegroundColor Green
$c1 = & curl.exe -s -o NUL -w "%{http_code}" -X POST "$Base/api/chat" -H "X-Operator: wangwu" -H "Content-Type: application/json" -d ("{`"sessionId`":$sessionId,`"message`":`"看差异`"}")
$c2 = & curl.exe -s -o NUL -w "%{http_code}" -X POST "$Base/api/chat" -H "X-Operator: zhang" -H "Content-Type: application/json" -d '{"sessionId":999999,"message":"看差异"}'
Write-Host "  别人的会话 -> HTTP $c1 ; 不存在的会话 -> HTTP $c2"

Write-Host "`n### 场景 8b：交接后谁能读（只有原 owner 和新 owner）" -ForegroundColor Green
$rZhang = & curl.exe -s -o NUL -w "%{http_code}" "$Base/api/chat/sessions/$sessionId" -H "X-Operator: zhang"
$rLisi  = & curl.exe -s -o NUL -w "%{http_code}" "$Base/api/chat/sessions/$sessionId" -H "X-Operator: lisi"
$rWang  = & curl.exe -s -o NUL -w "%{http_code}" "$Base/api/chat/sessions/$sessionId" -H "X-Operator: wangwu"
Write-Host "  原 owner zhang -> HTTP $rZhang ; 新 owner lisi -> HTTP $rLisi ; 第三方 wangwu -> HTTP $rWang"
$ev8b = Show-Events (Send-Chat -Operator 'wangwu' -Message '看差异' -SessionId $sessionId)
if ($ev8b.Count -eq 0) { Write-Host "  wangwu 打 /api/chat 被挡在流之外（401/404，无事件）" -ForegroundColor Yellow }

Write-Host "`n### 场景 9：缺 X-Operator -> 400，空消息 -> 400" -ForegroundColor Green
$c3 = & curl.exe -s -o NUL -w "%{http_code}" -X POST "$Base/api/chat" -H "Content-Type: application/json" -d '{"message":"帮助"}'
$c4 = & curl.exe -s -o NUL -w "%{http_code}" -X POST "$Base/api/chat" -H "X-Operator: zhang" -H "Content-Type: application/json" -d '{"message":"  "}'
Write-Host "  无 header -> HTTP $c3 ; 空消息 -> HTTP $c4"
