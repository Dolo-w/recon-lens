# P2 长期记忆实测（pwsh 7 才能用 -SkipHttpErrorCheck）
param([string]$Base = 'http://127.0.0.1:18080')

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

function Req($method, $path, $body, $headers) {
    $args = @{ Uri = "$Base$path"; Method = $method; SkipHttpErrorCheck = $true; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $body) {
        $args['Body'] = ($body | ConvertTo-Json -Compress -Depth 6)
        $args['ContentType'] = 'application/json'
    }
    if ($headers) { $args['Headers'] = $headers }
    $r = Invoke-WebRequest @args
    $parsed = $null
    try { $parsed = $r.Content | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content; Json = $parsed }
}

function Show($id, $name, $ok, $detail) {
    if ($ok) { $script:pass++ } else { $script:fail++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1,-4} {2,-44} {3}" -f $mark, $id, $name, $detail)
}

$alice = @{ 'X-Operator' = 'alice' }

$manual = "结算口径说明`n" +
"一、账期口径。账单侧以入账时间为准，结算侧以结算时间为准，两边跨月时会自然产生时间差，不算差异。`n" +
"二、金额口径。手续费与汇率进位在结算侧才发生，因此金额不一致在 0.01 元以内属于正常。`n" +
"三、重复键口径。同一业务键在同一批次出现两次以上，绝大多数是重复入账或消息重放，需要按幂等键去重后重跑。`n" +
"四、单边缺失口径。只有结算侧有记录时，通常是对方延迟入账；只有账单侧有记录时，通常是我方补单未同步。"

Write-Host '--- A 口径文档 ---'
$r = Req 'POST' '/api/docs' @{ title = '结算口径说明'; source = 'finance'; text = $manual } $null
$docId = $r.Json.docId
Show 'A1' 'POST /api/docs 入库切片' ($r.Status -eq 201 -and $r.Json.chunks -ge 2) "status=$($r.Status) chunks=$($r.Json.chunks) docId=$docId"

$r2 = Req 'POST' '/api/docs' @{ title = '结算口径说明（重传）'; source = 'finance'; text = $manual } $null
Show 'A2' '同一篇重传幂等' ($r2.Status -eq 200 -and $r2.Json.alreadyIndexed -eq $true) "status=$($r2.Status) alreadyIndexed=$($r2.Json.alreadyIndexed)"

$r = Req 'GET' '/api/docs/search?q=%E6%89%8B%E7%BB%AD%E8%B4%B9%20%E8%BF%9B%E4%BD%8D' $null $null
$matched = ($r.Json.items[0].matched -join ',')
Show 'A3' '中文原话检索（手续费 进位）' ($r.Status -eq 200 -and $r.Json.total -gt 0 -and $matched) "total=$($r.Json.total) 命中词=$matched 检索器=$($r.Json.retriever)"

$r = Req 'GET' '/api/docs/search?q=%E5%AE%B9%E5%B7%AE&source=warehouse' $null $null
Show 'A4' '元数据过滤在前' ($r.Status -eq 200 -and $r.Json.total -eq 0) "total=$($r.Json.total)"

$r = Req 'GET' '/api/docs' $null $null
Show 'A5' 'GET /api/docs 列出切片' ($r.Status -eq 200 -and $r.Json.total -ge 2) "total=$($r.Json.total)"

Write-Host '--- B 案例记忆 ---'
$r = Req 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'; causeKey = 'C002|ORD20260003'; resolution = '手续费进位' } $null
Show 'B1' '人确认案例缺 X-Operator → 400' ($r.Status -eq 400) "status=$($r.Status) err=$($r.Json.error)"

$r = Req 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'; causeKey = 'C002|ORD20260003'; resolution = '和财务确认是手续费进位，不改数据' } $alice
Show 'B2' '人确认案例（0.9 / HUMAN）' ($r.Status -eq 201 -and $r.Json.confidence -eq 0.9 -and $r.Json.source -eq 'HUMAN') "status=$($r.Status) conf=$($r.Json.confidence)"

