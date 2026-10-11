# 压测：百万级对账怎么跑、看到了什么

目标不是刷一个漂亮 QPS，而是回答三个具体问题：

1. **分片会不会改变结果？** 8 分片与 1 分片的差异必须逐条一致；
2. **引擎自己的内存占用跟数据量是什么关系？**
3. **分片谓词写成什么样才真的快？**

第 1、3 条在仓库里可以直接复现（下面有实测数字）；第 2 条的结论要分两种情况说清楚，
因为「嵌入式数据库」和「独立数据库」给出的答案完全不同 —— 这点很容易被压测报告糊弄过去。
**第三节是独立 MySQL 进程上的真机实测**：2M×2 真对账、`-Xmx256m` 跑完、GC 曲线，以及被这次压测
逼出来的 5 个真问题（连接泄漏、`error_msg` 溢出、连接池饿死、规则种子优先级、MySQL 建表脚本）；
装置原样入库在 [`tools/mysql-bench/`](../tools/mysql-bench/)。

## 结论速览（先看这一节）

一句话版：**分片不改变结果；内存与数据量解耦（前提是数据库是独立进程）；性能只取决于两件事 —— 分片谓词能不能命中索引、投影列在不在索引里，跟数据量本身关系不大；一次分片对账的总时长由最重的那个分片决定。**

| 场景 | 一句话结论 | 关键数字（本机实测，量级参考） |
| --- | --- | --- |
| 分片会不会改变结果 | **不会**，差异分类逐条一致 | H2：8 分片 vs 1 分片都是 800 条；MySQL：四口径都是 501 条 |
| 引擎内存与数据量 | **解耦**（前提：数据库是独立进程） | `-Xmx256m` 跑 2M×2 四遍：Full GC **0** 次、堆峰值 181 MB、最长停顿 5.9 ms；同样 256 MB 在 H2 嵌入式上连百万级都 OOM |
| 排序（1000 万行） | 成本由**行宽**决定，不是"有没有索引" | `ORDER BY id` 10.75 s（压根不排序）；窄行 8.32 s；`SELECT *` 37.74 s；表达式键 56.73 s。读的行数都是 1000 万，差别在归并趟数 581 / 2,651 / 2,666 |
| 给排序键建普通索引 | **基本白建**：优化器仍走 `Table scan + Sort` | 7.09 s；真正消掉排序的是**覆盖索引** 2.19 s（3 倍） |
| 投影里多一个索引外的列 | **排序立刻回来**：退回扫描 + filesort | 覆盖索引 1.42~2.19 s → 多取一列 `fee` 7.79~8.22 s（≈4~5 倍）；4 列 vs 51 列 = 6.37 s vs 67.27 s（**10.6 倍**） |
| 先排序只取主键、再按主键回表 | **只有"取前 N 行"划算**；全量对账时优化器自己都不选这条路，"分批 `IN`"也救不了它 | 200 万行：覆盖索引 0.38 s vs 两阶段 2.91 s（**7.6 倍**）；回表的绝对下限（按主键顺序顺序读聚簇行，零随机 IO）1000 万行也要 4.37 s，比覆盖索引 1.06 s 慢 **4.1 倍**；带 `LIMIT` 时 MySQL 主动改走"索引序 + 回表"（见 3.9） |
| 分区表 + 排序键索引 + `SELECT *` | **索引根本不会被用来消排序** | 裁到单月仍是 `Sort ← Filter ← Table scan`（读 708,163 行）2.10 s |
| 分片谓词 | 必须是**能命中索引的列**；函数谓词 = 每片扫全表 | 函数谓词 1.22~2.77 s/片 → 桶列+索引 0.145~0.16 s/片（8~12 倍）；引擎墙钟 2,329 → 1,083 ms（单分片读 260,000 行 vs 1,999,900 行） |
| 表达式替换的边界 | 类型必须与生成列定义**完全一致** | `AS UNSIGNED` 读 130 万行 / 1.49 s；`AS SIGNED` 读 1000 万行 / 1.79 s（**7.7 倍读放大**；都是覆盖索引扫描，所以墙钟只差 0.3 s） |
| 分组聚合 | 键有索引 = 流式；表达式键必落临时表 | 流式：临时表 0、排序 0；表达式键 G≈15 万 7.37 s；**G≈100 万起落盘**、读放大到 2000 万行；`temptable_max_ram` 1 GB→128 MB：48.96 s → 69.82 s |
| 分区裁剪 | **读得更少，不是排得更快** | 单月：读 708,163 行 / 2.10 s；不裁剪：读 1000 万行 / 49.95 s；非分区表同谓词 26.05 s。两者排序量都是 656,475 行（**14.1 倍读放大**） |
| 数据倾斜 | **最重的分片决定总时长**，分片再多也没用 | 值域等宽：最重段 868 万行 **51.39 s**、最轻段 18.8 万行 0.88 s（**58 倍**）；哈希桶 max/avg 4.149；采样定边界 max/avg 4.00 且留 3 个空段 |
| 连接池 | 必须 ≥ `2 × recon.engine.parallelism` | 池 16 在 8 分片时刚好占满：**结果仍全对，只是慢 6~11 倍**（2,329 → 13,992 ms），默认已改 24 |
| 对账前的预处理（把汇总物化成模型表） | **把"每轮都要重付一遍"的源侧扫描 + 汇总提前做一次**，之后每轮只碰模型表（成本从 O(源数据量) 变成 O(维度基数)） | 1000 万行宽表按 `customer_id+status` 汇总：现状每轮 **21.47 s** → 预处理一次 19.13 s + 建主键/索引 0.28 s → 之后每轮 **7.8~15.3 ms**（**第 2 轮就回本**，之后每轮快千倍级）；高基数维度（G≈100 万）每分片 296 ms、8 片约 2.4 s（同口径现状 48.96 s）；模型表 5 MB vs 宽表 4.2 GB（见 3.10；引擎实现 `recon_build_model` 与之的差异见 3.10.1：不建主键、桶列取模；真实 MySQL 200 万行的验证与"行级明细规则别建"见 3.10.2） |

三条最容易踩的坑：

1. **分片谓词用到的列必须出现在规则投影里**，否则 `Unknown column` —— 引擎把规则 SQL 包成了 `SELECT * FROM (<规则里的 sql>) src WHERE <分片谓词> ORDER BY <keyField>`，函数化谓词也不会被下推回基表（3.3）；
2. **改 `rules/*.yml` 要重启才生效**：启动时规则目录会与库里的**同名规则**对齐，内容不一致就按文件覆盖成新版本；改完想立刻生效而不重启，请走 `POST /api/rules`（3.6 第 4 条、第四节）；
3. **`schema.sql` 不能给 MySQL 用**（`CREATE INDEX IF NOT EXISTS` 是 H2 语法），给 DBA 的是 `src/main/resources/schema-mysql.sql`（3.6 第 5 条）。

> 数字口径：同一配置连跑 4 次波动在 ±10% 以内，隔一轮环境会有 ±25% 的系统性漂移，所以**只把 2 倍以上的差距当结论**。每个结论的证据（计划 + 计数器）见对应小节，边界见第四节。

## 一、仓库内可复现：百万级 + 8 分片对照（H2）

不需要装 MySQL，`./mvnw test` 默认也不会跑它（`@Tag("bench")` 已在 pom 里排除）。

```bash
./mvnw -Dtest=BenchH2Test -Dsurefire.excludedGroups= \
    -Dsurefire.argLine="-Xmx2g -Dfile.encoding=UTF-8" test
```

