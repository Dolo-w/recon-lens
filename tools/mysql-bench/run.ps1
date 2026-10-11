# run.ps1 —— 统一的 MySQL 基准执行器（docs/benchmark.md 第三节的数字就是用它跑出来的）
#
# 用法：
#   .\run.ps1 -File 14_s4_shard.sql -Tag s4
#   .\run.ps1 -File 13_s3_agg.sql -Tag s3 -Vertical
#
# 连接默认走 127.0.0.1:3307 / root / bench（本轮实测的免安装 MySQL）；`mysql.exe` 默认取 PATH 上的，
# 也可以用 -MysqlExe 显式指定，或用环境变量 MYSQL_EXE。
# 输出：<本目录>\out\<tag>.log（同时打到控制台）
param(
  [Parameter(Mandatory = $true)][string]$File,
  [string]$Tag = '',
  [switch]$Vertical,
  [switch]$NoTable,
  [int]$Tail = 25,
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
$name = if ($Tag) { $Tag } else { [IO.Path]::GetFileNameWithoutExtension($File) }
$log = Join-Path $outDir ("$name.log")
$sqlPath = if (Test-Path $File) { (Resolve-Path $File).Path } else { Join-Path $BenchDir $File }
if (-not (Test-Path $sqlPath)) { throw "SQL 文件不存在: $sqlPath" }

$clientArgs = @('-h', $DbHost, '-P', "$Port", "-u$User", "--password=$DbPassword",
  '--default-character-set=utf8mb4', '--show-warnings', '--comments')
if ($Vertical) { $clientArgs += '--vertical' } elseif (-not $NoTable) { $clientArgs += '--table' }

$t0 = Get-Date
"===== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') 开始 $File -> $log =====" | Tee-Object -FilePath $log
& $MysqlExe @clientArgs -e "SOURCE $($sqlPath -replace '\\','/');" *>&1 |
  Tee-Object -FilePath $log -Append |
  Select-Object -Last $Tail
$code = $LASTEXITCODE
$elapsed = ((Get-Date) - $t0).TotalSeconds
"===== 结束 $File exit=$code 耗时 $([math]::Round($elapsed,1))s =====" | Tee-Object -FilePath $log -Append
