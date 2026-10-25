param([string]$Base = "http://127.0.0.1:18090", [string]$Operator = "alice")
$ErrorActionPreference = "Stop"
$head = @("X-Operator: $Operator", "Content-Type: application/json; charset=utf-8")

function Ask($sid, $msg) {
    $body = @{ sessionId = $sid; message = $msg } | ConvertTo-Json -Compress
    $raw = & curl.exe -s -N -X POST "$Base/api/chat" -H $head[0] -H "Content-Type: application/json; charset=utf-8" --data-binary $body
    $text = ""
    foreach ($line in $raw -split "`n") {
        $l = $line.Trim()
        if ($l.StartsWith("data:")) {
            $json = $l.Substring(5).Trim()
            try { $o = $json | ConvertFrom-Json } catch { continue }
            if ($o.text) { $text += $o.text }
        }
    }
    return $text
}

function NewSession($title) {
    $body = @{ title = $title } | ConvertTo-Json -Compress
    $r = & curl.exe -s -X POST "$Base/api/chat/sessions" -H $head[0] -H $head[1] --data-binary $body | ConvertFrom-Json
    return $r.id
}

function Draft($sid, $compare) {
    "--- 起草（比 $compare）---"
    Ask $sid "起草个规则，编码 DEMO_CONFLICT" | Select-Object -Last 1
    Ask $sid "左 bill_left" | Select-Object -Last 1
    Ask $sid "右 bill_right" | Select-Object -Last 1
    Ask $sid "用 order_no 连" | Select-Object -Last 1
    Ask $sid "比 $compare" | Select-Object -Last 1
    Ask $sid "确认" | Select-Object -Last 1
}

$s1 = NewSession "口径 A"
Draft $s1 "amount"
$s2 = NewSession "口径 B"
Draft $s2 "status"

$s3 = NewSession "执行前反问"
"--- 第三个会话执行前应反问 ---"
$reply = Ask $s3 "跑一下 DEMO_CONFLICT"
$reply
"--- 选第二个 ---"
Ask $s3 "第二个"
