# ADR-0001 迭代 1 实测：N2 / N3 / N6 / N7
# 用法：pwsh 7 下 & ./work/adr1-iter1-smoke.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$h = @{ 'X-Operator' = 'alice' }
$pass = 0; $fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; "OK   $name  $detail" }
  else { $script:fail++; "FAIL $name  $detail" }
}

# ---------- A. N7：差异分页信封（结构化翻页出口）----------
$job = (Invoke-WebRequest "$base/api/jobs" -Method Post -Headers $h `
  -ContentType 'application/json' -Body '{"ruleCode":"BILL_VS_SETTLE","sync":true,"batchId":"n7-smoke"}' `
  -UseBasicParsing).Content | ConvertFrom-Json
$jobId = $job.jobId

$p1 = (Invoke-WebRequest "$base/api/diffs?jobId=$jobId&limit=2" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
Check 'A1 第一页截断' ($p1.truncated -eq $true) "total=$($p1.total) items=$($p1.items.Count) truncated=$($p1.truncated)"
Check 'A2 给出 nextOffset' ($p1.nextOffset -eq 2) "nextOffset=$($p1.nextOffset)"

$p2 = (Invoke-WebRequest "$base/api/diffs?jobId=$jobId&limit=2&offset=2" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
Check 'A3 第二页不再截断' ($p2.truncated -eq $false) "offset=$($p2.offset) truncated=$($p2.truncated)"
$hasNext = $p2.PSObject.Properties.Name -contains 'nextOffset'
Check 'A4 到底没有 nextOffset' (-not $hasNext) "字段存在=$hasNext"

# ---------- B. N3：被否决的方案进摘要 ----------
$s = (Invoke-WebRequest "$base/api/chat/sessions" -Method Post -Headers $h -ContentType 'application/json' `
  -Body (@{ title = '迭代1 N3' } | ConvertTo-Json -Compress) -UseBasicParsing).Content | ConvertFrom-Json
$sid = $s.session.id

$yaml = @'
code: ITER1_REJECT_RECON
name: 迭代1 拒绝样本
description: 只用来触发"草稿不能直接跑"
left:
  label: 账单
  table: bill_left
  keyType: STRING
  dimension: [customer_id, order_no]
right:
  label: 结算
  table: bill_right
  keyType: STRING
  dimension: [customer_id, order_no]
shard:
  count: 1
  parallelism: 1
compare:
  - field: amount
    type: DECIMAL
    tolerancePercent: 0.0001
    diffType: AMOUNT_MISMATCH
    severity: HIGH
missing:
  leftMissingDiffType: MISSING_LEFT
  rightMissingDiffType: MISSING_RIGHT
  severity: HIGH
duplicate:
  diffType: DUPLICATE_KEY
  severity: MEDIUM
'@
$body = @{ sessionId = $sid; message = "起草规则`n``````yaml`n$yaml`n``````" } | ConvertTo-Json -Compress
$r1 = (Invoke-WebRequest "$base/api/chat" -Method Post -Headers $h -ContentType 'application/json' -Body $body -UseBasicParsing).Content
Check 'B1 起草成功' ($r1 -match 'ITER1_REJECT_RECON') 'draft ok'

$r2 = (Invoke-WebRequest "$base/api/chat" -Method Post -Headers $h -ContentType 'application/json' `
  -Body (@{ sessionId = $sid; message = '跑一下 ITER1_REJECT_RECON' } | ConvertTo-Json -Compress) -UseBasicParsing).Content
Check 'B2 草稿被拒' ($r2 -match '还没生效|没做成') 'rejected'

Invoke-WebRequest "$base/api/chat" -Method Post -Headers $h -ContentType 'application/json' `
  -Body (@{ sessionId = $sid; message = '跑一下 BILL_VS_SETTLE' } | ConvertTo-Json -Compress) -UseBasicParsing | Out-Null

$sum = (Invoke-WebRequest "$base/api/chat/sessions/$sid/summary" -Method Post -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
Check 'B3 摘要里有被否决段' ($sum.summary -match '〔被否决〕') "summaryUptoSeq=$($sum.summaryUptoSeq)"
Check 'B4 否决段带轮次坐标' ($sum.summary -match '→ 答（\d+）') 'has coordinate'

# ---------- C. N6：压缩不动原始轮次 ----------
$before = ((Invoke-WebRequest "$base/api/chat/sessions/${sid}?limit=100" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json)
$beforeCount = $before.turns.Count
Invoke-WebRequest "$base/api/chat/sessions/$sid/summary" -Method Post -Headers $h -UseBasicParsing | Out-Null
$after = ((Invoke-WebRequest "$base/api/chat/sessions/${sid}?limit=100" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json)
Check 'C1 轮次条数不变' ($after.turns.Count -eq $beforeCount) "before=$beforeCount after=$($after.turns.Count)"
Check 'C2 首条内容不变' ($after.turns[0].content -eq $before.turns[0].content) 'first turn intact'

# ---------- D. 会话仍不能绕过审批（N1 回归）----------
$r3 = (Invoke-WebRequest "$base/api/chat" -Method Post -Headers $h -ContentType 'application/json' `
  -Body (@{ sessionId = $sid; message = '批准 ITER1_REJECT_RECON v1' } | ConvertTo-Json -Compress) -UseBasicParsing).Content
Check 'D1 说"批准"不会真批' ($r3 -match '确认码|签字|不能|流程') 'still guarded'

""
"==== $pass passed, $fail failed ===="
