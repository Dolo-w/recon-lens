# 规则 DSL 手册

一条规则就是一个 YAML 文件，放在 `recon.rules-location` 指向的目录（默认 `classpath:rules/`）。
启动时全部解析并校验一遍：配置写错会在启动阶段带着"改哪里"的提示直接失败，而不是等跑对账时抛空指针。

---

## 顶层字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `code` | 是 | 规则唯一标识，`[A-Z][A-Z0-9_]*`，例如 `BILL_VS_SETTLE`。REST 里用它发起对账 |
| `name` | 否 | 中文名，缺省等于 `code` |
| `description` | 否 | 一句话说明 |
| `left` / `right` | 是 | 两侧数据源，见下 |
| `shard` | 否 | 分片与并行度，缺省 `count: 1`（不分片、单线程） |
| `compare` | 是 | 要比对的字段，至少 1 个 |
| `missing` | 是 | 单边缺失归到哪种差异 —— 必须显式声明，避免差异被静默丢弃 |
| `duplicate` | 否 | 同键重复归到哪种差异，缺省 `DUPLICATE_KEY` |
| `options.maxAttempts` | 否 | 单个分片最大尝试次数，缺省 1（不重试） |

## left / right

两种写法二选一：写 `table`（整表对账）或写 `sql`（自定义口径，聚合/跨表都能用）。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `label` | 否 | 出现在归因文案里的名字，如"账单" / "结算" |
| `table` | 二选一 | 表名，等价于 `SELECT * FROM <table>` |
| `sql` | 二选一 | 自定义查询，**必须**把业务键起别名为 `keyField` 指定的名字 |
| `keyField` | 是 | 业务键列名（订单号、客户号…），两侧同名 |
| `keyType` | 否 | 业务键比较类型：`STRING`（默认）/ `LONG` / `DECIMAL` / `BOOLEAN` |
| `orderBy` | 是 | 取数排序字段，**必须等于 `keyField`** |

> **为什么 `orderBy` 必须等于 `keyField`**：引擎用的是归并式对账（双指针），两侧必须按同一个业务键升序到达。
> 排序与引擎的比较必须一致，所以 `keyType: STRING` 时请用定宽零填充的业务键（`ORD0000000001`）——
> 定宽字符串的字典序与数值序一致；宽度不齐时（`ORD2` / `ORD10`）字典序会与数值序分叉。

自定义 SQL 示例（按客户汇总后对账）：

```yaml
left:
  label: 客户账单
  keyField: biz_key
  keyType: STRING
  orderBy: biz_key
  sql: SELECT customer_id AS biz_key, SUM(amount) AS amount, COUNT(*) AS bill_count FROM bill_left GROUP BY customer_id
```

## shard

```yaml
shard:
  count: 4                                            # 分片数 = 并行单位 = 断点重跑单位
  parallelism: 4                                       # 本规则并发上限，实际线程数 = min(它, recon.engine.parallelism)
  whereLeft:  "MOD(id, {shards}) = {index}"
  whereRight: "MOD(id, {shards}) = {index}"
```

- `{shards}` 替换为分片总数，`{index}` 替换为分片下标（0 起）。
- `count > 1` 时 `whereLeft` 必填；`whereRight` 缺省与 `whereLeft` 相同。
- **两侧必须按同一个业务键口径切分**。上面这个 `MOD(id, …)` 是**反例**：`id` 是各自的代理主键，
> 同一笔业务在左右表 id 不同就会被切到不同分片，两边互相看不见，结果是一堆假缺失。
> 正确写法（按业务键取模）：
>
> ```yaml
>   whereLeft:  "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"
>   whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"
> ```
>
> 解析器会在 `where` 完全不引用业务键时打 WARN 提醒（只告警，因为按业务键做哈希/区间切分都是合法写法）。
>
> **性能提醒（实测数据见 [benchmark.md](benchmark.md)）**：`MOD(CAST(SUBSTRING(order_no, …)))` 是"列上套函数"，
> 索引用不上，每个分片都得全表扫一遍 —— 百万级数据下 8 分片 37.4 秒、1 分片 4.3 秒，**分片越多越慢**，
> 但差异结果完全一致。要让分片真的提速，先在库里把分片桶做成索引列（MySQL 8 函数索引
> `CREATE INDEX … ((MOD(n, 8)))`，或生成列 + 普通索引），规则里写 `shard_bucket = {index}`。
> 当前 DSL 只有 `{index}` / `{shards}` 两个占位符、所有分片共用一段 where，因此还表达不了
> "每个分片一段连续键范围"这种最自然的切法（`shard.strategy: RANGE` 在后续计划里）。

