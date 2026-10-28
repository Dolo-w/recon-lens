# 起 P2 实测实例：真模型 deepseek-flash + 文件库（与上一轮实测同款参数）
# 用法： .\work\p2-real.ps1 [-Port 18097] [-Mode legacy|loop] [-Key vv] [-Stop]
#   -Mode loop  自由循环（recon.chat.mode=loop），库换到 work/p2-loop，可与 legacy 同机并行对比
#   -Stop       只停（改代码重打包前必须先停，否则 jar 被锁）
param(
    [int]$Port = 18097,
    [ValidateSet('legacy', 'loop')]
    [string]$Mode = 'legacy',
    [string]$Key = 'vv',
    [string]$ApiKey = 'sk-cf36851c46394983b7dd79a474aae49d',
    [string]$Model = 'deepseek-flash',
    [int]$MaxHops = 6,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jar = Join-Path $root 'target\recon-lens-0.1.0.jar'

$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match "server\.port=$Port" }
foreach ($p in $running) {
    Stop-Process -Id $p.ProcessId -Force
    Write-Host "stopped PID $($p.ProcessId) (port $Port)"
}
if ($Stop) { exit 0 }
Start-Sleep -Seconds 1

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\App\ideaIC-2025.3\jbr' }
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

$db = if ($Mode -eq 'loop') { 'work/p2-loop' } else { 'work/p2-demo' }
$a = @('-jar', $jar, "--server.port=$Port",
    '--recon.agent.enabled=true',
    '--recon.mcp.http.enabled=true',
    "--recon.review.api-key=$Key",
    "--recon.security.api-key=$Key",
    "--spring.datasource.url=jdbc:h2:file:./$db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE",
    '--recon.chat.llm.base-url=https://api.deepseek.com/v1',
    "--recon.chat.llm.api-key=$ApiKey",
    "--recon.chat.llm.model=$Model",
    '--recon.chat.llm.timeout-ms=60000',
    '--recon.chat.loop.enabled=true',
    "--recon.chat.mode=$Mode",
    "--recon.chat.loop.max-hops=$MaxHops")

$log = Join-Path $root "logs\app-p2-$Port.log"
$err = Join-Path $root "logs\app-p2-$Port.err"
New-Item -ItemType Directory -Force -Path (Join-Path $root 'logs') | Out-Null
$proc = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $a -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden -WorkingDirectory $root

$sw = [Diagnostics.Stopwatch]::StartNew()
while ($sw.Elapsed.TotalSeconds -lt 60) {
    Start-Sleep -Milliseconds 400
    $code = & curl.exe -s -o NUL -w "%{http_code}" "http://127.0.0.1:$Port/api/jobs" 2>$null
    if ($code -eq '200') {
        Write-Host ("P2 就绪 http://127.0.0.1:$Port  PID $($proc.Id)  mode=$Mode  hops=$MaxHops  用时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + ' s  模型 ' + $Model)
        exit 0
    }
    if ($proc.HasExited) { throw "进程退出 code=$($proc.ExitCode)：看 $err" }
}
throw "60 秒没起来：看 $log / $err"
