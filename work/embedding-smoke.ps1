# ADR-0001 D4/N4 实测：检索降级链（向量 → 全文 → 关键词）
# 用 pwsh 7 跑：& ./work/embedding-smoke.ps1
#
# 为什么要有这个脚本：单测里向量层是"假实现"，证明的是排序逻辑；
# 这里用真的 HTTP 向量服务（work/fake-embed.mjs，8 维哈希桶）证明
# "配置了模型就走向量、没配置就降级并说清原因"这条装配在真进程上成立。
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$node = 'D:\node\node.exe'
$java = "$env:JAVA_HOME\bin\java.exe"
if (-not (Test-Path $java)) { $java = 'D:\App\ideaIC-2025.3\jbr\bin\java.exe' }
$jar = 'target\recon-lens-0.1.0.jar'
$script:ok = 0
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) { $script:ok++; Write-Host ("[PASS] {0}  {1}" -f $name, $detail) }
    else { $script:fail++; Write-Host ("[FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red }
}

function Wait-App {
    Remove-Item work\app-out.log, work\app-err.log -ErrorAction SilentlyContinue
    Start-Process $java -ArgumentList $args -RedirectStandardOutput work\app-out.log -RedirectStandardError work\app-err.log
    foreach ($i in 1..80) {
        Start-Sleep -Milliseconds 500
        if ((Get-Content work\app-out.log -Raw -ErrorAction SilentlyContinue) -match 'Started ReconLensApplication') { return }
    }
    throw "应用没起来，见 work\app-out.log"
}

function Stop-App {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like '*jbr*' } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Stop-App
Get-Process node -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*fake-embed*' } |
    Stop-Process -Force -ErrorAction SilentlyContinue

$embedder = Start-Process $node -ArgumentList 'work\fake-embed.mjs' `
    -RedirectStandardOutput work\fake-embed.log -RedirectStandardError work\fake-embed.err -PassThru
Start-Sleep -Seconds 2
$log = Get-Content work\fake-embed.log -Raw -ErrorAction SilentlyContinue
Check 'E1 假向量服务就绪' ($log -match 'listening on 18099') $log.Trim()

# ---------- 第一段：配置了向量服务 ----------
Wait-App '-jar' $jar '--recon.agent.enabled=true' '--server.port=18080' `
    '--recon.recall.embedding.url=http://127.0.0.1:18099/v1/embeddings' `
    '--recon.recall.embedding.dim=8' '--recon.recall.embedding.model=fake-8'

$doc = @{ title = '结算口径手册'; source = 'finance'
    text = '手续费按 0.6% 计入，汇率进位差额不超过 0.01 元。金额不一致要先看有没有跨日。' } | ConvertTo-Json -Compress
$idx = (Invoke-WebRequest "$base/api/docs" -Method Post -Body $doc -ContentType 'application/json' -UseBasicParsing).Content | ConvertFrom-Json
Check 'E2 文档入库成功' ($idx.chunks -ge 1) "docId=$($idx.docId) chunks=$($idx.chunks)"

Start-Sleep -Milliseconds 800
$s = (Invoke-WebRequest "$base/api/docs/search?q=%E6%89%8B%E7%BB%AD%E8%B4%B9" -UseBasicParsing).Content | ConvertFrom-Json
Check 'E3 配了模型就走向量层' ($s.mode -eq 'VECTOR') "mode=$($s.mode) degraded=$($s.degraded)"
Check 'E4 向量层不算降级且没有原因' (($s.degraded -eq $false) -and [string]::IsNullOrEmpty($s.reason)) "reason='$($s.reason)'"
Check 'E5 向量层真的有命中并且带分数' ($s.total -ge 1 -and $s.items[0].score -gt 0) "total=$($s.total) score=$($s.items[0].score)"

# 归因结论里要写明这次是怎么检索的
$job = (Invoke-WebRequest "$base/api/jobs" -Method Post -Body (@{ ruleCode = 'BILL_VS_SETTLE' } | ConvertTo-Json -Compress) -ContentType 'application/json' -UseBasicParsing).Content | ConvertFrom-Json
$jobId = $job.jobId
$diffs = (Invoke-WebRequest "$base/api/diffs`?jobId=$jobId" -UseBasicParsing).Content | ConvertFrom-Json
# 挑一条"金额不一致"：只有这个类型会被翻译成『金额 不一致』去查手册
$target = $diffs.items | Where-Object { $_.diffType -like '*MISMATCH*' } | Select-Object -First 1
$expText = (Invoke-WebRequest "$base/api/diffs/$($target.id)/explain" -Method Post -Body '{}' -ContentType 'application/json' -UseBasicParsing).Content
Check 'E6 归因里写明检索方式' ($expText -match '检索方式') "命中 diffType=$($target.diffType)"

Stop-App

# ---------- 第二段：不配置向量服务 ----------
Wait-App '-jar' $jar '--recon.agent.enabled=true' '--server.port=18080'
$idx2 = (Invoke-WebRequest "$base/api/docs" -Method Post -Body $doc -ContentType 'application/json' -UseBasicParsing).Content | ConvertFrom-Json
Start-Sleep -Milliseconds 500
$s2 = (Invoke-WebRequest "$base/api/docs/search?q=%E6%89%8B%E7%BB%AD%E8%B4%B9" -UseBasicParsing).Content | ConvertFrom-Json
Check 'E7 没配模型时不走向量' ($s2.mode -ne 'VECTOR') "mode=$($s2.mode)"
Check 'E8 降级被标出来' ($s2.degraded -eq $true) "degraded=$($s2.degraded)"
Check 'E9 降级原因说人话' ($s2.reason -match '未配置向量模型') "reason=$($s2.reason)"
Check 'E10 降级后仍能查到口径' ($s2.total -ge 1) "total=$($s2.total)"

Stop-App
Get-Process -Id $embedder.Id -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host ("结果：{0} 通过 / {1} 失败" -f $script:ok, $script:fail)
if ($script:fail -gt 0) { exit 1 }
