# ADR-0002 收尾实跑：数据快照 / 任务比较 / 指纹
# 前提：应用已在 18080 启动（--recon.agent.enabled=true）
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$pass = 0; $fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; Write-Host ("[PASS] {0} — {1}" -f $name, $detail) }
    else { $script:fail++; Write-Host ("[FAIL] {0} — {1}" -f $name, $detail) -ForegroundColor Red }
}
function Json($method, $path, $body, $headers) {
    $args = @{ Uri = "$base$path"; Method = $method; UseBasicParsing = $true }
    if ($headers) { $args.Headers = $headers }
    if ($body) { $args.Body = ($body | ConvertTo-Json -Compress); $args.ContentType = 'application/json' }
    $r = Invoke-WebRequest @args -SkipHttpErrorCheck
    $parsed = $null
    try { $parsed = $r.Content | ConvertFrom-Json } catch { }
    return @{ status = [int]$r.StatusCode; raw = $r.Content; json = $parsed }
}

Write-Host "`n=== A. 建任务时写数据快照 ==="
$j1 = Json POST '/api/jobs' @{ ruleCode = 'BILL_VS_SETTLE'; sync = $true; batchId = 'adr2-a' }
Check 'A1 任务跑通' ($j1.status -eq 200 -and $j1.json.status -eq 'SUCCESS') "jobId=$($j1.json.jobId) status=$($j1.json.status) diffs=$($j1.json.diffRows)"
$id1 = $j1.json.jobId

$detail = Json GET "/api/jobs/$id1"
$left = $detail.json.dataSnapshot.left
$right = $detail.json.dataSnapshot.right
Check 'A2 详情带数据快照' ($detail.status -eq 200 -and $null -ne $left) "dataSnapshot.left.table=$($left.table) right.table=$($right.table)"
Check 'A3 快照含结构指纹与行数' (($left.schemaHash -is [string]) -and $left.schemaHash.Length -eq 64 -and $left.rowCount -ne $null) "schemaHash=$($left.schemaHash.Substring(0,8))… rowCount=$($left.rowCount) source=$($left.source)"

Write-Host "`n=== B. 两次同规则任务可比 ==="
$j2 = Json POST '/api/jobs' @{ ruleCode = 'BILL_VS_SETTLE'; sync = $true; batchId = 'adr2-b' }
$id2 = $j2.json.jobId
$cmp = Json GET "/api/jobs/compare?a=$id1&b=$id2"
Check 'B1 同规则同版本=可比' ($cmp.status -eq 200 -and $cmp.json.comparable -eq $true) "comparable=$($cmp.json.comparable) reason=$($cmp.json.reason)"
Check 'B2 差值字段是 0' ($cmp.json.delta.differences -eq 0) "delta.differences=$($cmp.json.delta.differences) leftRows=$($cmp.json.delta.leftRows)"
Check 'B3 两侧都记录了数据' ($cmp.json.a.dataRecorded -eq $true -and $cmp.json.b.dataRecorded -eq $true) "a=$($cmp.json.a.dataRecorded) b=$($cmp.json.b.dataRecorded)"

Write-Host "`n=== C. 比不了的时候要拒绝 ==="
$bad = Json GET "/api/jobs/compare?a=$id1&b=999999"
Check 'C1 不存在的任务 → 404' ($bad.status -eq 404) "status=$($bad.status) body=$($bad.raw.Substring(0,[Math]::Min(60,$bad.raw.Length)))"

Write-Host "`n=== D. 历史功能没被改坏 ==="
$rules = Json GET '/api/rules'
Check 'D1 规则列表' ($rules.status -eq 200) "count=$($rules.json.Count)"
$diffs = Json GET "/api/diffs?jobId=$id1&limit=5"
Check 'D2 差异明细（分页信封）' ($diffs.status -eq 200 -and $diffs.json.total -ge 4) "total=$($diffs.json.total) items=$($diffs.json.items.Count) limit=$($diffs.json.limit)"
$retry = Json POST "/api/jobs/$id1/retry?onlyFailed=false" @{}
Check 'D3 重跑（含数据快照路径）' ($retry.status -eq 200) "status=$($retry.json.status)"

Write-Host "`n=== E. 会话仍在 ==="
$chat = Json POST '/api/chat' @{ message = '跑一下 BILL_VS_SETTLE' } @{ 'X-Operator' = 'alice' }
Check 'E1 会话跑一次' ($chat.status -eq 200 -and $chat.raw -match '任务 #') "stream bytes=$($chat.raw.Length)"

Write-Host ("`n结果：{0} 通过 / {1} 失败" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