数据由 `BenchH2Test.prepare()` 现场造进 H2 文件库（`./target/bench-db-<纳秒>`，每跑一次换一个库，
免得 schema 建表冲突）：

| 埋的差异 | 条件 | 条数 |
| --- | --- | --- |
| 右侧整条缺失 `MISSING_RIGHT` | `n % 10000 = 0`，右侧不发这条 | 100 |
| 左侧缺失、右侧独有 `MISSING_LEFT` | `n % 10000 = 2` 的键换成右侧独有编号 | 100 |
| 同键重复 `DUPLICATE_KEY` | `n % 10000 = 1` 的键右侧再插一条 | 100 |
| 金额差 1 元 `AMOUNT_MISMATCH` | `n % 2000 = 0` 且右侧存在 | 400 |
| 状态取反 `STATUS_MISMATCH` | `n % 5000 = 0` 且右侧存在 | 100 |

两个取模条件的交集恰好是 `n % 10000 = 0`，这些行右侧整条缺失、已经计入 `MISSING_RIGHT`，
不会再产生数值差异，所以金额/状态是 400/100 而不是 500/200，**总差异 800**。

### 实测结果（Windows 11 / JDK 17.0.20 / H2 2.2.224 文件库 / `-Xmx2g`，本机数据，仅作量级参考）

| 跑法 | 分片 | 耗时 | 吞吐 | 左侧扫描 | 右侧扫描 | 匹配 | 差异 | 堆占用（跑前 → 跑后） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `BIG_VS_SETTLE` | 8（并行度 8） | 37,426 ms | 5.34 万行/秒 | 1,000,000 | 1,000,100 | 999,900 | 800 | 303 → 1,446 MB |
| `BIG_VS_SETTLE_SINGLE` | 1 | 4,334 ms | 46.15 万行/秒 | 1,000,000 | 1,000,100 | 999,900 | 800 | 1,447 → 1,569 MB |

两种分片口径的差异分类完全相同：`{AMOUNT_MISMATCH=400, DUPLICATE_KEY=100, MISSING_LEFT=100, MISSING_RIGHT=100, STATUS_MISMATCH=100}`
—— 问题 1 的答案是**不影响**，这条断言就写在 `BenchH2Test` 里，改坏了测试会红。

### 反直觉的地方：这里 8 分片比 1 分片慢 8.6 倍

不是并行度的问题，是**分片谓词不可索引**的问题。规则里的分片条件是：

```sql
WHERE MOD(CAST(SUBSTRING(order_no, 4) AS BIGINT), 8) = 3   -- 每个分片都要算一遍表达式
```

`order_no` 上的索引对这种"列上套函数"的谓词没用，于是每个分片都对整张表做一次全表扫描 +
排序：8 分片 = 左表扫 8 遍、右表扫 8 遍（约 1,600 万行读取），1 分片只扫 2 遍。
**分片并行只有在分片谓词能命中索引时才有意义。**

要让分片命中索引，解法是在数据库侧把"分片桶"变成真正的索引列，规则里只引用这个列：

```sql
-- MySQL 8：函数索引（不用改表结构）
CREATE INDEX idx_left_bucket  ON bill_left  ((MOD(n, 8)));
CREATE INDEX idx_right_bucket ON bill_right ((MOD(n, 8)));

-- 或者生成列 + 普通索引
ALTER TABLE bill_left  ADD COLUMN shard_bucket INT AS (MOD(n, 8)) STORED, ADD KEY idx_left_bucket (shard_bucket);
ALTER TABLE bill_right ADD COLUMN shard_bucket INT AS (MOD(n, 8)) STORED, ADD KEY idx_right_bucket (shard_bucket);
```

```yaml
shard:
  count: 8
  # 两侧引用同一个"业务来源"的桶列：n 在左右表是同一个值（都由 order_no 派生）
  whereLeft:  "shard_bucket = {index}"
  whereRight: "shard_bucket = {index}"
```

> 上面两种写法都已在 MySQL 8.0.29 上实测（见第三节）：把分片桶落成**真实列 + 索引**之后，单分片
> 有序取数从 `Index lookup ... idx_shard_bucket (actual time=0.032..223.525)`（还要 filesort）变成
> `Covering index lookup ... idx_s7_shard_cover (actual time=0.029..92.824)`（同一脚本内的前后对照，
> 2.4 倍）；8 分片的引擎级墙钟从 2,311 ms 降到 1,083 ms。
> 实测的是"普通列 + 复合覆盖索引"（桶列在最左，后面跟排序键与投影列）；生成列 + 索引这条路的
> 计划替换边界见第三节 3.7：`AS UNSIGNED` 能被替换成生成列，`AS SIGNED` 不行。
> `tools/gen_bill_data.sql` 造的表里有 `n` 列和 `idx_left_n / idx_right_n`，桶列就建在 `n` 上。

**为什么不能直接写"每个分片一段键范围"？** 那才是归并式对账最自然的切法（每个分片取一段连续键，
天然走索引），但当前 DSL 的分片模板里只有 `{index}` 与 `{shards}` 两个占位符、所有分片共用同一段
`where`，表达不了"按范围给每个分片不同的边界"。`shard.strategy: RANGE`（解析器查一次键的
min/max，再均分边界）记在 README 的后续计划里 —— 这个限制是被这次压测逼出来的。

## 二、`-Xmx256m` 为什么在 H2 上跑不过：它是嵌入式库，跟被测代码同一个堆

第一次把堆限制到 256M 直接翻车，8 个分片全部报错：

```
分片 1 第 1/2 次执行失败: IllegalStateException: 取数失败:
  SELECT * FROM (SELECT * FROM big_left) src WHERE MOD(...) = 1 ORDER BY order_no
  | 原因 OutOfMemoryError: Java heap space
```

值得说的有两点：

- **OOM 来自 H2 引擎，不是归并逻辑**：嵌入式模式下 H2 的排序/结果缓冲和被测 JVM 共用一个堆，
  而且排序是在数据库引擎里做的。所以「H2 上跑百万级、堆压到 256M」证明不了
  「引擎内存与数据量解耦」，它只证明了 H2 排序需要多少堆。想证明前者，必须让数据库是**独立进程**。
- **错误路径的表现是对的**：单个分片 OOM 被当成该分片执行失败 → 按 `options.maxAttempts` 重试 →
  仍失败则标记分片 FAILED、任务落到 `PARTIAL_FAILED`，进程没有崩、其他分片结果照样落库。
  压测里顺手验证了一次故障隔离，这也是分片被设计成"重跑单位"的原因。

## 三、MySQL（独立进程）实测：2M×2 真对账、堆压 256M

第二节的结论决定了必须换独立数据库进程，所以本机装了一份 MySQL 8.0.29（免安装包、端口 3307、
跟被测 JVM 是不同的进程），把同样的问题重新回答了一遍。**这一节的每个数字都能用仓库里的装置重跑
出来**，装置原样入库在 [`tools/mysql-bench/`](../tools/mysql-bench/)（本轮原始日志含 EXPLAIN ANALYZE
全文，体积太大没进仓库）。

### 装置与数据

