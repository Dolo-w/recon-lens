# 迭代 2 实测：表单兜底（无 key、无宿主，只用 HTTP）
# 场景全部打在真实启动的 jar 上，不是单测。
param([string]$base = '')
if (-not $base) { $base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' } }

$script:pass = 0
$script:fail = 0

function Show([string]$no, [string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    if ($ok) { $script:pass++ } else { $script:fail++ }
    Write-Host ("[{0}] {1} {2}" -f $mark, $no, $name)
    if ($detail) { Write-Host ("         {0}" -f ($detail -replace '\s+', ' ').Substring(0, [Math]::Min(220, ($detail -replace '\s+', ' ').Length))) }
}

function PostJson([string]$path, $body, [string]$operator) {
    $json = $body | ConvertTo-Json -Depth 8 -Compress
    $headers = @{}
    if ($operator) { $headers['X-Operator'] = $operator }
    $r = Invoke-WebRequest -Uri "$Base$path" -Method Post -Body $json -ContentType 'application/json' `
        -Headers $headers -SkipHttpErrorCheck -UseBasicParsing
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
}

function GetJson([string]$path, [string]$operator) {
    $headers = @{}
    if ($operator) { $headers['X-Operator'] = $operator }
    $r = Invoke-WebRequest -Uri "$Base$path" -Headers $headers -SkipHttpErrorCheck -UseBasicParsing
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
}

$yaml = @'
code: SMOKE_FORM_RECON
name: 表单冒烟对账
description: 只用表单入口创建，验证无 key 也能走完起草→试算→看差异
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
  count: 2
  parallelism: 2
  whereLeft: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
  whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
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

Write-Host "== 场景 1：拉表单（不带 X-Operator） =="
$r = GetJson '/api/chat/forms'
$forms = $r.Body | ConvertFrom-Json
Show '1' 'GET /api/chat/forms → 200，15 个动作' ($r.Status -eq 200 -and $forms.items.Count -eq 15) "status=$($r.Status) items=$($forms.items.Count) 动作=$($forms.items.action -join ',')"

Write-Host "`n== 场景 2：表单起草一条规则（无 key，无模型） =="
$r = PostJson '/api/chat/actions' @{ action = 'DEFINE_RULE'; values = @{ ruleBody = $yaml } } 'alice'
$def = $r.Body | ConvertFrom-Json
$sessionId = $def.sessionId
Show '2' 'POST /actions DEFINE_RULE → 草稿落库并自动试算' ($r.Status -eq 200 -and $def.slots.ruleCode -eq 'SMOKE_FORM_RECON' -and $def.slotsSaved) "sessionId=$sessionId text=$($def.text) tools=$($def.tools -join ',')"

Write-Host "`n== 场景 3：表单看试算状态（规则名从槽位拿） =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'TRIAL_STATUS'; values = @{} } 'alice'
$trial = $r.Body | ConvertFrom-Json
$hasVerdict = $trial.text -match 'PASS|FAIL|不确定|没跑|试算'
Show '3' 'POST /actions TRIAL_STATUS（不填 ruleCode）→ 报试算结论' ($r.Status -eq 200 -and $hasVerdict) "text=$($trial.text)"

Write-Host "`n== 场景 4：草稿还跑不了，但要给出看得懂的理由 =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'RUN'; values = @{ ruleCode = 'SMOKE_FORM_RECON' } } 'alice'
$draftRun = $r.Body
$clearReason = $draftRun -match '草稿|未生效|不存在|REVIEWING|DRAFT'
Show '4' 'RUN 一条草稿规则 → 明确说它还跑不了' ($clearReason) "status=$($r.Status) body=$draftRun"

Write-Host "`n== 场景 4b：等影子试算跑完，会话里要看得到结论与可批性 =="
$deadline = (Get-Date).AddSeconds(40)
$trialText = ''
while ((Get-Date) -lt $deadline) {
    $r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'TRIAL_STATUS'; values = @{} } 'alice'
    $trialText = ($r.Body | ConvertFrom-Json).text
    if ($trialText -match 'PASS|FAIL|INCONCLUSIVE') { break }
    Start-Sleep -Milliseconds 800
}
$hasApproval = $trialText -match '可批性：\s*(NO_SIGNOFF_REQUIRED|NEED_SIGN|BLOCK_SIGNABLE|BLOCK_HARD|NOT_APPROVABLE)'
Show '4b' '试算跑完后，TRIAL_STATUS 同时报结论与可批性' $hasApproval "text=$trialText"

Write-Host "`n== 场景 5：跑一条已生效的规则，出任务号与差异分布 =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'RUN'; values = @{ ruleCode = 'BILL_VS_SETTLE' } } 'alice'
$run = $r.Body | ConvertFrom-Json
$jobId = $run.slots.jobId
Show '5' 'RUN BILL_VS_SETTLE → 任务号 + 差异分类' ($r.Status -eq 200 -and $jobId -gt 0 -and $run.text -match '差异') "jobId=$jobId text=$($run.text)"

Write-Host "`n== 场景 6：任务总览不填任务号（靠槽位） =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'JOB_SUMMARY'; values = @{} } 'alice'
$sum = $r.Body | ConvertFrom-Json
Show '6' 'JOB_SUMMARY（不填 jobId）→ 打到同一个任务' ($r.Status -eq 200 -and $sum.text -notmatch '哪个任务' -and $sum.text -match '扫描') "text=$($sum.text)"

Write-Host "`n== 场景 7：按类型看差异 =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'LIST_DIFFS'; values = @{ diffType = 'AMOUNT_MISMATCH' } } 'alice'
$diffs = $r.Body | ConvertFrom-Json
Show '7' 'LIST_DIFFS（任务号与规则名都靠槽位）→ 逐条列出' ($r.Status -eq 200 -and $diffs.text -match '差异共') "text=$($diffs.text)"

Write-Host "`n== 场景 8：整单归因 =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'EXPLAIN_JOB'; values = @{} } 'alice'
$explain = $r.Body | ConvertFrom-Json
Show '8' 'EXPLAIN_JOB（不填任务号）→ 每条差异给原因' ($r.Status -eq 200 -and $explain.text.Length -gt 60) "len=$($explain.text.Length) text=$($explain.text)"

Write-Host "`n== 场景 9：别人的会话，表单入口同样 404 =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'LIST_RULES'; values = @{} } 'bob'
Show '9' 'bob 用 alice 的 sessionId → 404（不泄露存在性）' ($r.Status -eq 404) "status=$($r.Status) body=$($r.Body)"

Write-Host "`n== 场景 10：参数校验 =="
$r1 = PostJson '/api/chat/actions' @{ action = 'DEFINE_RULE'; values = @{} } 'alice'
$r2 = PostJson '/api/chat/actions' @{ action = 'DROP_TABLE'; values = @{} } 'alice'
$r3 = PostJson '/api/chat/actions' @{ action = 'JOB_SUMMARY'; values = @{ jobId = '1O0' } } 'alice'
$r4 = PostJson '/api/chat/actions' @{ action = 'LIST_RULES'; values = @{} } $null
Show '10a' '起草缺正文 → 400' ($r1.Status -eq 400) "body=$($r1.Body)"
Show '10b' '未知动作 → 400 且列出可选值' ($r2.Status -eq 400 -and $r2.Body -match 'RUN') "body=$($r2.Body)"
Show '10c' 'jobId 打成字母 O → 400 并回显原值' ($r3.Status -eq 400 -and $r3.Body -match '1O0') "body=$($r3.Body)"
Show '10d' '缺 X-Operator → 400' ($r4.Status -eq 400) "status=$($r4.Status)"

Write-Host "`n== 场景 11：连续两次提交，槽位都写得进去（CAS 不误伤自己） =="
$r = PostJson '/api/chat/actions' @{ sessionId = $sessionId; action = 'LIST_RULES'; values = @{} } 'alice'
$second = $r.Body | ConvertFrom-Json
Show '11' '连续提交 slotsSaved 都为 true' ($r.Status -eq 200 -and $second.slotsSaved -and $second.slots.jobId -gt 0) "slotsSaved=$($second.slotsSaved) jobId=$($second.slots.jobId)"

Write-Host "`n== 场景 12：会话历史里是给人看的话，不是 JSON =="
$r = GetJson "/api/chat/sessions/$sessionId" 'alice'
$detail = $r.Body | ConvertFrom-Json
$firstTurn = $detail.turns[0].content
$isHuman = $firstTurn -match '〔表单〕' -and $firstTurn -notmatch '\{.*ruleBody'
Show '12' 'GET /sessions/{id} → 首轮是「〔表单〕DEFINE_RULE …」' ($r.Status -eq 200 -and $isHuman) "turns=$($detail.turns.Count) first=$($firstTurn.Substring(0,[Math]::Min(80,$firstTurn.Length)))"

Write-Host "`n== 汇总：$script:pass 通过 / $script:fail 失败 =="
if ($script:fail -gt 0) { exit 1 }
