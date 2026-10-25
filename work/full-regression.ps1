# 全量回归：历史功能 + 新增功能（一个脚本，跑在真实启动的 jar 上）
# 用法：pwsh -File work\full-regression.ps1
# 注意：-SkipHttpErrorCheck 需要 PowerShell 7（pwsh），Windows PowerShell 5.1 没有这个参数。

param(
    [string]$Base = 'http://127.0.0.1:18080'
)

$script:pass = 0
$script:fail = 0

function Show([string]$no, [string]$name, [bool]$ok, [string]$detail) {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    if ($ok) { $script:pass++ } else { $script:fail++ }
    Write-Host ("[{0}] {1} {2}" -f $mark, $no, $name)
    if ($detail) {
        $d = ($detail -replace '\s+', ' ')
        Write-Host ("         {0}" -f $d.Substring(0, [Math]::Min(200, $d.Length)))
    }
}

function Req([string]$method, [string]$path, $body, [hashtable]$headers) {
    $h = @{}
    if ($headers) { foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] } }
    $args = @{
        Uri = "$Base$path"; Method = $method; Headers = $h
        SkipHttpErrorCheck = $true; UseBasicParsing = $true; TimeoutSec = 180
    }
    if ($body -ne $null) {
        $args['Body'] = ($body | ConvertTo-Json -Depth 8 -Compress)
        $args['ContentType'] = 'application/json'
    }
    $r = Invoke-WebRequest @args
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
}

$alice = @{ 'X-Operator' = 'alice' }

Write-Host "`n===== A. 历史功能 ====="

# A1 规则库
$r = Req 'GET' '/api/rules' $null $null
$rules = $r.Body | ConvertFrom-Json
$codes = @($rules.items | ForEach-Object { $_.code })
Show 'A1' 'GET /api/rules 列出规则库' ($r.Status -eq 200 -and $codes.Count -ge 3) "status=$($r.Status) codes=$($codes -join ',')"

# A2 发起对账（同步）
$r = Req 'POST' '/api/jobs' @{ ruleCode = 'BILL_VS_SETTLE'; batchId = 'REG-SYNC'; sync = $true } $null
$job = $r.Body | ConvertFrom-Json
Show 'A2' 'POST /api/jobs（sync）跑一次对账' ($r.Status -eq 200 -and $job.diffRows -gt 0) "status=$($r.Status) jobId=$($job.jobId) 差异=$($job.diffRows)"
$jobId = $job.jobId

# A3 任务详情
$r = Req 'GET' "/api/jobs/$jobId" $null $null
$detail = $r.Body | ConvertFrom-Json
Show 'A3' 'GET /api/jobs/{id} 任务详情 + 分片' ($r.Status -eq 200 -and $detail.shards.Count -gt 0) "status=$($r.Status) 分片=$($detail.shards.Count)"

# A4 差异列表
$r = Req 'GET' "/api/diffs?jobId=$jobId&limit=50" $null $null
$diffs = $r.Body | ConvertFrom-Json
Show 'A4' 'GET /api/diffs?jobId= 差异明细' ($r.Status -eq 200 -and $diffs.items.Count -gt 0) "status=$($r.Status) 条数=$($diffs.items.Count)"
$diffId = @($diffs.items)[0].id

# A5 差异汇总
$r = Req 'GET' "/api/diffs/summary?jobId=$jobId" $null $null
Show 'A5' 'GET /api/diffs/summary 按类型/严重级汇总' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body)"

# A6 单条归因
$r = Req 'POST' "/api/diffs/$diffId/explain" $null $null
Show 'A6' 'POST /api/diffs/{id}/explain 单条归因' ($r.Status -eq 200 -and $r.Body.Length -gt 20) "status=$($r.Status) len=$($r.Body.Length)"

# A7 人工归因写回知识库
$r = Req 'POST' "/api/diffs/$diffId/attribution" @{ cause = '重复入账'; action = '按幂等键去重'; operator = 'alice' } $null
Show 'A7' 'POST /api/diffs/{id}/attribution 人工归因' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body)"

# A8 知识库
$r = Req 'GET' '/api/diffs/knowledge' $null $null
Show 'A8' 'GET /api/diffs/knowledge 归因知识库' ($r.Status -eq 200 -and $r.Body -match '重复入账') "status=$($r.Status)"

# A9 重跑失败分片（无失败分片时应给出明确结果，不是 500）
$r = Req 'POST' "/api/jobs/$jobId/retry" @{ onlyFailed = $true } $null
Show 'A9' 'POST /api/jobs/{id}/retry 重跑失败分片' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body)"

# A10 预处理模型
$r = Req 'POST' '/api/models/CUSTOMER_SUM_RECON' $null $null
Show 'A10' 'POST /api/models/{ruleCode} 建预处理模型' ($r.Status -eq 200) "status=$($r.Status) body=$($r.Body.Substring(0,[Math]::Min(120,$r.Body.Length)))"

