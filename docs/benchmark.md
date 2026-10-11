# 压测：百万级对账怎么跑、看什么

目标不是刷一个漂亮的 QPS，而是证明两件事：

1. **内存与数据量解耦** —— 左侧 100 万 + 右侧 100 万，`-Xmx256m` 也能跑完；
2. **分片只影响耗时，不影响结果** —— 8 分片与 1 分片的差异总数必须逐条一致。

## 造数

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS recon_lens DEFAULT CHARSET utf8mb4"
mysql -uroot -p recon_lens < tools/gen_bill_data.sql
```

脚本会生成 1,000,000 条 `bill_left` 与 1,000,100 条 `bill_right`（MySQL 8，递归 CTE 造序号表），
并按固定比例埋差异（每 1 万条一个）：

| 埋的差异 | 条件 | 预期条数 |
| --- | --- | --- |
| 右侧整条缺失 `MISSING_RIGHT` | `n % 10000 = 0` | 100 |
| 左侧没有、右侧独有 `MISSING_LEFT` | `n % 10000 = 2` | 100 |
| 同键重复 `DUPLICATE_KEY` | `n % 10000 = 1` 再插一条 | 100 |
| 金额差 1 元 `AMOUNT_MISMATCH` | `n % 2000 = 0` | 500 |
| 状态取反 `STATUS_MISMATCH` | `n % 5000 = 0` | 200 |

## 跑

```bash
# 用生产 profile（MySQL），堆限制在 256M，专门盯内存
mvn spring-boot:run -Dspring-boot.run.profiles=mysql \
    -Dspring-boot.run.jvmArguments="-Xmx256m -XX:+UseG1GC"

# 同步跑一次，curl 直接等到结束
time curl -s -X POST http://localhost:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"ruleCode":"BILL_VS_SETTLE","batchId":"bench-1m","sync":true}'

curl -s 'http://localhost:8080/api/diffs/summary?jobId=1'
```

`rules/bill-recon.yml` 里的 `shard.count` 就是并行度实验的旋钮：先 8，再 1，跑两遍做对照。
分片条件按业务键末两位取模（`MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}`），
两侧口径一致，所以分片数怎么改都不会影响差异结果。

## 记录表

填自己机器上的实测值（跑完 `time curl` 和 `/api/diffs/summary` 就有）：

| 分片数 | 耗时 | 左侧扫描 | 右侧扫描 | 差异总数 | 堆峰值 |
| --- | --- | --- | --- | --- | --- |
| 1 | | 1,000,000 | 1,000,100 | 1,000 | |
| 4 | | 1,000,000 | 1,000,100 | 1,000 | |
| 8 | | 1,000,000 | 1,000,100 | 1,000 | |

（差异总数 = 100 + 100 + 100 + 500 + 200 = 1,000；`duplicate` 与 `amount` 叠加的键仍各算各的类型。）

## 怎么看到"流式"真的生效

- H2 演示库跑不出这个结论（数据量太小），必须用 MySQL。
- `application-mysql.yml` 里的 `useCursorFetch=true` 是关键：不打开它，MySQL 驱动会把整个结果集拉进
  内存，`setFetchSize` 形同虚设 —— 这一条比调大堆更值得先检查。
- 用 VisualVM / JFR 看堆曲线：健康的形态是"锯齿状小幅波动"，而不是一条随扫描行数上升的斜线。
- 想看分片内部：`recon_shard` 表按 `scanned_left / scanned_right / diff_rows` 给了每个分片的独立计数，
  分片之间应该大致均衡（按业务键末两位取模天然均匀）。

## 已知的性能边界

- 单分片的比较成本与"同键组的重复条数"成正比，重复数据极多的键会成为热点。
- 差异写入是逐批 500 条 `batchUpdate`；MySQL 下配合 `rewriteBatchedStatements=true` 效果明显。
- 归因（尤其大模型归因）是独立的后置步骤，不影响对账本身的耗时；`ExplainService.explainBatch` 是按
  条数分批调用，批量大小由 `recon.llm.batch-limit` 控制。