| 项 | 值 |
| --- | --- |
| 数据库 | MySQL 8.0.29（独立进程，3307 端口，`bench` 库，utf8mb4 / utf8mb4_0900_ai_ci） |
| 被对账表 | `bill_left` / `bill_right` 各 1,999,900 行（每表数据 967 MB + 索引 200 MB，51 列） |
| 旁证大表 | `t50` 1,000 万行（数据 4.2 GB + 索引 2.1 GB），用来做排序/聚合/分片/分区/倾斜的纯 SQL 对照 |
| 引擎 | `recon-lens-0.1.0.jar` + `--spring.profiles.active=mysql`，`-Xmx256m`，G1 |
| 规则 | 同一份数据、同一套比较规则，只变化分片谓词与投影宽度（`tools/mysql-bench/rules/s7-*.yml`） |

### 3.1 结果对不对：引擎输出与"埋进去的真值"逐条对得上

四个口径（1 分片窄投影 / 1 分片宽投影 / 8 分片函数谓词 / 8 分片桶列）输出的差异完全一致：

| 差异类型 | 条数 | 严重度 | 它是怎么来的 |
| --- | --- | --- | --- |
| `AMOUNT_MISMATCH` | 199 或 200 | HIGH | 右侧 `id % 2000 = 0` 的行金额 +1；200 个键里有 1 个被下面的重复键挤掉 |
| `STATUS_MISMATCH` | 100 或 99 | MEDIUM | 同键两侧 `status` 真的不同（键 `ORD000001700000`…`170000099`） |
| `MISSING_RIGHT` | 101 | HIGH | 右侧整条缺失 100 条 + 1 条键被改写的孤儿行 |
| `MISSING_LEFT` | 100 | HIGH | 左侧整条缺失 100 条 |
| `DUPLICATE_KEY` | 1 | MEDIUM | 埋的改写是"右侧 `id=j` 的 `order_no` 换成 `id=j-1` 的键"，所以只有一个键 `…1700000` 真重复 |
| **合计** | **501** | | |

两个必须说清楚的细节：

- **199/200 与 100/99 的摆动不是 bug，是"同键多行取哪一行做比较"没定义**：重复键 `…1700000` 在右侧
  有两行，取哪一行去比 `amount` / `status` 由取数顺序决定 —— 窄投影给出 199+100，宽投影给出 200+99；
  总数、`MISSING_LEFT/RIGHT`、`DUPLICATE_KEY` 都不变。**如果业务要求这个数字稳定，得先把"重复键取数
  规则"定下来**，这是本次压测最值得带回去的产品问题。
- 单分片与 8 分片的差异分类逐条一致：第一节"H2 上分片不影响结果"的结论，在独立数据库上同样成立。

### 3.2 堆：`-Xmx256m` 下把 2M×2 跑了四遍，一次 OOM 都没有

一次应用进程里跑完 1 次热身 + 4 个口径（`-Xlog:gc`，87.5 s 墙钟、累计扫描 4×2×2,000,000 行）：

| 指标 | 值 |
| --- | --- |
| GC 事件 | 991 次（984 次 `Pause Young (Normal)`、2 次元数据阈值触发的 Young、2 次 Remark + 2 次 Cleanup + 1 次预防式回收） |
| Full GC | **0** 次（也没有 `To-space Exhausted`） |
| 单次 GC 前堆峰值 | 181 MB（上限 256 MB） |
| 单次 GC 后堆 | 2 ~ 38 MB |
| GC 停顿合计 / 最长单次 | 1,436.6 ms / 5.9 ms |

形态就是锯齿：堆到 180 MB 上下就被回收回 30 MB 左右，**跟扫了多少行没关系** —— 这才叫"内存与数据量
解耦"。对比第二节：同一个 256 MB 堆在 H2 上，连百万级的单分片都直接 OOM。

### 3.3 分片谓词怎么写才快：同一份数据、同为 8 分片，差 2 倍

| 8 分片口径（并行度 8，都是 501 条差异） | 单分片读多少行 | 墙钟（连跑多次，池 24） |
| --- | --- | --- |
| `shard_bucket = {index}`：桶列是普通列 + `(shard_bucket, order_no, amount, status, id)` 复合覆盖索引 | 260,000 | **1,083 / 1,084 / 1,085 / 1,086 / 1,159 ms** |
| `MOD(CAST(SUBSTRING(order_no,10,2) AS UNSIGNED),8) = {index}`：函数谓词 | 1,999,900（每片读全表） | 2,106 / 2,295 / 2,311 / 2,329 ms |

计划对照（`tools/mysql-bench/23_s7_engine_sql_plans.sql` 拿**引擎真正发出去的 SQL** 去 EXPLAIN ANALYZE）：

```
-- 函数谓词：谓词只能在派生表外面逐行过滤，每片都读完 199.99 万行
-> Filter: ((cast(substr(bill_left.order_no,10,2) as unsigned) % 8) = 3)  (actual time=6.201..428.539 rows=260000 loops=1)
    -> Covering index scan on bill_left using idx_s7_cover  (actual time=0.042..303.605 rows=1999900 loops=1)
-- 桶列谓词：定位 + 有序 + 覆盖，一次拿到
-> Covering index lookup on bill_left using idx_s7_shard_cover (shard_bucket=3)  (actual time=0.024..46.458 rows=260000 loops=1)
```

两个不太直觉的点：

- **引擎把规则 SQL 包成了 `SELECT * FROM (<规则里的 sql>) src WHERE <分片谓词> ORDER BY <keyField>`**，
  由此有两件事必须记住：
  - 分片谓词里用到的列必须出现在规则投影里，否则会报 `Unknown column`（本轮就是这么踩到的：
    改规则时漏了 `shard_bucket` 投影，8 个分片全报错）；
  - 函数化谓词**不会**被下推回基表去命中生成列/函数索引：同一个表达式直接查基表能被优化器替换成
    生成列（`type=ref, key=idx_shard_bucket, rows=2,513,520`，1.16 s），套一层派生表之后只能
    `type=index, key=idx_order_no, rows=9,744,530`（1.58 s）。
- **"桶列 + 排序键 + 投影列"合成一个局部覆盖索引**是分片提速的关键：单分片有序取数从
  `Index lookup ... idx_shard_bucket (actual time=0.032..223.525)` 变成
  `Covering index lookup ... idx_s7_shard_cover (actual time=0.029..92.824)`（同一脚本内的前后对照，
  2.4 倍、且不再需要 filesort）；引擎级同一口径从 2,389 ms 降到 1,083~1,159 ms。

### 3.4 一个更反直觉的结论：投影宽度会直接换掉执行计划

同一份数据、同一个规则，只把投影从 4 列换成 51 列（`table: bill_left` 让引擎生成 `SELECT *`）：

| 口径 | 计划（EXPLAIN） | 墙钟 |
| --- | --- | --- |
| 4 列 + `ORDER BY order_no` | `type=index, key=idx_s7_cover, Extra=Using index`（覆盖索引有序读，无排序） | 6,367 / 6,482 ms |
| 51 列 + `ORDER BY order_no` | `type=ALL, Extra=Using filesort`（全表扫 + 文件排序） | 67,268 / 67,593 ms |

**10.6 倍。** 多取 47 列的代价不是"多传 47 列"，而是优化器从"覆盖索引顺序读"退化成"全表扫 + filesort"。
（H2 上的同口径对照是 4.3 s vs 37.4 s，同一个机制。）所以规则 SQL 里的 `SELECT *` 是压测时必须盯的一行。

### 3.5 默认连接池 16 会在 8 分片 + 并行度 8 时饿死（默认值已改）

第一轮跑（`maximum-pool-size: 16`）8 个分片全在等连接：