# A11 审批 fail-closed（没配 recon.review.api-key 时必须 403）
$r = Req 'POST' '/api/rule-reviews/BILL_VS_SETTLE/1/confirmation' @{ decision = 'APPROVE' } $alice
Show 'A11' '领确认码未配 api-key → 403（fail-closed）' ($r.Status -eq 403) "status=$($r.Status) body=$($r.Body)"

Write-Host "`n===== B. 新增功能（会话） ====="

# B1 表单目录
$r = Req 'GET' '/api/chat/forms' $null $null
$forms = $r.Body | ConvertFrom-Json
Show 'B1' 'GET /api/chat/forms 14 个动作' ($r.Status -eq 200 -and $forms.items.Count -eq 14) "status=$($r.Status) count=$($forms.items.Count)"

# B2 会话 CRUD
$r = Req 'POST' '/api/chat/sessions' @{ title = '回归会话' } $alice
$sess = $r.Body | ConvertFrom-Json
$sid = $sess.session.id
Show 'B2' 'POST /api/chat/sessions 建会话' ($r.Status -eq 201 -and $sid -gt 0) "status=$($r.Status) sessionId=$sid"

# B3 SSE 一句话
$r = Req 'POST' '/api/chat' @{ sessionId = $sid; message = '有哪些规则' } $alice
$hasIntent = $r.Body -match 'event:intent'
$hasDelta = $r.Body -match 'event:delta'
$hasDone = $r.Body -match 'event:done'
Show 'B3' 'POST /api/chat 流式返回 intent/delta/done' ($r.Status -eq 200 -and $hasIntent -and $hasDelta -and $hasDone) "intent=$hasIntent delta=$hasDelta done=$hasDone"

# B4 槽位记忆
$r = Req 'POST' '/api/chat' @{ sessionId = $sid; message = '跑一下 BILL_VS_SETTLE' } $alice
$slotJob = if ($r.Body -match '"jobId":"?(\d+)"?') { $matches[1] } else { '' }
$r2 = Req 'POST' '/api/chat' @{ sessionId = $sid; message = '看差异' } $alice
Show 'B4' '槽位记忆：第二句不填任务号也能打到' ($r2.Status -eq 200 -and $r2.Body -match '差异') "jobId=$slotJob"

# B5 表单兜底：起草 → 试算状态
$yaml = @'
code: REG_FORM_RECON
name: 回归用规则
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
$r = Req 'POST' '/api/chat/actions' @{ action = 'DEFINE_RULE'; values = @{ ruleBody = $yaml } } $alice
$def = $r.Body | ConvertFrom-Json
$sid2 = $def.sessionId
Show 'B5' '〔表单〕起草规则 → 草稿 + 自动试算 + 记住规则名' ($r.Status -eq 200 -and $def.slots.ruleCode -eq 'REG_FORM_RECON' -and $def.slotsSaved) "session=$sid2 ruleCode=$($def.slots.ruleCode)"

# B6 试算结论 + 可批性
$deadline = (Get-Date).AddSeconds(40)
$trialText = ''
while ((Get-Date) -lt $deadline) {
    $r = Req 'POST' '/api/chat/actions' @{ sessionId = $sid2; action = 'TRIAL_STATUS'; values = @{} } $alice
    $trialText = ($r.Body | ConvertFrom-Json).text
    if ($trialText -match 'PASS|FAIL|INCONCLUSIVE') { break }
    Start-Sleep -Milliseconds 800
}
Show 'B6' '试算结论 + 可批性都出现在会话里' ($trialText -match '可批性：') "text=$trialText"

# B7 越权 404
$r = Req 'GET' "/api/chat/sessions/$sid" $null @{ 'X-Operator' = 'bob' }
Show 'B7' 'bob 读 alice 的会话 → 404（不泄露存在性）' ($r.Status -eq 404) "status=$($r.Status)"

# B8 交接
$r = Req 'POST' "/api/chat/sessions/$sid/handover" @{ toOperator = 'lisi' } $alice
Show 'B8' 'POST /sessions/{id}/handover 交接' ($r.Status -eq 200) "status=$($r.Status)"
$r = Req 'GET' "/api/chat/sessions/$sid" $null @{ 'X-Operator' = 'lisi' }
Show 'B9' '交接后新 owner 能读' ($r.Status -eq 200) "status=$($r.Status)"
$r = Req 'GET' "/api/chat/sessions/$sid" $null @{ 'X-Operator' = 'wangwu' }
Show 'B10' '交接后第三方仍 404' ($r.Status -eq 404) "status=$($r.Status)"

Write-Host "`n===== C. 浏览器入口 ====="
$r = Req 'GET' '/' $null $null
$ok = $r.Status -eq 200 -and $r.Body -match 'recon-lens' -and $r.Body -match 'api/chat/actions'
Show 'C1' 'GET / 返回内建会话页面' $ok "status=$($r.Status) len=$($r.Body.Length)"

Write-Host "`n===== 汇总：$script:pass 通过 / $script:fail 失败 ====="
if ($script:fail -gt 0) { exit 1 }
