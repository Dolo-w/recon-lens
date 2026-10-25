# 迭代 12 实测：案例按数据版本收窄 + 审计导出（P3 第 2/3 项）
# 用法（pwsh 7）：先确保 18080 端口上的 jar 已启动，然后 $ ./work/iter12-smoke.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$script:out = @()
$script:pass = 0
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) { $script:pass++; $script:out += "PASS  $name" }
    else { $script:fail++; $script:out += "FAIL  $name  :: $detail" }
}

function Api($method, $path, $body, $headers) {
    $params = @{ Uri = "$base$path"; Method = $method; UseBasicParsing = $true; SkipHttpErrorCheck = $true }
    if ($headers) { $params.Headers = $headers }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Compress -Depth 8); $params.ContentType = 'application/json' }
    $r = Invoke-WebRequest @params
    return @{ Status = [int]$r.StatusCode; Text = $r.Content }
}

$ops = @{ 'X-Operator' = 'alice' }

# ---------- A 服务就绪 ----------
$h = Api 'GET' '/api/rules' $null $null
Check 'A1 服务就绪 /api/rules 200' ($h.Status -eq 200) "status=$($h.Status)"

# ---------- B 跑一次对账，拿到 jobId 与数据版本 ----------
$run = Api 'POST' '/api/jobs' @{ ruleCode = 'BILL_VS_SETTLE'; remark = 'iter12' } $null
Check 'B1 POST /api/jobs 受理（200/202 都是受理）' ($run.Status -eq 200 -or $run.Status -eq 202) "status=$($run.Status) body=$($run.Text)"
$jobId = ($run.Text | ConvertFrom-Json).jobId
Check 'B2 拿到 jobId' ($jobId -gt 0) "jobId=$jobId"

$jobText = (Api 'GET' "/api/jobs/$jobId" $null $null).Text
# 详情是 {job:{...}, dataSnapshot:{...}} 信封：dataSnapshot 在根上，不在 job 里
$job = $jobText | ConvertFrom-Json
# 有快照时不会塞 recorded 字段（只有"没有快照"才回 recorded=false）
Check 'B3 任务详情带 dataSnapshot（读过哪两张表）' ($job.dataSnapshot.left.table -eq 'bill_left' -and $job.dataSnapshot.right.table -eq 'bill_right') "body=$($jobText.Substring(0,[Math]::Min(160,$jobText.Length)))"
Check 'B4 左表指纹有 schemaHash 与行数' ($job.dataSnapshot.left.schemaHash.Length -eq 64 -and $job.dataSnapshot.left.rowCount -gt 0) "left=$($job.dataSnapshot.left | ConvertTo-Json -Compress)"

# ---------- C 案例记忆：同规则三条，分属别的版本 / 本版本 / 没记版本 ----------
$null = Api 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'
    causeKey = 'C002|ORD20260003'; resolution = '旧版本：按旧口径忽略手续费'; operator = 'alice'
    datasetVersion = 'v-old-111' } $ops
$null = Api 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'
    causeKey = 'C002|ORD20260003'; resolution = '这一版：手续费进位，不改数据'; operator = 'alice'
    datasetVersion = 'v-new-222' } $ops
$null = Api 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'
    causeKey = 'C002|ORD20260003'; resolution = '没记版本：人工确认过'; operator = 'alice' } $ops

$all = (Api 'GET' '/api/cases/recall?ruleCode=BILL_VS_SETTLE&diffType=AMOUNT_MISMATCH&limit=10' $null $null).Text | ConvertFrom-Json
Check 'C1 不传版本时三条都在' ($all.total -ge 3) "total=$($all.total)"
Check 'C2 召回结果带 score（时间衰减已算）' ($null -ne $all.items[0].score) "item=$($all.items[0] | ConvertTo-Json -Compress)"

$nv = (Api 'GET' '/api/cases/recall?ruleCode=BILL_VS_SETTLE&diffType=AMOUNT_MISMATCH&datasetVersion=v-new-222&limit=10' $null $null).Text | ConvertFrom-Json
$versions = @($nv.items | ForEach-Object { [string]$_.datasetVersion })
Check 'C3 指定 v-new-222 时旧版本案例不出现' (-not ($versions -contains 'v-old-111')) "versions=$($versions -join '|')"
Check 'C4 指定版本时同版本仍召回' ($versions -contains 'v-new-222') "versions=$($versions -join '|')"
Check 'C5 响应回显筛选用的是哪一版' ($nv.datasetVersion -eq 'v-new-222') "echo=$($nv.datasetVersion)"

