# N9 跨会话检索实测（起 jar 打 HTTP，真实数据）
# 用法：pwsh -File work\recall-scope-smoke.ps1
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080'
$out = Join-Path $PSScriptRoot 'recall-scope-smoke.txt'
if (Test-Path $out) { Remove-Item $out }
$script:pass = 0
$script:fail = 0

function Say($name, $ok, $detail) {
    if ($ok) { $script:pass++; $line = "PASS  $name" } else { $script:fail++; $line = "FAIL  $name" }
    $line = $line + "  ->  " + $detail
    Write-Host $line
    Add-Content -Path $out -Value $line -Encoding utf8
}
function Post($path, $body, $who) {
    $h = @{}
    if ($who) { $h['X-Operator'] = $who }
    (Invoke-WebRequest "$base$path" -Method Post -Body ($body | ConvertTo-Json -Compress) `
            -ContentType 'application/json' -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
}
function GetJson($path, $who) {
    $h = @{}
    if ($who) { $h['X-Operator'] = $who }
    (Invoke-WebRequest "$base$path" -Headers $h -UseBasicParsing).Content | ConvertFrom-Json
}

# 等 jar 就绪
$ready = $false
foreach ($i in 1..60) {
    try { Invoke-WebRequest "$base/api/rules" -UseBasicParsing -TimeoutSec 3 | Out-Null; $ready = $true; break } catch { Start-Sleep -Milliseconds 500 }
}
Say 'A1 服务就绪' $ready $base

$tag = 'N9' + (Get-Random -Maximum 9999)
$a = Post '/api/chat/sessions' @{ title = "alice-$tag" } 'alice'
$b = Post '/api/chat/sessions' @{ title = "bob-$tag" } 'bob'
$asid = $a.session.id; $bsid = $b.session.id
Post "/api/chat/sessions/$asid/messages" @{ role = 'user'; content = "这批差异我怀疑是刷单 $tag" } 'alice' | Out-Null
Post "/api/chat/sessions/$bsid/messages" @{ role = 'user'; content = "我也怀疑这笔 $tag 是刷单" } 'bob' | Out-Null
Say 'A2 两个操作人各建会话并留痕' ($asid -gt 0 -and $bsid -gt 0) "alice=#$asid bob=#$bsid"

# B. 默认只搜自己
$mine = (Invoke-WebRequest "$base/api/chat/recall?q=$tag" -Headers @{'X-Operator'='bob'} -UseBasicParsing).Content | ConvertFrom-Json
$owners = @($mine.items | ForEach-Object { $_.owner } | Sort-Object -Unique)
Say 'B1 默认 scope=mine' ($mine.scope -eq 'MINE') $mine.scope
Say 'B2 只返回自己的命中' (($owners.Count -eq 1) -and ($owners[0] -eq 'bob')) ("owners=" + ($owners -join ','))
Say 'B3 说明里写清默认口径' ($mine.description -like '*只看我自己*') $mine.description

# C. team 范围：共享会话可见，带 owner
Invoke-WebRequest "$base/api/chat/sessions/$asid/visibility" -Method Post -Headers @{'X-Operator'='alice'} `
    -Body (@{ visibility = 'SHARED' } | ConvertTo-Json -Compress) -ContentType 'application/json' -UseBasicParsing | Out-Null
$team = (Invoke-WebRequest "$base/api/chat/recall?q=$tag&scope=team" -Headers @{'X-Operator'='bob'} -UseBasicParsing).Content | ConvertFrom-Json
$teamOwners = @($team.items | ForEach-Object { $_.owner } | Sort-Object -Unique)
Say 'C1 scope=team 生效' ($team.scope -eq 'TEAM') $team.scope
Say 'C2 能看到 alice 的共享会话' ($teamOwners -contains 'alice') ("owners=" + ($teamOwners -join ','))
Say 'C3 每条命中带 owner 与 visibility' (@($team.items | Where-Object { $_.owner -and $_.visibility }).Count -gt 0) ("items=" + $team.total)

# D. 回收成 PRIVATE 之后立刻搜不到（权限不是缓存）
Invoke-WebRequest "$base/api/chat/sessions/$asid/visibility" -Method Post -Headers @{'X-Operator'='alice'} `
    -Body (@{ visibility = 'PRIVATE' } | ConvertTo-Json -Compress) -ContentType 'application/json' -UseBasicParsing | Out-Null
$after = (Invoke-WebRequest "$base/api/chat/recall?q=$tag&scope=team" -Headers @{'X-Operator'='bob'} -UseBasicParsing).Content | ConvertFrom-Json
$afterOwners = @($after.items | ForEach-Object { $_.owner } | Sort-Object -Unique)
Say 'D1 改回 PRIVATE 后别人的内容立刻消失' (-not ($afterOwners -contains 'alice')) ("owners=" + ($afterOwners -join ','))

# E. 参数边界
$e1 = 0; try { Invoke-WebRequest "$base/api/chat/recall?q=$tag&scope=everyone" -Headers @{'X-Operator'='bob'} -UseBasicParsing | Out-Null } catch { $e1 = [int]$_.Exception.Response.StatusCode }
Say 'E1 scope 写错 -> 400' ($e1 -eq 400) "status=$e1"
$e2 = 0; try { Invoke-WebRequest "$base/api/chat/recall?q=$tag" -UseBasicParsing | Out-Null } catch { $e2 = [int]$_.Exception.Response.StatusCode }
Say 'E2 缺 X-Operator -> 400' ($e2 -eq 400) "status=$e2"
$e3 = 0; try { Invoke-WebRequest "$base/api/chat/recall?q=%20&X-Operator=bob".Replace('&X-Operator=bob','') -Headers @{'X-Operator'='bob'} -UseBasicParsing | Out-Null } catch { $e3 = [int]$_.Exception.Response.StatusCode }
Say 'E3 空检索词 -> 400' ($e3 -eq 400) "status=$e3"
$e4 = (Invoke-WebRequest "$base/api/chat/recall?q=$tag&limit=500" -Headers @{'X-Operator'='bob'} -UseBasicParsing).Content | ConvertFrom-Json
Say 'E4 limit 超上限被夹到 50 而不是报错' ($e4.total -le 50) ("total=" + $e4.total)

$line = "`n== N9 跨会话检索：$script:pass/$($script:pass + $script:fail) =="
Write-Host $line
Add-Content -Path $out -Value $line -Encoding utf8
