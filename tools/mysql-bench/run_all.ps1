# run_all.ps1 —— 顺序跑完全部纯 SQL 场景（单线程，避免互相抢 IO/CPU 干扰数字）
# 用法： .\run_all.ps1 [-MysqlExe D:\path\to\mysql.exe]
param(
  [string]$MysqlExe = $env:MYSQL_EXE,
  [string]$DbHost = '127.0.0.1',
  [int]$Port = 3307,
  [string]$User = 'root',
  [string]$DbPassword = 'root'
)
$b = $PSScriptRoot
$steps = @(
  @{ f = '10_s1a_sort_no_index.sql';  t = '10_s1a' },
  @{ f = '11_s2a_order_index.sql';    t = '11_s2a' },
  @{ f = '12_s2b_cover_index.sql';    t = '12_s2b' },
  @{ f = '13_s3_agg.sql';             t = '13_s3'  },
  @{ f = '14_s4_shard.sql';           t = '14_s4'  },
  @{ f = '15_s5_partition.sql';       t = '15_s5'  },
  @{ f = '16_s6_skew.sql';            t = '16_s6'  }
)
if (-not $MysqlExe) { $MysqlExe = 'mysql' }
foreach ($s in $steps) {
  "########## $(Get-Date -Format 'HH:mm:ss') 开始 $($s.f) ##########"
  & "$b\run.ps1" -File $s.f -Tag $s.t -Tail 30 -MysqlExe $MysqlExe -DbHost $DbHost -Port $Port -User $User -DbPassword $DbPassword
  "########## $(Get-Date -Format 'HH:mm:ss') 结束 $($s.f) ##########"
}
"ALL SCENARIOS DONE $(Get-Date -Format 'HH:mm:ss')"
