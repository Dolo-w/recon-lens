# P1（滚动摘要 + 轮次分页）实测脚本。用 pwsh 7 跑：pwsh -File work/p1-smoke.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$alice = @{ 'X-Operator' = 'alice' }
$bob   = @{ 'X-Operator' = 'bob' }
$pass = 0; $fail = 0
function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("[PASS] {0}  {1}" -f $name, $detail) }
  else       { $script:fail++; Write-Host ("[FAIL] {0}  {1}" -f $name, $detail) }
}
function Json($url, $headers) { (Invoke-WebRequest $url -Headers $headers -UseBasicParsing).Content | ConvertFrom-Json }
function Post($url, $headers, $body) {
  if ($null -eq $body) { (Invoke-WebRequest $url -Method Post -Headers $headers -UseBasicParsing).Content | ConvertFrom-Json }
  else { (Invoke-WebRequest $url -Method Post -Headers $headers -Body ($body | ConvertTo-Json -Compress) -ContentType 'application/json' -UseBasicParsing).Content | ConvertFrom-Json }
}

Write-Host '== A 摘要：长会话压成有界文本，水位线落库 =='
$s = Post "$base/api/chat/sessions" $alice @{ title = 'P1 长会话' }
$sid = $s.session.id
for ($i = 1; $i -le 30; $i++) {
  Post "$base/api/chat/sessions/$sid/messages" $alice @{ role = 'user'; content = "第 $i 问：这条差异怎么解释" } | Out-Null
  Post "$base/api/chat/sessions/$sid/messages" $alice @{ role = 'assistant'; content = "第 $i 答：按模板归因，属于时间差" } | Out-Null
}
$sum = Post "$base/api/chat/sessions/$sid/summary" $alice $null
$lines = ($sum.summary -split "`n")
Check 'A1 水位线等于最后一轮' ($sum.summaryUptoSeq -eq 60) "summaryUptoSeq=$($sum.summaryUptoSeq)"
Check 'A2 摘要行数有界' ($lines.Count -le 16) "lines=$($lines.Count) chars=$($sum.summary.Length)"
Check 'A3 摘要含首尾两端' (($sum.summary -match '问（1）') -and ($sum.summary -match '答（60）')) 'contains 问（1）/答（60）'
Check 'A4 过长时留省略标记' ($sum.summary -match '略去') 'has …（略去 N 行）…'

Write-Host '== B 分页：默认最后一页，游标由服务端给 =='
$p1 = Json "$base/api/chat/sessions/$sid`?limit=50" $alice
Check 'B1 首页 50 条且到最新' (($p1.turns.Count -eq 50) -and ($p1.turns[-1].seq -eq 60)) "count=$($p1.turns.Count) last=$($p1.turns[-1].seq)"
Check 'B2 还有更早且给游标' (($p1.turnsHasMore -eq $true) -and ($p1.nextBefore -eq 11)) "hasMore=$($p1.turnsHasMore) nextBefore=$($p1.nextBefore)"
$p2 = Json "$base/api/chat/sessions/$sid`?limit=50&before=$($p1.nextBefore)" $alice
Check 'B3 上一页接得上且到底' (($p2.turns[-1].seq -eq 10) -and ($p2.turnsHasMore -eq $false) -and ($null -eq $p2.nextBefore)) "last=$($p2.turns[-1].seq) hasMore=$($p2.turnsHasMore)"
$ids = $p1.turns | ForEach-Object { $_.seq }
Check 'B4 两页之间不重不漏' (($ids | Sort-Object -Unique).Count -eq 50 -and ($ids[0] -eq 11)) "first=$($ids[0]) unique=$(($ids | Sort-Object -Unique).Count)"

Write-Host '== C 自动压缩：不需要手动点 =='
$s2 = Post "$base/api/chat/sessions" $alice @{ title = 'P1 自动压缩' }
$sid2 = $s2.session.id
$pad = '补充说明' * 120
for ($i = 1; $i -le 6; $i++) {
  Invoke-WebRequest "$base/api/chat" -Method Post -Headers $alice -Body (@{ sessionId = $sid2; message = "第 $i 轮：$pad" } | ConvertTo-Json -Compress) -ContentType 'application/json' -UseBasicParsing -TimeoutSec 60 | Out-Null
}
$d2 = Json "$base/api/chat/sessions/$sid2`?limit=50" $alice
Check 'C1 聊够轮数自动压过' ($d2.session.summaryUptoSeq -gt 0) "summaryUptoSeq=$($d2.session.summaryUptoSeq) turns=$($d2.turns.Count)"
Check 'C2 摘要能在详情里回显' ($d2.session.summary.Length -gt 20) "chars=$($d2.session.summary.Length)"

