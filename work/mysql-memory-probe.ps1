# useCursorFetch 开/关对比：同一个任务、同一份数据，只换 JDBC 参数
# 用法：powershell -File work/mysql-memory-probe.ps1 -CursorFetch true|false
param(
  [Parameter(Mandatory = $true)][string]$CursorFetch,
  [string]$Heap = '256m'
)
$ErrorActionPreference = 'Stop'

$root = 'D:\App\deepseek\workspace\recon-lens'
$java = 'D:\App\ideaIC-2025.3\jbr\bin\java.exe'
$base = 'http://127.0.0.1:18080'
$jar  = Join-Path $root 'target\recon-lens-0.1.0.jar'

$env:SPRING_PROFILES_ACTIVE = 'mysql'
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3307/recon_lens?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=$CursorFetch&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false"
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:SPRING_DATASOURCE_PASSWORD = ''

Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*jbr*' } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
$out = Join-Path $root "work\mem-$CursorFetch-out.log"
$err = Join-Path $root "work\mem-$CursorFetch-err.log"
Remove-Item $out, $err -ErrorAction SilentlyContinue

$p = Start-Process $java -ArgumentList @(
  "-Xmx$Heap", '-jar', $jar,
  '--recon.agent.enabled=true', '--server.port=18080'
) -RedirectStandardOutput $out -RedirectStandardError $err -PassThru

$ok = $false
for ($i = 0; $i -lt 90; $i++) {
  Start-Sleep -Milliseconds 500
  $t = Get-Content $out -Raw -ErrorAction SilentlyContinue
  if ($t -match 'Started ReconLensApplication') { $ok = $true; break }
  if ($p.HasExited) { break }
}
if (-not $ok) { "服务没起来（heap=$Heap cursorFetch=$CursorFetch）"; Get-Content $out -Tail 15; exit 1 }

$sw = [Diagnostics.Stopwatch]::StartNew()
$peak = 0
$body = @{ ruleCode = 'MYSQL_STREAM_RECON'; batchId = "MEM-$CursorFetch"; remark = 'memory probe' } | ConvertTo-Json -Compress
$job = (Invoke-WebRequest "$base/api/jobs" -Method Post -Body $body -ContentType 'application/json' -UseBasicParsing).Content | ConvertFrom-Json
$id = $job.jobId

$failed = $null
for ($i = 0; $i -lt 600; $i++) {
  Start-Sleep -Milliseconds 250
  try { $p.Refresh(); if ($p.WorkingSet64 -gt $peak) { $peak = $p.WorkingSet64 } } catch { }
  try {
    $j = (Invoke-WebRequest "$base/api/jobs/$id" -UseBasicParsing -TimeoutSec 5).Content | ConvertFrom-Json
    if ($j.status -notin @('PENDING', 'RUNNING')) { break }
  } catch { $failed = $_; break }
  if ($p.HasExited) { $failed = '进程退出'; break }
}
$sw.Stop()

$verdict = if ($failed) { '进程/接口异常' } elseif ($j.status -eq 'SUCCESS' -or $j.status -eq 'PARTIAL_FAILED') { $j.status } else { '未知' }
[pscustomobject]@{
  cursorFetch = $CursorFetch
  heap        = $Heap
  job         = $id
  status      = $verdict
  seconds     = [math]::Round($sw.Elapsed.TotalSeconds, 1)
  peakRssMB   = [math]::Round($peak / 1MB, 0)
  diffs       = $j.diffRows
  err         = if ($j.errorMsg) { $j.errorMsg } else { '' }
} | Format-List

if ($failed) {
  "--- 失败线索 ---"
  if ($failed -is [string]) { $failed } else { $failed.Exception.Message }
  Get-Content $out -Tail 20
  Get-Content $err -Tail 10
}
