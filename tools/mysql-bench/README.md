# MySQL 压测装置（`docs/benchmark.md` 第三节的数字就是在这里跑出来的）

这一套脚本用来回答一个问题：**recon-lens 在真的独立 MySQL 进程上、百万到千万行数据上，到底什么快、什么慢、哪个环节会出问题。**
不是基准测试框架，是一次性的实测脚本集合 —— 每条 SQL 都自己打 `FLUSH STATUS` / `EXPLAIN ANALYZE`，
数字直接从输出里读，日志留在 `out\`。

环境是 2026-10 在一台 Windows 开发机上实测的：MySQL **8.0.29**（免安装 zip，独立进程、端口 **3307**）、
JDK 17、Node 18+，`-Xmx256m`。绝对耗时只对这台机器有意义，**看相对倍数和计划形态**。

## 一、准备

1. 起一个自己的 MySQL（**不要用业务库**，这里会建 10M 行的表和 4GB 数据文件）：

   ```ini
   # my-bench.ini
   [mysqld]
   basedir=D:/path/to/mysql-8.0.29-winx64
   datadir=D:/path/to/mysql-bench-data
   port=3307
   character-set-server=utf8mb4
   collation-server=utf8mb4_0900_ai_ci
   innodb_buffer_pool_size=1G          # 机器够大就给 4G，能少掉很多"冷缓存"噪声
   ```

   ```powershell
   mysqld --defaults-file=my-bench.ini --console     # 前台看日志；正式跑可以丢后台
   mysql  -h127.0.0.1 -P3307 -uroot -proot -e "SELECT VERSION()"
   ```

2. 建库并造数（**顺序执行，别跳**）：

   ```powershell
   .\run.ps1 -File 00_init.sql          # 建库 bench
   .\run.ps1 -File 01_nums.sql          # 数字表 d（0..9 的幂集），所有宽表都从它派生
   .\run.ps1 -File 02_t50.sql           # t50：1000 万行 × 50 列 + 刻意的分布设计（倾斜/基数/派生键）
   ```

   `02_t50.sql` 大约要跑十几分钟、落 4.2GB 数据 + 2.1GB 索引。里面的列分布是**故意设计**的
   （`tenant_id` 极端重尾、`acct_id` 100 万基数、`order_no` 唯一且后两位可当分片函数输入），
   后面每个场景都靠它。

3. `mysql.exe` 默认取 PATH 上的；不在 PATH 上就加 `-MysqlExe D:\...\bin\mysql.exe`，或设环境变量
   `MYSQL_EXE`。连接默认 `127.0.0.1:3307`、`root/root`、库 `bench`，可用 `-DbHost/-Port/-User/-DbPassword` 改。

## 二、跑纯 SQL 场景（对应 docs/benchmark.md 3.7~3.10）

```powershell
.\run_all.ps1                     # 依次跑 10~16，单线程，互不抢 IO；每步一份日志
.\run.ps1 -File 15_s5_partition.sql -Tag s5 -Tail 40      # 也可以单跑某个场景
.\stream.ps1 -Query "SELECT * FROM t50 ORDER BY order_no" -Tag s1_wide_stream
```

| 脚本 | 回答的问题 |
| --- | --- |
| `10_s1a_sort_no_index.sql` | 还没有二级索引时，排序/宽行/派生键的执行计划与耗时（`ORDER BY id` 10.75s vs 派生表达式键 56.73s） |
| `11_s2a_order_index.sql` | 给排序键建普通索引的**反面教材**：优化器仍然选 `Table scan + Sort`（7.09s），普通索引没消掉排序；消掉排序的是覆盖索引（见下一行） |
| `12_s2b_cover_index.sql` | 覆盖索引：同一查询、只是把要比的列也放进索引（7.09s → 2.19s） |
| `13_s3_agg.sql` | 聚合口径：`GROUP BY` 表达式键，分组基数 15 万 / 100 万 / 1000 万，临时表与落盘怎么变 |
| `14_s4_shard.sql` | 分片谓词：`MOD(CAST(SUBSTRING(...)))` 函数谓词 vs 生成列桶 + 索引 |
| `15_s5_partition.sql` | 分区裁剪（单月 2.10s vs 不裁剪 49.95s vs 非分区表同谓词 26.05s） |
| `16_s6_skew.sql` | 倾斜：值域等宽 / 哈希取模 / 采样定边界三种切分的最大段与均衡度 |
| `17_s4b_substitution.sql` | 表达式等价替换：`CAST(... AS INT / SIGNED / UNSIGNED)` 哪个能命中生成列索引 |
| `18_s6b_sampled.sql` | 采样边界的真实落地（重尾数据下 8 段行数差到 170 倍） |
| `23_s7_engine_sql_plans.sql` | 把引擎**真正发出去**的 SQL 原样 `EXPLAIN ANALYZE`（判明派生表外包一层会让函数谓词失效） |
| `24_counter_probe.sql` | 状态计数器补测：`SHOW GLOBAL STATUS` 是累计值，场景文件头部的 `ALTER/ANALYZE` 会污染该文件首块，这个文件没有任何 DDL，逐块可归因 |
| `25_late_lookup.sql` | "先排序只取主键、再按主键回表"（late row lookup）值不值：只排序取主键 1000 万行 1.06s（`Sort_rows=0`），但 200 万行两阶段 2.91s vs 单阶段覆盖索引 0.38s（**7.6 倍**）；LL-8/LL-9 是这条路的下限（按主键顺序读聚簇行）0.63s / 4.37s，仍慢 1.7~4.1 倍 |
| `26_prereq_model.sql` | 对账前的预处理值不值：现状每轮在千万行宽表上 `GROUP BY` 21.47s → 物化一次 19.13s（＋建主键/分片索引 0.28s）→ 之后每轮 7.8ms（全量）/15.3ms（单分片）；高基数维度（G≈100 万）每分片 296ms。含 DDL，块内自带 `*_ms` 计时，用 `extract_model.py` 读日志 |

> 计数器怎么读：`FLUSH STATUS` 只清会话计数器，所以「两张 `SHOW GLOBAL STATUS` 之间差多少」才是那条语句的开销。
> `24_counter_probe.sql` 就是为了给场景文件的首块补一份干净的增量（跑法：`.\run.ps1 -File 24_counter_probe.sql -Tag 24_probe`）。
> 批量提取：`python extract_counters.py`（默认读本目录 `out\`，只输出「两块之间恰好一条 `EXPLAIN ANALYZE`」的可归因块，
> 并自动减掉每块固定 +1 的 `Created_tmp_tables` 基线噪声；日志目录可以当第一个参数传）。

## 三、跑引擎真机对账（对应 docs/benchmark.md 3.1~3.5）

```powershell
.\run.ps1 -File 20_s7_engine_tables.sql   # bill_left / bill_right 各 200 万行，埋入已知差异
.\run.ps1 -File 21_s7_engine_ddl.sql      # 引擎四张元数据表建在同一个库（MySQL 版 DDL）
.\run.ps1 -File 22_s7_cover_index.sql     # 分片局部覆盖索引：建索引前后的计划与耗时对照
.\_s7_rerun.ps1 -Pool 24                  # 打包 + 起应用 + 跑 4 个口径 + 收 GC 日志 + 抓连接饥饿证据
.\_s7_rerun.ps1 -Pool 16 -SkipPackage     # 复现"池太小 → 分片全在等连接"（耗时涨 6~11 倍，结果不变）
.\_s7_rerun.ps1 -Cases S7_NARROW_BUCKET8  # 只跑一个口径
```

- 应用参数都在 `_s7_rerun.ps1` 里：`-Xmx256m`、`-Xlog:gc:...`、mysql profile、池大小、
  `--recon.rules-location=file:<本目录>/rules/`、`run-demo-on-startup=false`。
  仓库根目录由脚本位置推出，也可以用 `-RepoRoot` 指；jar 默认取 `target\recon-lens-*.jar`（可用 `-Jar`）。
  `mvnw` 的 `-s settings.xml` 用 `-MavenSettings` 传（默认不传），中央仓库镜像用环境变量 `MVNW_REPOURL`。
- `rules\s7-*.yml` 是这一轮用到的 5 份规则（窄/宽/函数谓词 8 片/函数谓词 SIGNED/桶列 8 片）。

> **坑：规则种子不会覆盖已有规则。** 引擎启动时会扫规则目录导入，但 `recon_rule` 里**已存在**的 `code`
> 会被跳过（`RuleCatalog` 里就是 `continue`）。改完 `rules\*.yml` 想让它生效，先清掉旧行再重启：
> `mysql -h127.0.0.1 -P3307 -uroot -proot bench -e "DELETE FROM recon_rule WHERE code LIKE 'S7%'"`。

## 四、看结果

- 每次 `run.ps1` / `stream.ps1` 都在 `out\` 落一份同名日志（`out\<tag>.log`），里面有完整输出；
  控制台只回显末尾若干行，`-Tail` 可调。
- `_s7_rerun.ps1` 落 `out\s7_p24_engine.log`（墙钟与差异分类）、`out\s7_p24_gc.log`（GC）、
  `out\s7_p24_app.out/.err`（应用输出，末尾会 grep 出连接饥饿证据）。
- `90_probe_diffs.sql`：把某个 job 的差异逐条摊开、对照源表真值，用来验证"引擎报的差异到底对不对"
  （里面的 `job_id` 按自己的任务改）。
- 汇总后的数字、EXPLAIN 原文与结论在 [`../../docs/benchmark.md`](../../docs/benchmark.md)，
  这一轮压测逼出来的 5 个产品问题见该文 3.6 与 [`../../CHANGELOG.md`](../../CHANGELOG.md)。

`out\` 是运行产物，已在 `.gitignore` 里。
