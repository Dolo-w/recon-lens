# P2 多轮交互实测：上下文续接 + 起草三段 + 追问续接
# 前置：jar 已起在 18080（建议带 DEEPSEEK_API_KEY，模型只影响"听得多准"，不影响这些断言）
# 用法（必须是 pwsh 7）：& ./work/p2-multiturn.ps1

$ErrorActionPreference = 'Stop'
$base = if ($env:RECON_BASE) { $env:RECON_BASE } else { 'http://127.0.0.1:18090' }
$h = @{ 'X-Operator' = 'alice' }
$pass = 0; $fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; "  [OK]   $name  $detail" }
    else { $script:fail++; "  [FAIL] $name  $detail" }
}

function NewSession($title) {
    $r = Invoke-WebRequest "$base/api/chat/sessions" -Method Post -Body (@{ title = $title } | ConvertTo-Json -Compress) `
        -ContentType 'application/json' -Headers $h -UseBasicParsing -SkipHttpErrorCheck
    ($r.Content | ConvertFrom-Json).session.id
}

function Ask($sid, $msg) {
    $r = Invoke-WebRequest "$base/api/chat" -Method Post `
        -Body (@{ sessionId = [long]$sid; message = $msg } | ConvertTo-Json -Compress) `
        -ContentType 'application/json' -Headers $h -UseBasicParsing -TimeoutSec 120 -SkipHttpErrorCheck
    $plan = $null; $intent = ''; $text = ''; $tools = @()
    foreach ($line in ($r.Content -split "`n")) {
        if ($line -notlike 'data:*') { continue }
        $p = $line.Substring(5).Trim()
        try { $o = $p | ConvertFrom-Json } catch { continue }
        if ($o.source) { $plan = $o }
        if ($o.intent -and -not $o.source) { $intent = $o.intent }
        if ($o.text) { $text += $o.text }
        if ($o.name) { $tools += $o.name }
    }
    [pscustomobject]@{ plan = $plan; intent = $intent; text = $text; tools = $tools }
}

"== P2 多轮交互实测 =="

# ---------------------------------------------------------------- A 上下文续接
"== A 组：省略句续接 =="
$s = NewSession '多轮-上下文'
$a1 = Ask $s '数据呢，有啥'
Check 'A1 问有哪些数据' ($a1.plan.intent -eq 'LIST_DATA') "intent=$($a1.plan.intent)"

$a2 = Ask $s '字段'
Check 'A2 只说「字段」也能接住意图' ($a2.plan.intent -eq 'DESCRIBE_TABLE') "intent=$($a2.plan.intent)"
Check 'A3 没有表名时代之以「看哪张表」而不是重播整份清单' ($a2.text -like '*看哪张表*') $a2.text.Substring(0, [Math]::Min(40, $a2.text.Length))

$a4 = Ask $s 'bill_left'
Check 'A4 回一个裸表名就是答案（续接上一轮）' ($a4.tools -contains 'recon_describe_table') "tools=$($a4.tools -join ',')"
Check 'A5 续接要说明依据' ($a4.plan.reason -like '*续接*') $a4.plan.reason

# ---------------------------------------------------------------- B 起草三段
"== B 组：口述起草 =="
$s2 = NewSession '多轮-起草'
$b1 = Ask $s2 '起草个规则：订单号和状态对账'
Check 'B1 动词命中起草' ($b1.plan.intent -eq 'DEFINE_RULE') "intent=$($b1.plan.intent)"
Check 'B2 缺参数时问一个，不是甩模板' ($b1.text -like '*左侧用哪张表*') $b1.text.Substring(0, [Math]::Min(40, $b1.text.Length))

$b3 = Ask $s2 '左 bill_left，右 bill_right，按 order_no 连，比 amount'
Check 'B3 收齐后先请确认，不直接落库' ($b3.text -like '*确认*' -and $b3.text.Contains('code:')) $b3.text.Substring(0, [Math]::Min(40, $b3.text.Length))
Check 'B3b 确认前没有调用 define_rule' (-not ($b3.tools -contains 'recon_define_rule')) "tools=$($b3.tools -join ',')"

$b4 = Ask $s2 '确认'
Check 'B4 确认之后才落成草稿' ($b4.tools -contains 'recon_define_rule') "tools=$($b4.tools -join ',')"

