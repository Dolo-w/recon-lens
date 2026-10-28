# 按 README 的形状启动 P2（不阻塞、就绪即返回）
# 用法：
#   $env:DEEPSEEK_API_KEY="sk-..."        # 必填，P2 的模型路由
#   $env:RECON_REVIEW_KEY="vv"            # 审批密钥（闸门 3），默认就用 vv
#   .\work\run-p2.ps1 -Port 18097
param(
    [int]$Port = 18097,
    [string]$ReviewKey = $(if ($env:RECON_REVIEW_KEY) { $env:RECON_REVIEW_KEY } else { 'vv' }),
    [string]$LlmModel = $(if ($env:RECON_LLM_MODEL) { $env:RECON_LLM_MODEL } else { 'deepseek-flash' }),
    [switch]$WithMcpHttp,
    [switch]$DemoTrialGate
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jar = Join-Path $root 'target\recon-lens-0.1.0.jar'

if (-not (Test-Path $jar)) { throw "没有 $jar：先跑 .\mvnw.cmd -o package -DskipTests" }
$llmKey = $env:DEEPSEEK_API_KEY
if ([string]::IsNullOrWhiteSpace($llmKey)) { Write-Warning 'DEEPSEEK_API_KEY 为空：会用词表档（P1）启动，模型路由空转' }

# 已经在跑就不重复起
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match "server.port=$Port" }
if ($running) {
    Write-Host "端口 $Port 已有实例（PID $($running.ProcessId)），不重复启动"
    exit 0
}

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\App\ideaIC-2025.3\jbr' }
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

$a = @('-jar', $jar, "--server.port=$Port",
    '--recon.agent.enabled=true',
    "--recon.review.api-key=$ReviewKey",
    "--recon.security.api-key=$ReviewKey")
if ($llmKey) {
    # 两套配置是分开的：chat.* 学会话理解，llm.* 管起草与归因
    $a += @("--recon.chat.llm.base-url=https://api.deepseek.com/v1",
            "--recon.chat.llm.api-key=$llmKey",
            "--recon.chat.llm.model=$LlmModel",
            '--recon.chat.llm.timeout-ms=60000',
            '--recon.llm.enabled=true',
            '--recon.llm.base-url=https://api.deepseek.com/v1',
            "--recon.llm.api-key=$llmKey",
            "--recon.llm.model=$LlmModel")
}
if ($WithMcpHttp) { $a += '--recon.mcp.http.enabled=true' }
if ($DemoTrialGate) {
    # 演示库只有 10 行：降到 5/3 才能让试算 PASS。**这不是生产配置**，它改变闸门版本。
    $a += @('--recon.trial.min-sample-rows=5', '--recon.trial.min-common-keys=3')
}

$log = Join-Path $root "logs\app-$Port.log"
$err = Join-Path $root "logs\app-$Port.err"
New-Item -ItemType Directory -Force -Path (Join-Path $root 'logs') | Out-Null
$p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $a -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden

# 轮询到能服务为止：实测 2~3 秒，最多等 30 秒
$sw = [Diagnostics.Stopwatch]::StartNew()
$deadline = 30
while ($sw.Elapsed.TotalSeconds -lt $deadline) {
    Start-Sleep -Milliseconds 200
    $code = & curl.exe -s -o NUL -w "%{http_code}" "http://127.0.0.1:$Port/api/jobs" 2>$null
    if ($code -eq '200') {
        $sw.Stop()
        Write-Host ("P2 就绪：http://127.0.0.1:$Port  PID $($p.Id)  用时 " + [math]::Round($sw.Elapsed.TotalSeconds, 2) + ' s')
        Write-Host "  模型： $(if ($llmKey) { $LlmModel } else { '（无 key，词表档）' })   审批密钥：$ReviewKey"
        Write-Host "  日志： $log"
        exit 0
    }
    if ($p.HasExited) { throw "进程退出（code $($p.ExitCode)）：看 $err" }
}
throw "30 秒没起来：看 $log / $err"
