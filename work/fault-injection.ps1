# 故障注入：验证"降级而不是报错"
#
# 为什么要有这个脚本：上线后第一次出事，几乎必然是"模型不听话/模型不可用/模型写错",
# 而不是意图识别。这三条契约必须能自动化复现，否则每次只能靠人肉回忆。
#
# 用法：
#   pwsh work/fault-injection.ps1 -Port 18096 -StubPort 18999
# 前置：应用跑在 -Port（agent 开 + 配了桩模型），桩模型跑在 -StubPort。

param(
    [int]$Port = 18096,
    [int]$StubPort = 18999,
    [string]$Operator = "fault-injection"
)

$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:$Port"
$repo = "D:\workspace\deepseek\recon-lens"
$results = @()

function New-Session {
    $body = @{ title = "fault-injection" } | ConvertTo-Json
    $r = Invoke-RestMethod "$base/api/chat/sessions" -Method Post -Body $body -ContentType "application/json" `
        -Headers @{ "X-Operator" = $Operator }
    # 返回体是 {session:{id:..,title:..}} —— 值在 .session.id 上（踩过两次，别改回去）
    $id = $null
    if ($r.session) { $id = $r.session.id }
    if (-not $id) { $id = $r.sessionId }
    if (-not $id) { $id = $r.id }
    return $id
}

# 发一句，返回 SSE 原文（不解析事件：这里只关心"有没有把 500 抛给调用方"）
function Send-Chat([int]$sessionId, [string]$message) {
    $body = @{ sessionId = $sessionId; message = $message } | ConvertTo-Json
    try {
        $r = Invoke-WebRequest "$base/api/chat" -Method Post -Body $body -ContentType "application/json" `
            -Headers @{ "X-Operator" = $Operator; Accept = "text/event-stream" } -TimeoutSec 90 -UseBasicParsing
        return @{ ok = $true; status = $r.StatusCode; body = $r.Content }
    } catch {
        $status = 0
        $errBody = $_.Exception.Message
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $errBody = $sr.ReadToEnd()
            } catch { }
        }
        return @{ ok = $false; status = $status; body = $errBody }
    }
}

function Assert-Row([string]$name, [bool]$pass, [string]$evidence) {
    $script:results += [pscustomobject]@{ case = $name; pass = $pass; evidence = $evidence }
    if ($pass) { Write-Host "[PASS] $name :: $evidence" } else { Write-Host "[FAIL] $name :: $evidence" }
}

# ---------- A. 模型返回坏 JSON → 降级到词表，不把异常抛给用户 ----------
Set-Content -Path "$repo\logs\stub-reply.txt" -Value "这不是 JSON，模型开始胡说" -Encoding utf8
$sid = New-Session
$r = Send-Chat $sid "帮我看看有哪些对账规则"
$degraded = $r.ok -and $r.body -match '"event":"done"' -or ($r.ok -and $r.body.Length -gt 100)
Assert-Row "A 坏 JSON" ($r.ok -and $r.body -notmatch '"status":500') "HTTP $($r.status)，仍返回了对话内容（$($r.body.Length) 字符）"

# ---------- B. 模型进程整个不可用 → 降到 P1，不 500 ----------
$stub = Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'stub-llm' }
$stubPid = $null
if ($stub) { $stubPid = ($stub | Select-Object -First 1).ProcessId; Stop-Process -Id $stubPid -Force }
Start-Sleep -Seconds 1
$sid2 = New-Session
$r2 = Send-Chat $sid2 "跑一下 BILL_VS_SETTLE"
$bodyOk = $r2.ok -and $r2.status -eq 200
Assert-Row "B 模型不可用" $bodyOk "HTTP $($r2.status)，$($r2.body.Length) 字符"

# 恢复桩模型，别把环境留成坏的
$stubLog = Start-Process node -ArgumentList 'tools\stub-llm.mjs' -WorkingDirectory $repo -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 2

# C 要让"闸门 1 拒绝"真正发生：给桩模型一份结构合法、但用了 HASH() 的规则体。
$badRule = @'
{
  "code": "FAULT_HASH_RECON",
  "name": "故障注入用规则",
  "dimension": ["customer_id"],
  "shard": { "count": 4, "parallelism": 2, "strategy": "MOD" },
  "left": {
    "table": "bill_left",
    "sql": "SELECT id, customer_id, amount, status, updated_at FROM bill_left WHERE HASH(customer_id) % 4 = 0",
    "columns": { "key": "customer_id", "amount": "amount", "status": "status" }
  },
  "right": {
    "table": "bill_right",
    "sql": "SELECT id, customer_id, amount, status, updated_at FROM bill_right",
    "columns": { "key": "customer_id", "amount": "amount", "status": "status" }
  },
  "compare": [ { "field": "amount", "type": "AMOUNT", "tolerance": 0.01 } ],
  "diff": { "missing": { "diffType": "MISSING_RIGHT" }, "duplicate": { "diffType": "DUPLICATE_KEY" } }
}
'@
Set-Content -Path "$repo\logs\stub-reply.txt" -Value $badRule -Encoding utf8