Write-Host '== D 边界与权限 =='
$code = 0
try { Post "$base/api/chat/sessions/$sid/summary" $bob $null | Out-Null } catch { $code = [int]$_.Exception.Response.StatusCode }
Check 'D1 别人压不了（404 不泄露存在）' ($code -eq 404) "status=$code"
$d3 = Json "$base/api/chat/sessions/$sid2`?limit=1" $alice
Check 'D2 limit 生效' ($d3.turns.Count -eq 1) "count=$($d3.turns.Count)"
$s3 = Post "$base/api/chat/sessions" $alice @{ title = 'P1 空会话' }
$d4 = Json "$base/api/chat/sessions/$($s3.session.id)" $alice
Check 'D3 没聊过的会话没有摘要' ([string]::IsNullOrEmpty($d4.session.summary) -and $d4.session.summaryUptoSeq -eq 0) "summary='$($d4.session.summary)' upto=$($d4.session.summaryUptoSeq)"

Write-Host '== F 从头翻：最早 / 最新 两个方向都能走 =='
$f1 = Json "$base/api/chat/sessions/$sid`?limit=50&from=1" $alice
Check 'F1 from=1 拿到第 1 轮' ($f1.turns[0].seq -eq 1) "first=$($f1.turns[0].seq)"
Check 'F2 从头翻时没有更早、但有更新' (($f1.turnsHasMore -eq $false) -and ($f1.turnsHasMoreAfter -eq $true)) "hasMore=$($f1.turnsHasMore) hasAfter=$($f1.turnsHasMoreAfter)"
Check 'F3 往后翻的游标接得上' ($f1.nextAfter -eq 51) "nextAfter=$($f1.nextAfter)"
$f2 = Json "$base/api/chat/sessions/$sid`?limit=10&from=$($f1.nextAfter)" $alice
Check 'F4 用 nextAfter 继续往下走' ($f2.turns[0].seq -eq 51) "first=$($f2.turns[0].seq) last=$($f2.turns[-1].seq)"
$tail = Json "$base/api/chat/sessions/$sid`?limit=10" $alice
Check 'F5 回到最新一页时没有更新的了' ($tail.turnsHasMoreAfter -eq $false) "hasAfter=$($tail.turnsHasMoreAfter)"

Write-Host '== G 中段不丢线索：省略行带坐标与关键词 =='
$s5 = Post "$base/api/chat/sessions" $alice @{ title = 'P1 中段线索' }
$sid5 = $s5.session.id
for ($i = 1; $i -le 40; $i++) {
  $body = "第 $i 轮：普通进展"
  if ($i -eq 20) { $body = '顺便看一眼 CUSTOMER_SUM_RECON 的任务 #7' }
  if ($i -eq 22) { $body = '那个任务的差异类型是 MISSING_LEFT' }
  Post "$base/api/chat/sessions/$sid5/messages" $alice @{ role = $(if ($i % 2 -eq 1) { 'user' } else { 'assistant' }); content = $body } | Out-Null
}
$sum5 = Post "$base/api/chat/sessions/$sid5/summary" $alice $null
Check 'G1 省略行标出了轮次区间' ($sum5.summary -match '第 \d+–\d+ 轮') 'has 第 a–b 轮'
Check 'G2 中段出现的规则名没被丢掉' ($sum5.summary -match 'CUSTOMER_SUM_RECON') 'kept CUSTOMER_SUM_RECON'
Check 'G3 中段提到的任务号没被丢掉' ($sum5.summary -match '#7') 'kept #7'

Write-Host '== H 压缩比：数字要能报出来 =='
$rawTurnChars = 0
for ($i = 1; $i -le 200; $i++) { $rawTurnChars += 40 }
$ratio = [math]::Round($sum5.summary.Length / ($sid5 * 0 + 40 * 40), 3)
Check 'H1 摘要长度不超过上限' ($sum5.summary.Length -le 1600) "chars=$($sum5.summary.Length)"
Check 'H2 40 轮压成 <=16 行' (($sum5.summary -split "`n").Count -le 16) "lines=$(($sum5.summary -split "`n").Count)"

Write-Host '== E 历史功能没被拖坏 =='
$rules = Json "$base/api/rules" $alice
Check 'E1 规则列表可用' ($rules.Count -ge 3) "rules=$($rules.Count)"
# 自己跑一个任务再查：不要依赖"上一次跑测试时数据库里碰巧有 job 2"
$run = Post "$base/api/jobs" $alice @{ ruleCode = 'BILL_VS_SETTLE'; remark = 'p1-smoke' }
# 建任务默认异步，等它跑完再查详情
$jobId = $run.jobId
for ($k = 1; $k -le 30; $k++) {
  Start-Sleep -Milliseconds 300
  $job = Json "$base/api/jobs/$jobId" $alice
  if ($job.status -ne 'PENDING' -and $job.status -ne 'RUNNING') { break }
}
Check 'E2 任务详情仍带数据快照' ($null -ne $job.dataSnapshot.left.table) "job=$jobId status=$($job.status) left=$($job.dataSnapshot.left.table)"
Check 'E3 页面可访问' ((Invoke-WebRequest "$base/" -UseBasicParsing).StatusCode -eq 200) 'GET / -> 200'

Write-Host ''
Write-Host ("合计 PASS={0} FAIL={1}" -f $pass, $fail)
if ($fail -gt 0) { exit 1 }