```
分片 2 第 1/2 次执行失败: CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
  | 根因 SQLTransientConnectionException: HikariPool-1 - Connection is not available,
    request timed out after 10004ms (total=16, active=16, idle=0, waiting=7)
```

原因很直接：**每个分片同时持有左右两个游标**，并行度 8 就是 16 条连接，恰好把池占满，写差异 /
写分片状态再要连接只能等到超时。同一个 jar、同一份数据、同一个覆盖索引下的对照：

| 8 分片口径 | 池 24 | 池 16 | 差 |
| --- | --- | --- | --- |
| 函数谓词 | 2,329 ms（饥饿告警 0 条） | 13,992 ms（5 条饥饿告警） | 6.0× |
| 桶列 | 1,083 ms（0 条） | 11,928 ms（含在上述 5 条里） | 11.0× |

**最危险的地方是结果仍然全对（501 条差异一条不差），只是慢一个数量级** —— `options.maxAttempts`
的重试把问题藏起来了，只有日志会告诉你。所以默认值已从 16 调到 24，注释写在 `application.yml`：
至少 `2 × recon.engine.parallelism`，再加写库与 HTTP 的余量。

### 3.6 跑 MySQL 才暴露出来的 5 个真问题（5 个已修）

| # | 问题 | 症状 | 处置 |
| --- | --- | --- | --- |
| 1 | `JdbcRowCursor` 打开游标失败时漏了 `Connection` | 连接池被打满，后续分片全部重试 | 已修 + `JdbcRowCursorTest` |
| 2 | `ReconRepository.finishJob` 落库前不截断 `error_msg`（`markShardFailed` 截了） | MySQL 报 `Data too long for column 'error_msg'`，`POST /api/jobs` 直接 500、任务状态没落库 | 已修（截到 1000 字符；失败原因最多拼 3 条 + `共 N 个分片失败`）+ 回归测试 |
| 3 | 默认连接池 16 = 8 分片 × 2 游标 | 见 3.5 | 默认值调到 24 |
| 4 | `RuleCatalog` 对"库里已存在"的规则码不再覆盖 | 改了 `rules/` 里的 YAML 不生效，一直报 `Unknown column 'shard_bucket'`（库里还是旧版本） | 压测时先 `DELETE FROM recon_rule WHERE code LIKE 'S7%'` 再重启；**产品行为已定**：种子按文件覆盖同名规则（内容一致不动、不一致落新版本、已下线的不复活、`recon.seed-rules=false` 可关），见 `RuleCatalog.seedFromRuleDirectory()` 与 `RuleCatalogTest` 的两个新用例 |
| 5 | `schema.sql` 用了 `CREATE INDEX IF NOT EXISTS` | 这句 MySQL 不支持，而 README 又让 DBA "照 `schema.sql` 建表" —— 照着跑会停在这两行 | 已补 `src/main/resources/schema-mysql.sql`（索引写进建表语句，MySQL 8.0.29 上从空库连跑两遍验证通过） |

### 3.7 附：千万行大表上的纯 SQL 侧对照

| 场景 | 写法 A | 写法 B / 处置 |
| --- | --- | --- |
| 全表排序（1000 万行） | `ORDER BY id`（物理有序）**10.75 s**，无排序算子 | `ORDER BY order_no` 窄行 filesort **8.32 s**；`SELECT *` 宽行 **37.74 s**；派生表达式键（`concat(customer_id,'|',status)`）**56.73 s** |
| 同一个 `ORDER BY order_no`，取 3 列 | 普通索引 `idx_order_no` 建好后仍走 `Table scan + Sort`：**7.09 s** | 覆盖索引 `(order_no, amount, status)` 后 `Covering index scan`：**2.19 s**（手工验证"覆盖"二字值 3 倍） |
| 表达式分组的聚合 | `GROUP BY` 纯列 + 同列 `ORDER BY`（有索引）**13.72 s**，不落临时表 | 表达式键 G≈15 万 **7.37 s**、G≈100 万 **48.96 s**、G≈1000 万 **52.62 s**（都落临时表）；把 `temptable_max_ram` 从 1 GB 压到 128 MB 后 G≈100 万涨到 **69.82 s** |
| 8 分片各片 `COUNT(*)` | 函数谓词 8 片：1.22 ~ 2.77 s/片 | 生成列桶 + 索引后 **0.145 ~ 0.160 s/片**（8~12 倍）；分片均衡度 max/avg = **1.040** |
| 分区表的裁剪 | 裁到单月 + `ORDER BY order_no`：**2.10 s**（只扫 708,163 行） | 不裁剪：**49.95 s**；同样的"单月"谓词打在非分区表上是 **26.05 s**（全表扫 1000 万行） |
| 数据倾斜 | 值域等宽 8 段：最重段（868 万行）**51.39 s**，最轻段 **0.88 s** | `MOD(tenant_id,8)` 哈希桶 max/avg = **4.149**；按采样定边界在本例中失效（8 个分位点几乎全落在同一个 `tenant_id` 上：`b1..b7 = 1,1,1,2,2,3,170`）；按这几个分位点切出的 8 段真实行数是 4,999,999 / 0 / 0 / 2,000,000 / 0 / 1,000,000 / 750,021 / 1,249,980 —— max/avg = **4.00** 且留下 **3 个空段**，最重段取 5M 行 + `ORDER BY order_no` 仍要单独跑 **12.01 s**（`18_s6b_sampled.sql`） |
| 表达式替换的边界 | `MOD(CAST(SUBSTRING(order_no,10,2) **AS UNSIGNED**),8)` 与生成列定义一致 → `type=ref, key=idx_shard_bucket, rows=2,513,520`：**1.16 s** | 只把类型换成 **`AS SIGNED`**：`type=index, key=idx_order_no, rows=9,744,530`：**1.58 s**（计数器上这一格差 **7.7 倍读放大**：命中桶列时 `Innodb_rows_read` 只涨 1,300,000，替换失败时要涨 10,000,000，见 3.8） |

### 3.8 附：状态计数器的可归因增量

`EXPLAIN ANALYZE` 回答"每个算子花了多久"，**状态计数器**回答"读了多少行、排了多少行、落了几次临时表"。两者放在一起，才能把"为什么慢"落到机制上（这一节就是 3.1~3.7 各条结论的计数器证据）。

读法（很重要）：`SHOW GLOBAL STATUS` 是**累计值**，`FLUSH STATUS` 只清会话计数器 ⇒ 只有"两张状态表相减"才是某条语句的增量。而每个场景文件的头部都跑过 `ALTER TABLE ADD INDEX` / `ANALYZE TABLE` / `CREATE TABLE ... SELECT`，这些开销会落进该文件的**第一张**状态表，首块因此不可归因。所以这一节的数字来自两处：

- `24_counter_probe.sql`：文件内**没有任何 DDL**，把各场景的"首块"重测一遍，逐块可归因（跑法见下面的复现清单）；
- 各场景文件**第 2 块起**的差值（两块之间只有一条 `EXPLAIN ANALYZE`）。

两点读数约定：每块都会固定 `Created_tmp_tables +1`（`EXPLAIN`/`SHOW` 自身的基线噪声），所以下面表里只写**减掉噪声后**的值，判别落盘看 `Created_tmp_disk_tables`；`Innodb_rows_read` 是 InnoDB 交给上层的行数（回表、索引扫描都算），它比墙钟稳定，适合做归因。

