# stream.ps1 —— 端到端"把结果集全部搬走"的墙钟测量（最接近 recon-lens 通过 JDBC 流式消费每一行的形态）
# 与 EXPLAIN ANALYZE 的区别：这里包含协议打包与传输，不含客户端格式化（--batch --quick）
# 用法： .\stream.ps1 -Query "SELECT * FROM t50 ORDER BY order_no" -Tag s1_wide_stream
param(
  [Parameter(Mandatory = $true)][string]$Query,
  [Parameter(Mandatory = $true)][string]$Tag,
  [string]$Db = 'bench',
  [string]$MysqlExe = $env:MYSQL_EXE,
  [string]$DbHost = '127.0.0.1',
  [int]$Port = 3307,
  [string]$User = 'root',
  [string]$DbPassword = 'root',
  [string]$BenchDir = $PSScriptRoot
)
if (-not $MysqlExe) { $MysqlExe = 'mysql' }
$outDir = Join-Path $BenchDir 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$log = Join-Path $outDir "$Tag.log"

$t0 = Get-Date
"===== $(Get-Date -Format 'HH:mm:ss') stream: $Tag =====" | Tee-Object -FilePath $log
"SQL: $Query" | Tee-Object -FilePath $log -Append
& $MysqlExe -h $DbHost -P "$Port" "-u$User" "--password=$DbPassword" --default-character-set=utf8mb4 `
  --batch --quick --raw $Db -e $Query 2>$null > $null
$code = $LASTEXITCODE
$elapsed = ((Get-Date) - $t0).TotalSeconds
"墙钟（含结果集传输）: $([math]::Round($elapsed,1)) s   exit=$code" | Tee-Object -FilePath $log -Append