# ---------- D 归因里能看到案例段 ----------
$diffPage = (Api 'GET' "/api/diffs?jobId=$jobId&limit=5" $null $null).Text | ConvertFrom-Json
# 挑金额不一致那条：案例是写在 AMOUNT_MISMATCH 上的，拿 DUPLICATE_KEY 那条当然没有历史案例
$amountDiff = $diffPage.items | Where-Object { $_.diffType -match 'MISMATCH' } | Select-Object -First 1
$diffId = if ($amountDiff) { $amountDiff.id } else { $diffPage.items[0].id }
Check 'D1 拿到一条差异' ($diffId -gt 0) "total=$($diffPage.total)"
$explained = Api 'POST' "/api/diffs/$diffId/explain" $null $null
$exp = $explained.Text | ConvertFrom-Json
Check 'D2 归因 200' ($explained.Status -eq 200) "status=$($explained.Status)"
Check 'D3 归因正文含历史处理段' ($exp.explanation -match '同类差异的历史处理') "text=$($exp.explanation.Substring(0,[Math]::Min(120,$exp.explanation.Length)))"
Check 'D4 recalled 标记为真' ($exp.recalled -eq $true) "recalled=$($exp.recalled)"

# ---------- E 审计导出 ----------
$aud = Api 'GET' '/api/audit/runs?limit=5' $null $ops
Check 'E1 审计运行列表 200' ($aud.Status -eq 200) "status=$($aud.Status)"
Check 'E3 缺 X-Operator 400' ((Api 'GET' '/api/audit/runs' $null $null).Status -eq 400) "no operator"

$csv = Api 'GET' '/api/audit/runs?format=csv&limit=3' $null $ops
$bom = [string][char]0xFEFF
Check 'E4 CSV 200 且以 BOM+表头开头' ($csv.Status -eq 200 -and $csv.Text.StartsWith($bom + 'id,createdAt,status')) "head=$($csv.Text.Substring(0,[Math]::Min(50,$csv.Text.Length)))"

$appr = Api 'GET' '/api/audit/approvals?limit=5' $null $ops
Check 'E5 审批留痕 200 且有 total' ($appr.Status -eq 200 -and $appr.Text -match '"total"') "body=$($appr.Text.Substring(0,[Math]::Min(120,$appr.Text.Length)))"

Check 'E6 时间格式错 400' ((Api 'GET' '/api/audit/runs?from=2026/10/26' $null $ops).Status -eq 400) "bad time"
$missing = Api 'GET' '/api/audit/runs/999999' $null $ops
Check 'E7 不存在的运行 400 且说清原因' ($missing.Status -eq 400 -and $missing.Text -match '没有这次运行') "body=$($missing.Text)"

# 审计只覆盖 recon_agent_run；内建会话是确定性路由、不建 run，
# 所以这里用一个真会写 run 的动作：把会话交接给 bob（触发 SESSION_HANDOVER 审计）
$sid0 = ((Api 'POST' '/api/chat/sessions' @{ title = 'audit-src' } $ops).Text | ConvertFrom-Json).session.id
$null = Api 'POST' '/api/chat' @{ sessionId = $sid0; message = '有哪些规则' } $ops
$handover = Api 'POST' "/api/chat/sessions/$sid0/handover" @{ toOperator = 'bob' } $ops
Check 'E9 交接成功（会产生一条审计运行）' ($handover.Status -eq 200) "status=$($handover.Status) body=$($handover.Text.Substring(0,[Math]::Min(120,$handover.Text.Length)))" 

$aud2 = Api 'GET' '/api/audit/runs?limit=5' $null $ops
$aud2j = $aud2.Text | ConvertFrom-Json
Check 'E2 审计能导出刚才那轮会话的运行记录' ($aud2j.total -ge 1 -and $aud2.Text -match '"createdBy"') "total=$($aud2j.total) body=$($aud2.Text.Substring(0,[Math]::Min(160,$aud2.Text.Length)))"
$runId = if ($aud2j.total -ge 1) { $aud2j.items[0].id } else { 0 }
$detail = Api 'GET' "/api/audit/runs/$runId" $null $ops
Check 'E8 运行详情能带出步骤' ($detail.Status -eq 200 -and $detail.Text -match '"steps"') "status=$($detail.Status)"

# ---------- F 会话链路回归 ----------
$sid = ((Api 'POST' '/api/chat/sessions' @{ title = 'iter12' } $ops).Text | ConvertFrom-Json).session.id
$chat = Api 'POST' '/api/chat' @{ sessionId = $sid; message = '看一下差异' } $ops
Check 'F1 会话流式仍可用' ($chat.Status -eq 200 -and $chat.Text.Length -gt 50) "status=$($chat.Status) len=$($chat.Text.Length)"

$script:out | ForEach-Object { $_ }
"----"
"PASS=$script:pass FAIL=$script:fail"
$script:out | Out-File -FilePath (Join-Path $PSScriptRoot 'iter12-smoke.txt') -Encoding utf8