**排序与投影**（同一张 1000 万行表）：

| 口径 | 根算子 | ΔInnodb_rows_read | ΔSort_rows | ΔSort_merge_passes | 墙钟 |
| --- | --- | --- | --- | --- | --- |
| `ORDER BY id`（物理有序） | `Index scan on t50 using PRIMARY` | 10,000,000 | **0** | 0 | 11.24 s |
| 覆盖索引 `ORDER BY order_no` 取 3 列 | `Covering index scan on t50 using idx_cov` | 10,000,000 | **0** | 0 | 1.42 s |
| 同上但多取一列 `fee`（覆盖被破坏） | `Sort: t50.order_no` ← `Table scan` | 10,000,000 | 10,000,000 | 645 | 7.79 s |
| 窄行 `ORDER BY order_no` 取 3 列（无覆盖索引） | `Sort: t50.order_no` ← `Table scan` | 10,000,000 | 10,000,000 | 581 | 8.32 s |
| `SELECT *`（50 列）同一个排序键 | `Sort: t50.order_no` ← `Table scan` | 10,000,000 | 10,000,000 | **2,651** | 37.74 s |
| 派生表达式键 `concat(customer_id,'\|',status)` | `Sort: concat(...)` ← `Table scan` | 10,000,000 | 10,000,000 | **2,666** | 56.73 s |

读法：**读的行数完全一样，差别全在 `Sort_merge_passes`**。同样排 1000 万行，窄行 581 趟归并、50 列宽行 2,651 趟、派生表达式 2,666 趟 —— 排序缓冲按行宽切，行越宽每趟能装的行越少，归并趟数就上去了。这也是"投影宽度会换掉执行计划"（3.4）在计数器上的样子：覆盖索引那一行 `ΔSort_rows = 0`，排序压根没发生。

**临时表与分组基数**（`GROUP BY` 表达式键，G = 分组基数）：

| 口径 | ΔCreated_tmp_tables | ΔCreated_tmp_disk_tables | ΔSort_rows | ΔInnodb_rows_read | 墙钟 |
| --- | --- | --- | --- | --- | --- |
| G≈15 万，**键有索引**（流式分组） | 0 | 0 | **0** | 10,000,000 | 13.56 s |
| G≈15 万（表达式键） | 1 | 0 | 50,000 | 10,000,000 | 7.37 s |
| G≈100 万（表达式键） | 1 | **1** | 1,000,000 | **20,000,000** | 48.96 s |
| G≈1000 万（表达式键） | 1 | **1** | 10,000,000 | **20,000,000** | 52.62 s |
| G≈100 万，`temptable_max_ram` 压到 128 MB | 1 | **1** | 1,000,000 | 20,000,000 | 69.82 s |
| G≈15 万，同样 128 MB 限额 | 1 | 0 | 50,000 | 10,000,000 | — |

读法：键上有索引时是**流式分组**（临时表 0、排序 0）；换成表达式键就必须走临时表，`G` 一过内存限额就 `Created_tmp_disk_tables = 1`（落盘），`Innodb_rows_read` 也从 10M 变 20M —— 表达式键让优化器放弃索引，变成"全表扫一遍、临时表再读一遍"。把 `temptable_max_ram` 从 1 GB 压到 128 MB 并没有多出一次落盘（都还是 1 次），墙钟却从 48.96 s 涨到 69.82 s。

**分片与分区裁剪**：

| 口径 | 根算子 | ΔInnodb_rows_read | ΔSort_rows | 墙钟 |
| --- | --- | --- | --- | --- |
| 单分片 `COUNT(*)`，函数谓词 `AS UNSIGNED`（与生成列定义一致） | `Aggregate` ← `Index lookup ... idx_shard_bucket` | **1,300,000** | 0 | 1.49 s |
| 同一查询改成 `AS SIGNED`（同长度、类型不同 ⇒ 替换失败） | `Aggregate` ← `Filter` ← `Covering index scan ... idx_order_no` | **10,000,000** | 0 | 1.79 s |
| 桶列单分片取数：`WHERE shard_bucket = 0 ORDER BY order_no` | `Sort` ← `Index lookup ... idx_shard_bucket` | 1,300,000 | 1,300,000 | 5.31 s |
| 引擎真机：建分片局部覆盖索引后的分片取数 | `Using index`（无 filesort） | **260,000** | 0 | 6.37 s 级 |
| 分区表裁剪到单月 + `ORDER BY order_no` | `Sort` ← `Filter` ← `Table scan on t50p` | **708,163** | 656,475 | 2.07 s |
| 同一个"单月"谓词打在非分区表 `t50` 上 | `Sort` ← `Filter` ← `Table scan on t50` | **10,000,000** | 656,475 | 12.95 s |

读法两句话：**分区裁剪不是"跑得更快"，是"读得更少"** —— 最后两行的排序量一模一样（656,475 行），`Innodb_rows_read` 是 708,163 vs 10,000,000（14.1 倍）。**表达式替换失败也不是"多算一点"** —— 输出都是 130 万行，`AS SIGNED` 必须先扫完 1000 万行的 `idx_order_no` 再逐行 `Filter`（7.7 倍读放大）；注意那 10M 行是**覆盖索引扫描**而不是全表读，所以墙钟只差 0.3 s（1.49 s vs 1.79 s），差距在 IO 上，冷缓存或小缓冲池下会放大。

**不能归因的块**（写出来是为了让数字可复核）：

- `S4-2` / `S4-3`：块内各有 8 条 `EXPLAIN ANALYZE`（8 个分片），两块之间不止一条语句；
- `S4-5`：它与上一张状态表之间隔着 `S4-4` 的两条普通 `SELECT`（按桶分组的全索引扫，2 × 1000 万行），那一行 21,300,000 里绝大部分是 S4-4 的开销（`S4-6` 与 `S4-5` 之间只有一条语句，干净：1,300,000）；
- `17_s4b_substitution.sql`：`S4b-2` 是物化生成列 + 建索引的 DDL 且没有状态表，之后各块整体错位一格 ⇒ 该文件的**计时**结论（1.16 s vs 1.58 s）保留，**计数器**改用本节的 `PROBE-8/9`；
- 每个场景文件的**首块**：头部 DDL 的开销混在里面（`24_counter_probe.sql` 复测替代）；
- `16_s6_skew.sql`：本来是纯计时文件，块里没有 `SHOW GLOBAL STATUS`，因此没有计数器。

### 3.9 附：先排序只取主键、再按主键回表（late row lookup）值不值

对账取数最常被提出来的一个"优化"：**排序阶段只取主键**（排序输入变窄、更省内存），明细等用到时再按主键一条条取回来。这是标准的 late row lookup / deferred join，`25_late_lookup.sql` 在同一张 1000 万行表上把三个形态跑在一起（文件内零 DDL，逐块可归因）：