# ---------------------------------------------------------------- C 追问续接
"== C 组：没点名规则时先确认 =="
$s3 = NewSession '多轮-追问'
$c1 = Ask $s3 '帮我跑一下对账'
# 两种正确姿势都行：先确认（模型补出了规则），或先问"要跑哪条"（模型没补）。
# 唯一不能接受的是"没点名就直接跑"。
Check 'C1 没点名规则时不会直接跑' `
    ((-not ($c1.tools -contains 'recon_run')) -and (($c1.text -like '*确认*') -or ($c1.text -like '*要跑哪条规则*'))) "intent=$($c1.plan.intent) tools=$($c1.tools -join ',')"

$c2 = Ask $s3 'BILL_VS_SETTLE'
Check 'C2 用户点名另一条编码也能直接跑起来' ($c2.tools -contains 'recon_run') "tools=$($c2.tools -join ',')"
Check 'C3 这一句被理解成跑对账' ($c2.plan.intent -eq 'RUN') "intent=$($c2.plan.intent) reason=$($c2.plan.reason)"

# ---------------------------------------------------------------- D 业务词指代
"== D 组：根据订单和状态对账 + 就这两个吧 =="
$s4 = NewSession '多轮-业务词'
$d1 = Ask $s4 '给我起草个规则，根据订单和状态对账'
Check 'D1 认出起草意图' ($d1.plan.intent -eq 'DEFINE_RULE') "intent=$($d1.plan.intent)"
Check 'D2 业务词要记住（订单和状态）' ($d1.text -like '*订单和状态*') $d1.text.Substring(0, [Math]::Min(30, $d1.text.Length))

$d3 = Ask $s4 '就这两个吧'
Check 'D3 「就这两个吧」指向候选表，不是掉进帮助' ($d3.plan.intent -eq 'DEFINE_RULE' -and $d3.text -like '*bill_left*') "intent=$($d3.plan.intent)"
# 「订单和状态」说的是对账维度（复合键），不是"键 + 比对字段"：比对字段还要问
Check 'D4 两个业务词都进维度，且继续问比对字段' (($d3.text -like '*order_no和status*') -and ($d3.text -like '*比哪一个字段*')) $d3.text

$d5 = Ask $s4 '比金额吧'
# 注意：PowerShell 的 -like 里 [..] 是字符集通配，这里必须用 Contains 做字面比较
Check 'D5 收齐后先摆骨架等确认' `
    (($d5.text.Contains('dimension: [order_no, status]')) -and $d5.text.Contains('amount') -and ($d5.text -like '*确认*')) $d5.text.Substring(0, [Math]::Min(30, $d5.text.Length))
Check 'D5b 确认前没有落库' (-not ($d5.tools -contains 'recon_define_rule')) "tools=$($d5.tools -join ',')"

$d6 = Ask $s4 '确认'
Check 'D6 确认之后才落草稿' ($d6.tools -contains 'recon_define_rule') "tools=$($d6.tools -join ',')"

# ---------------------------------------------------------------- E 取消
"== E 组：等参数时说算了 =="
$s5 = NewSession '多轮-取消'
$e1 = Ask $s5 '起草一条规则'
Check 'E1 进入等参数' ($e1.text -like '*哪张表*') $e1.text.Substring(0, [Math]::Min(24, $e1.text.Length))
$e2 = Ask $s5 '算了'
Check 'E2 取消要回一句人话，不能再来一遍帮助' ($e2.text -like '*不建了*') $e2.text

# ---------------------------------------------------------------- F 写操作确认（两段式）
"== F 组：推断出来的写操作要先确认 =="
$s6 = NewSession '多轮-写确认'
$f1 = Ask $s6 '跑一下那个账单和结算的对账'
Check 'F1 先确认，不直接跑' ($f1.text -like '*确认*') $f1.text.Substring(0, [Math]::Min(40, $f1.text.Length))
Check 'F1b 确认前没有调用 recon_run' (-not ($f1.tools -contains 'recon_run')) "tools=$($f1.tools -join ',')"

$f2 = Ask $s6 '确认'
Check 'F2 确认之后才真的跑' ($f2.tools -contains 'recon_run') "tools=$($f2.tools -join ',')"

$f3 = Ask $s6 '跑一下那个结算对账'
$f4 = Ask $s6 '算了'
Check 'F3 待确认时取消不会执行' ((-not ($f4.tools -contains 'recon_run')) -and ($f4.text -like '*取消*')) $f4.text

"== 结果：$pass 通过 / $fail 失败 =="
if ($fail -gt 0) { exit 1 }
