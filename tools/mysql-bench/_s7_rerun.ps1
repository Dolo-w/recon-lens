# _s7_rerun.ps1 —— 让 recon-lens 引擎连本机 MySQL（默认 127.0.0.1:3307/bench）跑真实对账，
# 记录墙钟、差异分类与 GC 日志。docs/benchmark.md 3.1~3.5 的数字来自这个脚本。
#
# 用法：
#   .\_s7_rerun.ps1                      # 池 24，跑全部 4 个口径（干净基线）
#   .\_s7_rerun.ps1 -Pool 16             # 池 16，复现"默认配置下连接饥饿 → 分片重试"
#   .\_s7_rerun.ps1 -SkipPackage         # 不重新打包
#   .\_s7_rerun.ps1 -Cases S7_NARROW     # 只跑一个口径
#
# 前置：MySQL 已按 README 起好、20/21/22 号脚本已执行（引擎表 + bill 表 + 覆盖索引）、
#       rules\s7-*.yml 已就位（或已通过 POST /api/rules 灌进 recon_rule）。
param(
    [int]$Pool = 24,
    [switch]$SkipPackage,
    [string]$Cases = '',
    [string]$TagSuffix = '',
    # 仓库根目录：默认由脚本位置推出（tools/mysql-bench → 仓库根）
    [string]$RepoRoot = '',
    # 产物目录（日志）：默认就放本目录下的 out\
    [string]$BenchDir = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenSettings = '',
    [string]$MvnwRepoUrl = $env:MVNW_REPOURL,
    [string]$NodeExe = 'node',
    [string]$Jar = '',
    [string]$DbHost = '127.0.0.1',
    [int]$Port = 3307,
    [string]$Db = 'bench',
    [string]$User = 'root',
    [string]$DbPassword = 'root',
    [string]$AppBase = 'http://127.0.0.1:8080'
)
$ErrorActionPreference = 'Continue'
if (-not $BenchDir) { $BenchDir = $PSScriptRoot }
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
$javaExe = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java' }
if (-not $Jar) {
    $cand = Get-ChildItem (Join-Path $RepoRoot 'target') -Filter 'recon-lens-*.jar' -EA SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $cand) { throw "找不到 jar：先跑 `.\mvnw.cmd -DskipTests package`，或用 -Jar 指定" }
    $Jar = $cand.FullName
}
$tag = if ($Pool -eq 16) { 's7_p16' } else { 's7_p24' }
if ($Cases) { $tag = "${tag}_only" }
if ($TagSuffix) { $tag = "$tag$TagSuffix" }
$outDir = Join-Path $BenchDir 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Set-Location $RepoRoot

if (-not $SkipPackage) {
    '=== package ==='
    $mvnArgs = @('-B', '-DskipTests', '--no-transfer-progress')
    if ($MavenSettings) { $mvnArgs = @('-s', $MavenSettings) + $mvnArgs }
    & (Join-Path $RepoRoot 'mvnw.cmd') @mvnArgs package 2>&1 |
        Select-String -Pattern 'BUILD|ERROR|Building jar' | ForEach-Object { $_.Line.Trim() }
}

$gc = Join-Path $outDir "${tag}_gc.log"
$appOut = Join-Path $outDir "${tag}_app.out"
$appErr = Join-Path $outDir "${tag}_app.err"
foreach ($f in @($gc, $appOut, $appErr)) { Remove-Item $f -EA SilentlyContinue }
$url = "jdbc:mysql://${DbHost}:${Port}/${Db}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false"
$rulesDir = (Join-Path $BenchDir 'rules') -replace '\\', '/'
$arguments = @(
    '-Xmx256m',
    "-Xlog:gc:file=${gc}:time,uptime,level,tags",
    '-jar', $Jar,
    '--spring.profiles.active=mysql',
    "--spring.datasource.url=$url",
    "--spring.datasource.username=$User",
    "--spring.datasource.password=$DbPassword",
    "--spring.datasource.hikari.maximum-pool-size=$Pool",
    "--recon.rules-location=file:$rulesDir/",
    '--recon.engine.run-demo-on-startup=false'
)
"=== 启动应用 pool=$Pool jar=$Jar ==="
$app = Start-Process -FilePath $javaExe -ArgumentList $arguments -WorkingDirectory $RepoRoot `
    -RedirectStandardOutput $appOut -RedirectStandardError $appErr -PassThru
"app pid=$($app.Id)"

$deadline = (Get-Date).AddSeconds(120)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri "$AppBase/api/rules" -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 1000 }
}
"app ready=$ready  after $([int]((Get-Date) - $app.StartTime).TotalSeconds)s"

if ($ready) {
    $driver = Join-Path $BenchDir '_s7_run.mjs'
    if ($Cases) {
        & $NodeExe $driver $AppBase $Cases 2>&1 | Tee-Object -FilePath (Join-Path $outDir "${tag}_engine.log")
    } else {
        & $NodeExe $driver $AppBase 2>&1 | Tee-Object -FilePath (Join-Path $outDir "${tag}_engine.log")
    }
} else {
    '=== app 未就绪，输出启动日志 ==='
    Get-Content $appErr -EA SilentlyContinue | Select-Object -Last 40
}

"=== 连接饥饿证据（分片重试 / 连接超时）==="
$hits = Select-String -Path $appOut, $appErr -Pattern '第 \d+/\d+ 次执行失败|SQLTransientConnectionException|Connection is not available' -EA SilentlyContinue
"命中 $($hits.Count) 行"
$hits | Select-Object -First 8 | ForEach-Object { $_.Line.Trim() }

Stop-Process -Id $app.Id -Force -EA SilentlyContinue
Start-Sleep -Seconds 1
"app stopped"