| 形态 | 根算子 | ΔHandler_read_key | ΔInnodb_rows_read | ΔSort_rows | 墙钟 |
| --- | --- | --- | --- | --- | --- |
| 只排序取主键（全量 1000 万行） | `Covering index scan on t50 using idx_order_no` | 11（≈0，基线噪声） | 10,000,000 | **0** | 1.06 s |
| 单阶段：排序 + 取 4 列，`LIMIT 200000` | `Limit` ← `Covering index scan ... idx_cov` | 11 | 200,000 | 0 | 0.038 s |
| 两阶段：物化 20 万主键 + 20 万次主键回表 | `Nested loop inner join` → `Single-row index lookup on t using PRIMARY (id=k.id)` × 200,000 | **200,011** | 400,000 | 0 | 0.32 s |
| 单阶段：同上但 `LIMIT 2000000` | `Limit` ← `Covering index scan ... idx_cov` | 11 | 2,000,000 | 0 | 0.38 s |
| 两阶段：物化 200 万主键 + 200 万次主键回表 | 同上，lookup `loops = 2000000` | **2,000,011** | 4,000,000 | 0 | 2.91 s |
| **回表的下限**：2,000,000 行按主键顺序顺序读聚簇行（无随机 IO、无往返，分批 `IN` + MRR 能做到的最好情况） | `Index scan on t50 using PRIMARY` | 11 | 2,000,000 | 0 | 0.63 s |
| **回表的下限**：全量 10,000,000 行同样按主键顺序读 | `Index scan on t50 using PRIMARY` | 11 | 10,000,000 | 0 | 4.37 s |

（同一次完整跑的数字；同一文件跑两遍，逐块差别在 ±15% 内。）

逐条结论：

1. **"排序只取主键"这一半确实便宜**：`ORDER BY order_no` 只取 `id` 时，1000 万行 1.06 s 且 `Sort_rows = 0` —— 因为 InnoDB 二级索引的叶子天然带主键后缀，`idx_order_no` 的叶子本来就是 `(order_no, id)`，连排序算子都不出现。
2. **回表那一半把便宜全部吃掉**：同样 200 万行，单阶段覆盖索引 0.38 s，两阶段 2.91 s（**7.6 倍**；20 万行那对是 0.038 s vs 0.32 s）。计数器上多出来的是 `Handler_read_key = 2,000,011`（每个主键一次随机取行）与翻倍的 `Innodb_rows_read`（先读索引项、再读聚簇行）。按每行 1.46 µs 线性外推，全量 1000 万行是 14 s 级。
3. **即使把回表优化成"零随机 IO"，仍然打不过覆盖索引**：表里最后两行是这条路的天花板——直接把聚簇行按主键顺序顺序读（无随机取行、无往返，等价于分批 `IN` + MRR 能做到的最好情况），200 万行 0.63 s、1000 万行 4.37 s，还是比覆盖索引（0.38 s / 1.06 s）慢 **1.7 倍 / 4.1 倍**。原因是覆盖索引只读索引项（字节数本就少得多）且**从不碰聚簇行**，而两阶段方案无论如何都要把整行读一遍。
4. **"分批取"本身省不了往返**：引擎现在的 `recon.engine.fetch-size` 默认就是 **2000**（`application.yml:35`、`application-mysql.yml:26`），`JdbcRowCursor` 用 `setFetchSize(2000)` 流式取数——1000 万行本来就是 5000 次往返。把游标改成"内存里 2000 个主键一批"并不减少往返次数，只是把"每次取回 2000 行顺序扫的结果"换成"2000 个主键各做一次随机取行"（外加服务端物化一份主键列表）。
5. **它还会把内存重新变成 O(数据量)**：1000 万个主键用 `long[]` 存是 76 MB，1 亿行 763 MB——超出 `-Xmx256m` 那套"内存与数据量解耦"的前提（3.2：峰值 181 MB、0 次 Full GC）；左右两侧、多分片并行还要叠加。另外两阶段返回的行是主键序，得按内存中的位置重排后才能继续归并，等于把顺序保证从数据库搬到应用层自己维护。
6. **MySQL 自己就是这么判断的**：同一个"投影里多一列索引外字段"的查询，加 `LIMIT 100000` 时优化器选 `type=index, key=idx_order_no`（**索引序 + 逐行回表**，111 ms）；把 `LIMIT` 去掉（对账要全量）它立刻改选 `type=ALL ... Using filesort`（全表扫 + 排序，7.79 s）。也就是说这条路优化器**只在"只要前几行"时才走**；取全量时它算过账，不划算。

**结论：对"取前 N 行"（预览、分页、抽样）划算；对"全量流式对账"不划算。** 全量对账的正确方向是反过来的 —— **让索引把对账要的列全带上**：覆盖索引 1.42 s；按分片建的局部覆盖索引只读该分片 260,000 行。

数字之外还有三个工程理由：

- **顺序**：引擎按业务键升序**流式**归并（`JdbcRowCursor`，游标 + `fetchSize`）。两阶段的第二阶段按主键取回，行序是主键序，要么在内存里按 `id → row` 缓存重排（1000 万行的映射会把 `-Xmx256m` 撑爆，与 3.2"内存与数据量解耦"直接冲突），要么每批再排一次 —— 而"每批再排一次"就等于把省下的排序又加回来。
- **连接与并发**：每个分片多一轮往返、游标占用时间更长（3.5 里池的余量正是被这类占用吃掉的）；8 个分片同时做随机回表，IO 互相打断，比顺序扫更吃亏。
- **本机环境对回表最有利**：`innodb_buffer_pool_size = 8G` 基本能装下 4.2 GB 数据 + 2.1 GB 索引，这些回表大多命中内存；真实库的缓冲池占比没这么高，回表的代价只会更大。

### 3.10 附：对账前的预处理（把汇总物化成模型表）值不值

前面几节测的都是"每一轮对账要付多少钱"。这一节测另一种做法：**把规则已经声明过的汇总口径提前算一次、落成一张窄的模型表**，之后每轮对账只读这张表。`26_prereq_model.sql` 在同一张 1000 万行宽表上跑完整对照（该文件块内是 DDL，因此用 `SET @t0 := NOW(6)` 自带计时；状态计数器仍逐块可归因）：

| 块 | 干什么 | 墙钟 | 关键计数器（Δ） |
| --- | --- | --- | --- |
| M5 | **现状：每轮**在原始宽表上 `GROUP BY CONCAT(customer_id,'|',status)` + `ORDER BY biz_key`（引擎现在生成的 SQL） | **21,473 ms** | `Handler_read_next` +10,000,000、`Innodb_rows_read` +10,000,000、`Sort_rows` 0、临时表 0（`idx_biz_key_gen` 生成列索引让它流式分组） |
| M1 | **预处理一次**：`CREATE TABLE AS SELECT ... GROUP BY`（G≈5 万） | **19,125 ms** | `Innodb_rows_read` +10,000,000 |
| M2 | 预处理一次：给模型表加主键（聚簇）+ 分片生成列与索引 | **278 ms** | `Innodb_rows_read` +50,000 |
| M3 | **每轮**：单分片读模型表（引擎包裹形态 `WHERE shard_bucket=3 ORDER BY biz_key`） | **15.3 ms**（扫 6,272 行） | `Handler_read_next` +6,272、`Sort_rows` 0 |
| M4 | **每轮**：不分片全量读模型表 | **7.8 ms**（扫 50,000 行） | `Handler_read_next` +50,000、`Sort_rows` 0 |
| M6 | 预处理一次：高基数维度（`acct_id+status`，G≈100 万）建模型表 | **55,081 ms** | `Innodb_rows_read` +20,000,000、`Created_tmp_disk_tables` +1（分组落盘） |
| M7 | 预处理一次：高基数模型表加主键 + 分片索引 | **3,974 ms** | `Innodb_rows_read` +1,000,000 |
| M8 | **每轮**：单分片读高基数模型表 | **296 ms**（扫 124,998 行） | `Handler_read_next` +124,998、`Sort_rows` 0 |