# ---------- C. 起草链路拿到致命违规 → 会话 BLOCKED（不是 500，也不是成功） ----------
# 用 HASH() 造一个闸门 1 一定会拒的规则体：验证"拒绝是结构化结论，不是异常"。
# 走 /mcp（streamable HTTP）：必须带 Accept: application/json, text/event-stream，
# 响应是 SSE 帧（data: {...}），直接 ConvertFrom-Json 会失败 —— 见下面 Send-Mcp。
$mcp = "$base/mcp"
$script:session = $null

function Send-Mcp($body) {
    $h = @{ 'Accept' = 'application/json, text/event-stream'; 'Content-Type' = 'application/json' }
    if ($script:session) { $h['Mcp-Session-Id'] = $script:session }
    $raw = $body | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-WebRequest -Uri $mcp -Method Post -Headers $h `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($raw)) -UseBasicParsing
    $sid = $r.Headers['Mcp-Session-Id']
    if ($sid) { $script:session = "$sid" }
    $text = $r.Content
    if ($text -match 'data:\s*(\{.*\})') { $text = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

$null = Send-Mcp @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
    protocolVersion = '2025-06-18'; capabilities = @{}; clientInfo = @{ name = 'fault'; version = '1.0' } } }
$null = Send-Mcp @{ jsonrpc = '2.0'; method = 'notifications/initialized' }

$call = Send-Mcp @{ jsonrpc = '2.0'; id = 2; method = 'tools/call'; params = @{
    name = 'recon_draft_rule'
    arguments = @{
        businessDescription = '按 customer_id 分片，用 HASH 算分片下标，对账金额'
        ruleCode = 'FAULT_HASH_RECON'
    } } }
$text = (($call.result.content) | Where-Object { $_.type -eq 'text' } | Select-Object -First 1).text
# 工具文案是「已创建 Agent 会话 #3」而不是 runId=N —— 正则要认这个
$runId = $null
if ($text -match '会话 #(\d+)') { $runId = [int]$Matches[1] }
if (-not $runId -and $text -match 'runId=(\d+)') { $runId = [int]$Matches[1] }

$status = "unknown"
$stepText = ""
if ($runId) {
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        $audit = Invoke-RestMethod "$base/api/audit/runs/$runId" -Headers @{ "X-Operator" = $Operator; "X-Api-Key" = "vv" }
        $status = $audit.run.status
        $stepText = ($audit.steps | ForEach-Object { "$($_.action)/$($_.status)/$($_.payload)" }) -join " | "
        if ($status -in @("SUCCESS", "PARTIAL", "BLOCKED", "FAILED")) { break }
    }
}
$gateSeen = $stepText -match 'SAFETY_VIOLATION' -or $stepText -match 'NON_PORTABLE' -or $stepText -match '闸门'
Assert-Row "C 闸门拒绝" ($runId -ne $null -and $status -eq "BLOCKED" -and $gateSeen) `
    "runId=$runId，终态 $status，轨迹里看到闸门拒绝：$gateSeen"

# ---------- 降级矩阵 ----------
$lines = @()
$lines += "# 降级矩阵"
$lines += ""
$lines += "> 由 ``work/fault-injection.ps1`` 生成。契约：**降级，而不是把异常抛给调用方**。"
$lines += ""
$lines += "| 注入的故障 | 期望 | 实测 | 判定 |"
$lines += "|---|---|---|---|"
$lines += "| 模型返回坏 JSON | 回退词表，会话仍有回复 | $($results[0].evidence) | $(if ($results[0].pass) { 'PASS' } else { '**FAIL**' }) |"
$lines += "| 模型进程不可用 | 降到 P1，不 500 | $($results[1].evidence) | $(if ($results[1].pass) { 'PASS' } else { '**FAIL**' }) |"
$lines += "| 起草拿到致命违规 | 会话进入 BLOCKED/PARTIAL，不抛 500 | $($results[2].evidence) | $(if ($results[2].pass) { 'PASS' } else { '**FAIL**' }) |"
$lines += ""
$lines += "未自动化的一项：**数据库方言不支持**（H2 缺 ``CRC32``）—— 已在 ``docs/report-real-llm.md`` 里实测过："
$lines += "试算 4 个分片全 FAILED → 会话 ``PARTIAL`` 且 ``errorMsg`` 写明 ``CANARY_FAILED``，"
$lines += "而不是静默 ``SUCCESS``。它需要改库（DROP ALIAS），不适合放进常规冒烟。"
$lines | Set-Content "$repo\docs\degradation-matrix.md" -Encoding utf8

$failed = ($results | Where-Object { -not $_.pass }).Count
Write-Host "`n降级矩阵已写入 docs/degradation-matrix.md，失败 $failed 项"
if ($failed -gt 0) { exit 1 }
