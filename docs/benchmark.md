# 压测：百万级对账怎么跑、看到了什么

目标不是刷一个漂亮 QPS，而是回答三个具体问题：

1. **分片会不会改变结果？** 8 分片与 1 分片的差异必须逐条一致；
2. **引擎自己的内存占用跟数据量是什么关系？**
3. **分片谓词写成什么样才真的快？**

第 1、3 条在仓库里可以直接复现（下面有实测数字）；第 2 条的结论要分两种情况说清楚，
因为「嵌入式数据库」和「独立数据库」给出的答案完全不同 —— 这点很容易被压测报告糊弄过去。

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

> 这两条 SQL 需要 MySQL 8 才能验证，本机没有 MySQL 环境，**未实测** —— 这里只给出方案与条件。
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

## 三、要证明"流式不占堆"，用 MySQL（独立进程）这样跑

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS recon_lens DEFAULT CHARSET utf8mb4"
mysql -uroot -p recon_lens < tools/gen_bill_data.sql

# 生产 profile（MySQL），堆限制在 256M
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql \
    -Dspring-boot.run.jvmArguments="-Xmx256m -XX:+UseG1GC"

time curl -s -X POST http://localhost:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"ruleCode":"BILL_VS_SETTLE","batchId":"bench-1m","sync":true}'
curl -s 'http://localhost:8080/api/diffs/summary?jobId=1'
```

- `application-mysql.yml` 里的 `useCursorFetch=true` 是关键：不打开它，MySQL 驱动会把整个结果集
  拉进内存，`setFetchSize` 形同虚设 —— 这一条比调大堆更值得先检查。
- 用 VisualVM / JFR 看堆曲线：健康的形态是"锯齿状小幅波动"，而不是一条随扫描行数上升的斜线。
- MySQL 下每个分片的 `WHERE MOD(...)` 同样是全表扫描，按上面第一种写法换成键范围，
  再对比 1/4/8 分片的耗时，才能看到"接近线性"的加速。
- 想看分片内部：`recon_shard` 表按 `scanned_left / scanned_right / diff_rows` 给了每个分片的独立计数，
  分片之间应该大致均衡（按业务键取模、或按键范围等分，都是均匀的）。

（本仓库没有 MySQL 环境，这一节给的是跑法与观察点，不含本机数字 —— 不编造没跑过的数据。）

## 四、其他已知边界

- 单分片的比较成本与"同键组的重复条数"成正比，重复数据极多的键会成为热点。
- 差异写入是逐批 500 条 `batchUpdate`；MySQL 下配合 `rewriteBatchedStatements=true` 效果明显。
- 归因（尤其大模型归因）是独立的后置步骤，不影响对账本身的耗时；`ExplainService.explainBatch` 是按
  条数分批调用，批量大小由 `recon.llm.batch-limit` 控制。
- 检查点粒度是分片：任务中断后重跑的最小单位是一个分片，不会从半张表中间的某一行继续。