规模对照：`t50` 950 万行 / **4,229 MB 数据 + 2,133 MB 索引**；`t50_model` 50,000 行 / **5 MB + 2 MB**；`t50_model_hi` 1,000,000 行。

逐条结论：

1. **低基数维度：第 2 轮就回本，之后每轮快千倍级。** 预处理一次 19.13 s（就是现状每轮要重付的那 19~21 s）+ 0.28 s 建索引；之后每轮 7.8 ms（全量）～15.3 ms（单分片）。跑 2 轮：19.4 + 0.016 = **19.4 s vs 现状 42.9 s**。
2. **高基数维度收益小得多，但仍然是 20 倍级。** 汇总粒度直接决定模型表行数：G≈100 万 → 模型表 100 万行，预处理 55.1 s + 4.0 s，之后每轮约 2.4 s（8 片 × 296 ms），而同口径现状每轮是 48.96 s（3.6 的 S3-2）。**模型表的行数 = 维度基数，不是源表行数**，这是全部收益的来源。
3. **它省的是"每轮都要重付的源侧扫描 + 汇总"，不是排序。** M5 之所以没出现排序，是因为这张表上刚好有 `idx_biz_key_gen` 生成列索引让分组流式完成；换成没有生成列索引的表达式维度（3.4 的 S3-2），现状每轮就是 48.96 s 且分组落盘。反过来说：**预处理也顺带把"物理形态"设计对了** —— 模型表上主键就是业务键（`ORDER BY biz_key` 变成索引序，`Sort_rows = 0`），分片列是带索引的生成列（分片谓词必然命中，3.3 的坑在建模阶段就消除了）。
4. **顺手解决两件事**：① 行宽 —— 模型表只留业务键 + 要比较的列 + 分片列，`SELECT *` 也接近覆盖索引；② 分片谓词 —— 它是我们建的列，不是用户 SQL 里那句 `MOD(CAST(SUBSTRING(...)))`。
5. **语义边界（最重要的一条）**：预处理**只能物化规则已经声明的粒度**，不能替应用层决定汇总口径。行级对账规则（没有 `measures`）物化后仍然是行级的 —— 不汇总、只是窄化 + 聚簇，收益来自行宽和排序序，而不是行数。谁做汇总：**在源库侧 SQL 里做**（模型表就是规则投影 SQL 的物化），而不是把 4.2 GB 拉回应用再汇总。
6. **新增的失败模式是"新鲜度"，不是性能**：源数据变了而模型表没重建，对账会安静地基于旧数据给出"没有差异"。所以模型表必须带构建时间/水位、规则版本，任务里要记录用的是哪一版模型；规则改了版本，模型必须失效重建。存储与权限也要算账 —— 模型表是源数据的又一份副本（只留对账要的列，别顺手把 `remark`、`extract_ref` 这类也带上）。

**结论：值得做，但形态是"把规则声明的投影/汇总物化"，不是"预处理成报表"。** 高频重跑（日报/小时报）、源表宽、源侧要汇总或排序、源库有低峰窗口的场景收益最大；一次性对账、规则或映射常改、需要"当前最新"的场景就别做。

#### 3.10.1 落到引擎里（`recon_build_model`）：三处与压测装置不同的选择

上面的对照是"最优形态"（M2 建的是主键，读的时候按物理顺序扫）。落进引擎时有三处**故意不一样**，代价与理由都记在这里：

1. **不建主键，只建普通索引**（`idx_..._key(biz_key)` + `idx_..._bucket(biz_key_bucket)`）。理由是正确性：行级规则本来就允许重复业务键（重复要留给引擎报 `DUPLICATE_KEY`），聚合维度也可能出现 NULL，加主键会让构建阶段直接失败，而对账结果不依赖模型表唯一。代价是读侧从"聚簇顺序扫"变成"二级索引扫 + 每行一次回表"（3.9 的结论：只有覆盖索引能消掉这次回表）——模型表窄，通常无所谓；模型表真到千万行级时要记得把要比较的列并进索引。
2. **分片桶用生成列 `biz_key_bucket`**（MySQL `CRC32(...) % n`，H2 `MOD(ORA_HASH(...), n)`），规则里两侧的分片条件都写成 `biz_key_bucket = {index} AND biz_key_bucket < {shards}`。**两侧必须用同一个桶函数**，否则同一个业务键在两侧落到不同分片，会凭空多出一堆单边缺失差异。
3. **H2 的 `ORA_HASH(expr, n)` 是 0..n 共 n+1 个值**（2.2.224 与 2.4.240 都一样），所以必须取模。这是本轮实测抓到的真 bug：用 20 个真实订单号，`ORA_HASH(k,4)` 的分布是 `0=7 1=3 2=3 3=4`**`4=3`** —— 落在第 4 个桶的 3 行**任何分片都取不到**，读模型表时 `leftRows=17`（基线 20），而且**一条差异都不报**；改成 `MOD(ORA_HASH(k,4),4)` 后分布 `0=10 1=3 2=3 3=4`，20 行全覆盖。教训：分片桶算错不会报错，只会安静地少对一部分数据，所以模型表用例必须断言**行数 + 差异分类**两边都对得上，不能只断言"建出来了"。
4. **H2 的索引名是库级唯一的**（`ALTER TABLE ... RENAME TO` 不会改索引名，旧名仍被线上模型表占着），所以每次构建的索引名带一个自增序号；MySQL 的索引名只在本表内唯一，多这个后缀无害 → `Index "idx_..._key" already exists` 是第二次构建才会炸的坑。

模型规则 code 用大写后缀 `<CODE>__MODEL`（`RuleParser` 的 code 规则是 `[A-Z][A-Z0-9_]*`）；`recon_model` 表登记 `rule_version / dimension / aggregated / shards / row_count / build_ms / created_at`，第 6 条说的"新鲜度"就是靠它看的（自动失效还没做，见 README 后续计划）。

#### 3.10.2 在真实 MySQL 上验证引擎实现：行级明细规则别建模型表

3.10.1 记的是"选择"，这一节记**引擎真跑在 MySQL 上的实测**（MySQL 8.0.29 / `bench` 库 / `bill_left` = `bill_right` = 1,999,900 行；`java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mysql`，全部走 HTTP 接口）。DBA 路径先验一遍：`src/main/resources/schema-mysql.sql` 对**已有数据的库**连跑两遍，只新增出 `recon_model`，其余 `CREATE TABLE IF NOT EXISTS` 全是空操作（幂等 ✓）。

两种规则形态，同样 200 万行，结论完全相反：

| 规则形态 | 原规则每轮 | 建模型表一次 | 模型规则每轮 | 差异是否一致 |
| --- | --- | --- | --- | --- |
| **行级明细** `S7_NARROW`（两侧各扫 200 万行，只比对 amount/status） | **5 s**（1 分片） | **28.6 s**（左 14,167 ms + 右 14,096 ms，模型表 2 × 1,999,900 行） | **12 s**（4 分片） | 一致：501 条 = `AMOUNT_MISMATCH 199 + STATUS_MISMATCH 100 + MISSING_LEFT 100 + MISSING_RIGHT 101 + DUPLICATE_KEY 1`，两侧任务的 biz_key 集合互差为 0 |
| **汇总口径** `CUSTOMER_SUM_RECON`（两侧 SQL 各自 `GROUP BY customer_id` 扫 200 万行） | **3.6 s**（2 分片） | **5.42 s**（左 2,605 ms + 右 2,677 ms，模型表 2 × 50,000 行） | **0.16 s**（4 分片） | 一致：100 条 `AMOUNT_MISMATCH`，集合互差为 0 |

