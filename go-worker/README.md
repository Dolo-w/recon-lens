# recon-worker：大批量比对执行器（Go 单二进制）

recon-lens 的执行层分体：**规则引擎、取数、任务编排、差异生命周期留在 Java**，
worker 只做一件事 —— 把「两侧已按业务键升序导出的 CSV」按规则快速比完，
产出差异文件与统计。卖点是部署形态：**一个 exe 扔进去就跑**，没有 JVM 宠物。

对比语义与 Java `MergeMatcher` / `ValueComparator` **逐条对齐**（同一份数据
Java 跑和 Go 跑应给出相同差异集），测试里有端到端对齐用例。

## 什么时候用 worker，什么时候用 Java 引擎

| 场景 | 用谁 |
| --- | --- |
| 库表对库表、要水位探测/差异生命周期/计价 | Java 引擎 |
| 厂商 CSV/导出文件大批量比对（百万行级） | worker |
| CI 里对账、临时脚本取证、客户机器上跑 | worker |

## 快速开始

```
go build -o recon-worker.exe .

recon-worker --left bill.csv --right settle.csv --spec rule.json ^
    --out diffs.jsonl --stats stats.json
```

输入要求：两侧 CSV **按业务键升序**（导出时 `ORDER BY`）——worker 启动时会
预检有序性，乱序数据直接拒绝（乱序归并会错位出假差异，宁可不跑）。

## 任务规格（spec.json）

字段与 Java YAML 规则一一对应，可直接从规则文件复制：

```json
{
  "jobId": "demo",
  "left":  {"label": "账单", "keyField": "order_no", "keyType": "string", "eventTime": "created_at"},
  "right": {"label": "结算", "keyField": "order_no", "keyType": "string"},
  "compare": [
    {"field": "amount", "type": "decimal", "tolerance": "0.01",
     "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"},
    {"field": "status", "type": "string", "ignoreCase": true,
     "diffType": "STATUS_MISMATCH", "severity": "MEDIUM"}
  ],
  "missing":  {"leftMissingDiffType": "BILL_MISSING", "rightMissingDiffType": "SETTLE_MISSING", "severity": "HIGH"},
  "duplicate": {"diffType": "DUPLICATE_KEY", "severity": "HIGH"}
}
```

- `keyType` / `type`：`string`（默认）| `boolean` | `long` | `decimal`
- `tolerance` 绝对容差、`tolerancePercent` 相对容差（都配时任一超限即差异），
  数值类型专用；十进制串传入，内部走精确十进制（对齐 BigDecimal，不是 float）
- `severity` 默认 `MEDIUM`；写错字回落 MEDIUM，不会停任务
- `eventTime`：差异带业务发生时刻（事件级对账），左侧优先

## 提交语义：最大可提交前缀（学 Lago events-processor）

worker 按 `--shard-size`（默认 50000 个键组事件）把归并流切成逻辑分片，
committer **按分片顺序提交**：

1. 分片差异写入输出文件 → flush → 记 checkpoint（输出字节偏移 + 末键）；
2. 任何失败停在第一个未成功分片：**之前的提交全部保留**；
3. 修复问题后 `--resume`：输出截断回 checkpoint 边界（丢弃半截差异），
   扫描跳过已提交键组，从断点继续。

崩溃恢复是同一机制：输出文件里可能比 checkpoint 多写了几行（进程死在
flush 之后、checkpoint 之前），resume 时截断回边界，不变式是
**「输出文件 = 已提交前缀」**。

```
recon-worker --left ... --right ... --spec ... --resume
```

## 并发

归并必须单线程顺序（键序前提），字段比对下沉为纯函数按分片并发
（`--concurrency`，默认 CPU 核数、上限 8），committer 按序收集提交。
10 万行 × 2 侧实测 0.75 秒（含预检共 4 遍读）。

## 水位 PENDING（可选）

与 Java 引擎的快照/cut-off 语义一致：水位早于就绪线 → 该侧缺失差异标
`*_PENDING`（未到达 ≠ 真缺失），并带解释：

```
--right-watermark 2026-11-01T00:00:00Z --deadline 2026-11-20T00:00:00Z
```

只传水位不传就绪线 = 无从判定，视为就绪（诚实处理，不猜）。
时间格式：RFC3339 或 `yyyy-MM-dd[ HH:mm[:ss]]`。

## 输出

- `diffs.jsonl`（默认）：每行一条差异，字段对齐 Java `DiffRecord`：
  `diffType / severity / bizKey / field / leftValue / rightValue /
  deltaValue / deltaRatio / eventAt / explanation`，外加两侧整行 JSON 凭证
  （超 4000 字符截断，口径同 Java）
- `--format csv`：证据包列格式，可直接发给客户/财务
- `stats.json`：扫描数（跳过数单列）、matched、byType/byField、分片提交情况

## 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 完成（COMPLETED） |
| 1 | 启动/配置错误（规格、参数、字段不存在） |
| 2 | 数据错误（编码/CSV 格式/乱序；checkpoint 已保留可 resume） |
| 3 | 部分提交（最大可提交前缀；checkpoint 可 resume） |

## 语义对齐清单（与 Java 引擎逐条对应）

| Java | worker | 说明 |
| --- | --- | --- |
| `MergeMatcher` 归并 | `matcher.go runProducer` | 双指针归并、同键组、重复键只报不配对 |
| `ValueComparator` | `value.go` | null/trim/ignoreCase/Boolean.parseBoolean/BigDecimal |
| `canonicalKey` | 渲染去尾零 | 10.00 与 10 同键同显示 |
| `Readiness` PENDING | 水位 CLI 参数 | 未就绪缺失标 `_PENDING` + 解释 |
| `eventAtOf` | `eventTime` 列 | 左优先、原样字符串、解析交给计价侧 |
| `ShardRunner` 事务 | checkpoint 提交 | 整分片成功才算提交，失败可续跑 |
| `validateFields` | 启动时列校验 | 配置错误在跑之前报 |