$r = Req 'POST' '/api/cases' @{ ruleCode = 'BILL_VS_SETTLE'; diffType = 'AMOUNT_MISMATCH'; causeKey = 'C002|ORD20260003'; resolution = '疑似手续费进位，建议人工确认'; source = 'LLM' } $null
Show 'B3' '模型自述案例（0.5 / 无操作人）' ($r.Status -eq 201 -and $r.Json.confidence -eq 0.5) "status=$($r.Status) conf=$($r.Json.confidence)"

$r = Req 'GET' '/api/cases/recall?ruleCode=BILL_VS_SETTLE&diffType=AMOUNT_MISMATCH' $null $null
$first = $r.Json.items[0]
Show 'B4' '检索排序：人确认排在模型前面' ($r.Status -eq 200 -and $r.Json.total -ge 2 -and $first.source -eq 'HUMAN') "total=$($r.Json.total) 首条=$($first.source) score=$($first.score)"

$r = Req 'GET' '/api/cases?ruleCode=BILL_VS_SETTLE' $null $null
Show 'B5' 'GET /api/cases 列案例' ($r.Status -eq 200 -and $r.Json.total -ge 2) "total=$($r.Json.total)"

Write-Host '--- C 归因里带上记忆 ---'
$r = Req 'POST' '/api/jobs' @{ ruleCode = 'BILL_VS_SETTLE'; batchId = 'RECALL-SMOKE'; sync = $true } $null
$jobId = $r.Json.jobId
$r = Req 'GET' "/api/diffs?jobId=$jobId&limit=50" $null $null
$target = $r.Json.items | Where-Object { $_.diffType -eq 'AMOUNT_MISMATCH' } | Select-Object -First 1
if (-not $target) { $target = $r.Json.items[0] }
Show 'C1' '跑一次对账拿到差异' ($r.Status -eq 200 -and $null -ne $target) "jobId=$jobId 差异=$($r.Json.items.Count) 目标=#$($target.id) $($target.diffType)"

$r = Req 'POST' "/api/diffs/$($target.id)/explain" $null $null
$hasRecall = $r.Body -like '*同类差异的历史处理*'
Show 'C2' '单条归因追加同类案例' ($r.Status -eq 200 -and $r.Json.recalled -eq $true -and $hasRecall) "recalled=$($r.Json.recalled) len=$($r.Body.Length)"

$hasDoc = $r.Body -like '*口径依据*'
Show 'C3' '单条归因追加口径依据' ($r.Status -eq 200 -and $hasDoc) "len=$($r.Body.Length)"

Write-Host '--- D 会话里也能用 ---'
$s = Req 'POST' '/api/chat/sessions' @{ title = 'P2 实测' } $alice
$sid = $s.Json.session.id
$r = Req 'POST' '/api/chat' @{ sessionId = $sid; message = "第 $($target.id) 条差异为什么" } $alice
$hasChat = ($r.Body -like '*同类差异的历史处理*') -or ($r.Body -like '*口径依据*')
Show 'D1' '会话归因带上记忆段落' ($r.Status -eq 200 -and $hasChat) "status=$($r.Status) len=$($r.Body.Length)"

$r = Req 'GET' "/api/chat/sessions/$sid" $null $alice
$tools = ($r.Json.turns | Where-Object { $_.toolCalls } | ForEach-Object { $_.toolCalls }) -join '|'
Show 'D2' '轮次里记下 recall 工具' ($tools -like '*recall*') "tools=$tools"

Write-Host '--- E 边界 ---'
$r = Req 'GET' '/api/cases/recall?ruleCode=NO_SUCH_RULE&diffType=AMOUNT_MISMATCH' $null $null
Show 'E1' '没有案例的规则返回空列表' ($r.Status -eq 200 -and $r.Json.total -eq 0) "total=$($r.Json.total)"

$r = Req 'POST' '/api/docs' @{ title = '空文档'; text = '   ' } $null
Show 'E2' '空正文 → 400' ($r.Status -eq 400) "status=$($r.Status)"

$r = Req 'DELETE' "/api/docs/$docId" $null $null
Show 'E3' 'DELETE /api/docs/{id} 删除切片' ($r.Status -eq 200 -and $r.Json.removed -ge 1) "removed=$($r.Json.removed)"

$total = $script:pass + $script:fail
Write-Host ''
Write-Host ("SUMMARY {0}/{1} passed" -f $script:pass, $total)
if ($script:fail -gt 0) { exit 1 }