读法：**收益来自"每轮都要重付的源侧扫描 + 汇总"，与"规则是不是行级"无关，与"物化后的行数比源表小多少"有关。** 行级明细规则物化后行数不变（2,000,000 → 1,999,900），只是把行复制成一份窄表，建一次要 28.6 s，之后每轮还更慢（12 s vs 5 s：多了读模型表 + 4 片调度）——**这种情况不要建模型表**。汇总口径反过来：模型表行数 = 维度基数（50,000），建一次 5.42 s，第 2 轮起回本，之后每轮 0.16 s（这个数里还包含 HTTP 与任务登记开销，单看扫描是毫秒级）。

MySQL 侧的物理形态与设计一致（`SHOW CREATE TABLE`）：

```sql
`biz_key_bucket` smallint GENERATED ALWAYS AS ((crc32(coalesce(cast(`biz_key` as char charset utf8mb4),_utf8mb4'')) % 4)) STORED,
KEY `idx_438d8fa3_1_key` (`biz_key`),
KEY `idx_438d8fa3_1_bucket` (`biz_key_bucket`)
-- 没有 PRIMARY KEY：行级规则允许重复业务键（重复要留给引擎报 DUPLICATE_KEY）
```

1,999,900 行在 4 个桶上的分布是 `499974 / 499975 / 499974 / 499977`，四个分片各自产出的差异数是 `116 / 117 / 133 / 135`——**四片都非 0**。这条断言值得写进用例：桶函数算错（见 3.10.1 第 3 条）不会报错，只会让某个分片安静地一条差异都没有。`recon_model` 里登记的两条记录与接口返回的 `GET /api/models` 也对上了（`row_count / build_ms / shards / created_by`）。

顺带一个口径提醒：`CUSTOMER_SUM_RECON` 的汇总写在源侧 SQL 里、没有声明 `measures`，所以 `recon_model.aggregated` 记的是 `0`、`dimension` 是 `NULL` —— 这个字段表达的是"**规则**是否声明了聚合/派生维度"，不是"源 SQL 是不是汇总 SQL"。手工写聚合 SQL 的规则照样能建模型表、照样有收益。

### 复现清单

```powershell
# 0) 起一个独立 MySQL（免安装包解压即可；本轮用的是 3307 端口 + mysql-bench.ini）
# 1) 建表：DBA 视角的 DDL 就是 src/main/resources/schema-mysql.sql；压测用的引擎表在
#    tools/mysql-bench/20_s7_engine_tables.sql + 21_s7_engine_ddl.sql
# 2) 造数（1000 万行大表 + 2M×2 对账表，本机几分钟到十几分钟）
.\tools\mysql-bench\run_all.ps1
.\tools\mysql-bench\run.ps1 -File 14_s4_shard.sql -Tag s4      # 单独跑某个场景

# 2b) 状态计数器（3.8）：这个文件里没有任何 DDL，逐块可归因；约 1~2 分钟
.\tools\mysql-bench\run.ps1 -File 24_counter_probe.sql -Tag 24_probe

# 2c) late row lookup 对照（3.9）：单阶段 vs 两阶段（物化主键 + 主键回表），外加"按主键顺序读"的下限；十几秒
.\tools\mysql-bench\run.ps1 -File 25_late_lookup.sql -Tag 25_latelookup

# 2d) 预处理物化对照（3.10）：现状每轮 vs 建一次模型表之后每轮；含 DDL，约 100 秒
.\tools\mysql-bench\run.ps1 -File 26_prereq_model.sql -Tag 26_model      # 建 t50_model / t50_model_hi 两张模型表
# 读这张表的日志（DDL 块自带 *_ms 计时）：python _mysql_bench/extract_model.py tools/mysql-bench/out/26_model.log

# 3) 引擎真机对账：自建 jar → 起应用（-Xmx256m）→ 跑 4 个口径 → 打印"连接饥饿证据"
.\tools\mysql-bench\_s7_rerun.ps1                                              # 池 24（默认）
.\tools\mysql-bench\_s7_rerun.ps1 -Pool 16 -Cases S7_NARROW_FUNC8,S7_NARROW_BUCKET8   # 复现 3.5
```

- 每个分片扫了多少行在 `recon_shard`（`scanned_left / scanned_right / matched_rows / diff_rows`），
  差异明细在 `recon_diff`，任务状态在 `recon_job`；四个口径跑的都是同一份数据。
- 覆盖索引实验：`22_s7_cover_index.sql`；引擎真实 SQL 的计划：`23_s7_engine_sql_plans.sql`。
- 读计数器：`FLUSH STATUS` 只清会话计数器，所以要把日志里**相邻两张** `SHOW GLOBAL STATUS` 相减；
  场景文件头部的 `ALTER/ANALYZE/CTAS` 会污染首块，首块用 `24_counter_probe.sql` 复测。
  批量提取：`python tools/mysql-bench/extract_counters.py`（只输出可归因块，并自动减掉每块固定 +1 的噪声）。
- **量级参考的小心话**：同一配置连跑 4 次的波动在 ±10% 以内，但隔一轮环境（比如刚建完索引、
  缓冲池被换过一轮）会有 ±25% 的系统性漂移。所以这一节只把**2 倍以上**的差距当结论，
  个位数百分比不当结论；`Bucket8` 建索引前后那组是同一脚本内的前后对照，不受这个漂移影响。

## 四、其他已知边界

- 单分片的比较成本与"同键组的重复条数"成正比，重复数据极多的键会成为热点。
- 差异写入是逐批 500 条 `batchUpdate`；MySQL 下配合 `rewriteBatchedStatements=true` 效果明显。
- 归因（尤其大模型归因）是独立的后置步骤，不影响对账本身的耗时；`ExplainService.explainBatch` 是按
  条数分批调用，批量大小由 `recon.llm.batch-limit` 控制。
- 检查点粒度是分片：任务中断后重跑的最小单位是一个分片，不会从半张表中间的某一行继续。
- **MySQL 建表脚本**：给 DBA 执行的是 `src/main/resources/schema-mysql.sql`（mysql profile 的
  `spring.sql.init.mode=never`，应用不会自动建表）；`schema.sql` 里那些 `CREATE INDEX IF NOT EXISTS`
  只服务 H2，MySQL 会直接报语法错。
- ~~**规则种子不覆盖同名规则**~~（已改）：`recon.seed-rules=true` 现在启动时会把规则目录里的 YAML
  与库里的**同名规则**对齐 —— 内容一致就什么都不做，不一致就按文件**覆盖**成新版本（追加版本，不删旧的），
  已被显式下线的 code 不覆盖也不复活；想让库完全自己说了算就设 `recon.seed-rules=false`。
  行为与用例见 `RuleCatalog.seedFromRuleDirectory()` 与 3.6 第 4 条。
- **极端倾斜下"采样定边界"会失效**：本轮 8 个分位点几乎落在同一个 `tenant_id` 上
  （`b1..b7 = 1,1,1,2,2,3,170`），分片大小完全不可控；倾斜数据要么按真实直方图分桶，
  要么把大租户单独拎出来跑。
- **量级参考的边界**：同一配置连跑 4 次在 ±10% 以内，但换一轮环境（如刚建完索引用掉过缓冲池）
  会有 ±25% 的系统性漂移 —— 差 2 倍以内不要当结论。