## compare

```yaml
compare:
  - { field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH }
  - { field: status, type: STRING,  ignoreCase: true,  diffType: STATUS_MISMATCH,  severity: MEDIUM }
```

| 字段 | 说明 |
| --- | --- |
| `field` | 字段名，大小写不敏感（列名在引擎里统一转小写） |
| `type` | `STRING` / `LONG` / `DECIMAL` / `BOOLEAN` |
| `ignoreCase` | 仅 `STRING`：忽略大小写（同时会 trim 两边空白） |
| `tolerance` | 数值绝对容差，差异 `<= tolerance` 视为一致 |
| `tolerancePercent` | 数值相对容差（0.005 = 0.5%），与 `tolerance` 同时配置时任一超限即判为差异 |
| `diffType` | 差异类型名，自由字符串。同一条规则内必须唯一 |
| `severity` | `LOW` / `MEDIUM` / `HIGH`，缺省 `MEDIUM` |

- 任一侧为 `NULL`、另一侧非 `NULL` → 直接判为差异（没有容差概念）。
- `tolerance` / `tolerancePercent` 只对 `DECIMAL` / `LONG` 有意义，配在 `STRING` 上会在启动时报错。
- 相对容差的分母是**右侧绝对值**；右侧为 0 时任何非零偏差都算差异。

## missing / duplicate

```yaml
missing:
  leftMissingDiffType: MISSING_LEFT     # 只在右表出现的键
  rightMissingDiffType: MISSING_RIGHT   # 只在左表出现的键
  severity: HIGH
duplicate: { diffType: DUPLICATE_KEY, severity: MEDIUM }
```

- 一个业务键在某一侧出现多条时，只发一条 `DUPLICATE_KEY` 差异，`leftValue` / `rightValue` 记下两侧条数
  与最多 5 个主键；组内多余的行不再重复报单边缺失，否则重复数据会同时污染"重复"和"缺失"两类差异。
- 组内其余行仍会按位配对做字段比对（配对数量 = `min(左侧条数, 右侧条数)`）。

## 差异分类与归因的关系

引擎内部只认四类语义（`DiffKind`）：`VALUE_MISMATCH`、`MISSING_LEFT`、`MISSING_RIGHT`、`DUPLICATE_KEY`。
规则里的 `diffType` 是给业务看的自由字符串，引擎按下面顺序反查它的语义：

1. 等于 `duplicate.diffType` → `DUPLICATE_KEY`
2. 等于 `missing.rightMissingDiffType` → `MISSING_RIGHT`
3. 等于 `missing.leftMissingDiffType` → `MISSING_LEFT`
4. 其余 → `VALUE_MISMATCH`

所以 `diffType` 想怎么改名都行，归因模板只看这四类。

## 最小可用规则

```yaml
code: DEMO
left:  { table: bill_left,  keyField: order_no, keyType: STRING, orderBy: order_no }
right: { table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no }
compare:
  - { field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH }
missing: { leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT }
```

## 启动期校验清单

解析器（`dsl/RuleParser`）会一次性检查：

- `code` 格式、`keyField` 非空、`left` / `right` 段存在
- `table` 与 `sql` 必须二选一
- `orderBy` 必须等于 `keyField`
- `shard.count` / `parallelism` 至少为 1；`count > 1` 时 `where` 必须同时含 `{index}` 与 `{shards}`
- `compare` 至少一个字段；`diffType` 在规则内唯一
- 容差只允许出现在 `DECIMAL` / `LONG` 字段上且非负
- `missing` 段必须存在
- （WARN）分片 `where` 未引用业务键

运行时校验：每个分片开跑前会检查 `keyField` 与所有 `compare.field` 是否真的出现在结果集列里，
缺列时报出的错误带上实际列名 —— 自定义 SQL 写错别名是最常见的手误。
