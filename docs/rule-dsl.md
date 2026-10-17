# 规则 DSL 手册

一条规则就是一个 YAML 文件，放在 `recon.rules-location` 指向的目录（默认 `classpath:rules/`）。
启动时全部解析并校验一遍：配置写错会在启动阶段带着"改哪里"的提示直接失败，而不是等跑对账时抛空指针。

规则运行时的权威来源是数据库规则库（`recon_rule`）：目录里的 YAML 在启动时与库里的同名规则对齐（库里没有的
`code` 作 v1 导入，内容不一致就按文件覆盖成新版本），`POST /api/rules` 提交的新版本立即生效、不需要重启
（见下文「规则库与版本」）。

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

数据源两种写法二选一：写 `table`（整表对账）或写 `sql`（自定义口径，聚合/跨表都能用）。
业务键也是两种写法二选一：写 `keyField`（直接指定键列），或写 `dimension`（声明式对账维度，见下节）。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `label` | 否 | 出现在归因文案里的名字，如"账单" / "结算" |
| `table` | 二选一 | 表名，等价于 `SELECT * FROM <table>` |
| `sql` | 二选一 | 自定义查询；用 `keyField` 时**必须**把业务键别名为 `keyField` 指定的名字 |
| `keyField` | 二选一 | 业务键列名（订单号、客户号…），两侧同名。与 `dimension` **互斥** |
| `dimension` | 二选一 | 声明式对账维度：一个字符串表达式，或一组列名（见下节） |
| `dimensionSeparator` | 否 | 列表维度拼接用的分隔符，缺省 `\|` |
| `measures` | 否 | 聚合列，写成 `别名: SQL 表达式`；**只有声明了 `dimension` 才能配** |
| `keyType` | 否 | 业务键比较类型：`STRING`（默认）/ `LONG` / `DECIMAL` / `BOOLEAN` |
| `orderBy` | 见说明 | `keyField` 写法下必填且**必须等于 `keyField`**；写了 `dimension` 时**不允许**配置 |
| `where` | 否 | 两侧各自的静态行级过滤，与分片条件是 **AND** 关系；不支持 `{index}` / `{shards}` 占位符（见下节「关于 `where`」） |

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

## 声明式对账维度（dimension / measures）

`dimension` 回答"这一笔业务靠什么区分"。不写 SQL 也能定义对账维度：引擎把它收敛成一个生成的键列
`__biz_key`，之后归并、比较、落库都只认这一个列 —— 维度长什么样是规则的事，引擎不需要知道。

| 写法 | 示例 | 生成的键表达式 |
| --- | --- | --- |
| 字符串表达式 | `dimension: "CONCAT(customer_id, '#', status)"` | 原样使用 |
| 列名列表 | `dimension: [account_id, resource_id]` | `CONCAT(account_id, '\|', resource_id)` |
| 单列列表 | `dimension: [order_no]` | `order_no`（不套 `CONCAT`） |

```yaml
left:
  label: 资源清单
  table: resource_left
  keyType: STRING
  dimension: [account_id, resource_id]        # 与 keyField 互斥；也不要再写 orderBy
```

- 列表维度用 `dimensionSeparator` 指定分隔符（缺省 `|`），分隔符里的 `'` 会被转义成 `''`。
- **单列维度直接用列名**：`dimension: order_no` 或 `dimension: [order_no]`，生成 `SELECT order_no AS __biz_key`，
  排序与条件都作用在列本身、能命中索引；**多列才用 `CONCAT(a, '|', b)`**。
- 拼接**不要用 `||`**：MySQL 里默认是逻辑或，不是字符串拼接。
- 拼接出来的键按字符串比较，`keyType` 一般保持 `STRING`；结果里的 `biz_key` 就是那个字符串（如 `C001|OK`）。
- 只写 `dimension` 不写 `measures` 时**不分组**，行数与原表一致（每行算一个业务键）。
- 声明了 `dimension` 就**不能写 `orderBy`**：引擎固定按生成的 `__biz_key` 升序取数（归并式对账依赖两侧同序）。

### 聚合维度（measures）

`measures` 写 `别名: SQL 表达式`，且必须先有 `dimension`：

```yaml
left:
  label: 账单
  table: bill_left
  keyType: STRING
  dimension: [customer_id, status]            # 生成 CONCAT(customer_id, '|', status)
  measures:
    amount: SUM(amount)
    order_count: COUNT(*)
```

聚合维度下 `compare.field` **必须**是 `measures` 里产出的别名（分组后原始明细列已经没有意义），
否则启动期就失败：

```
账单(bill_left, 维度=CONCAT(customer_id, '|', status)) 用了聚合维度（measures），比对字段 status 必须出现在 measures 里，例如 status: SUM(status)
```

完整示例见 `src/main/resources/rules/customer-status-recon.yml`。

### 关于 `where`

`left.where` / `right.where` 是**两侧各自的静态行级过滤**，和 `shard` 的分片条件是「与」的关系（`ShardPlanner#combine`）：

| 情况 | 生成的 `WHERE` |
| --- | --- |
| 有分片条件 + 有侧级过滤 | `WHERE (<分片条件>) AND (<侧级过滤>)` |
| 只有侧级过滤 | `WHERE <侧级过滤>` |
| 只有分片条件 | `WHERE <分片条件>` |
| 都没有 | 不生成 `WHERE` 子句 |

典型用法是"只对未作废的单据对账"：

```yaml
left:
  label: 账单
  table: bill_left
  keyField: order_no
  orderBy: order_no
  where: "state <> 'DELETED'"
```

生成的 SQL（实测形状）：

```sql
-- 侧级过滤 + 分片条件
SELECT * FROM (SELECT * FROM bill_left) src
  WHERE (MOD(id, 4) = 1) AND (state <> 'DELETED') ORDER BY order_no

-- 只有侧级过滤
SELECT * FROM (SELECT * FROM bill_left) src
  WHERE state <> 'DELETED' ORDER BY order_no
```

- 左右各写各的：`left.where` 只作用左表，不影响右侧，也不需要两边写法一致。
- **但两侧过滤口径不一致会人为制造单边缺失**：例如只在右侧过滤掉 `state = 'DELETED'`，这些业务键在左侧还在，
  就会报出一批 `MISSING_RIGHT`。所以除非确实只想单边看，两侧要按同一业务口径过滤。
- 声明式维度下侧级过滤同样作用在**原始数据**上（和分片条件一起待在内层，投影 `__biz_key` 之前）：
  `SELECT customer_id AS __biz_key, src.* FROM (SELECT * FROM (SELECT * FROM bill_left) src WHERE state <> 'DELETED') src ORDER BY __biz_key`
- **不支持 `{index}` / `{shards}` 占位符**：写了在解析期就报错，分片是 `shard.whereLeft` / `shard.whereRight` 的活 ——
  ``<side>.where 是两侧各自的静态过滤条件，不支持 {index}/{shards} 占位符：分片条件请写 shard.whereLeft / shard.whereRight``
  （`<side>` 即 `left` / `right`）。

## 生成的取数 SQL

不管哪种写法，生成的 SQL 都按业务键升序取数 —— 归并式对账的正确性依赖这个前提。
`__biz_key` 是声明式维度生成的键列别名（`SideSpec.KEY_ALIAS`）。

| 写法 | 生成的 SQL |
| --- | --- |
| `table` + `keyField` | `SELECT * FROM (SELECT * FROM <table>) src [WHERE <分片条件> [AND <侧级过滤>]] ORDER BY <orderBy>` |
| `sql` + `keyField` | `SELECT * FROM (<sql>) src [WHERE <分片条件> [AND <侧级过滤>]] ORDER BY <orderBy>` |
| `dimension` | `SELECT <keyExpr> AS __biz_key, src.* FROM (<sourceSelect>) src ORDER BY __biz_key` |
| `dimension` + 分片 | `SELECT <keyExpr> AS __biz_key, src.* FROM (SELECT * FROM (<sourceSelect>) src WHERE <分片条件> [AND <侧级过滤>]) src ORDER BY __biz_key` |
| `dimension` + `measures` | `SELECT <keyExpr> AS __biz_key, <表达式> AS <别名>, … FROM (<inner>) src GROUP BY <keyExpr> ORDER BY __biz_key` |

`<sourceSelect>` 是 `SELECT * FROM <table>` 或自定义 `sql`；`<inner>` 是 `<sourceSelect>` 本身，
或外面再套一层、把合并后的条件放进 `WHERE` 的包装（条件合并规则见「关于 `where`」）。
`<侧级过滤>` 是 `left.where` / `right.where`，两个条件都没有时整段 `WHERE` 不出现。

实际生成的三条形状（与 `ShardPlannerTest` 一致）：

```sql
-- 单列维度，不分片
SELECT customer_id AS __biz_key, src.* FROM (SELECT * FROM bill_left) src ORDER BY __biz_key

-- 单列维度 + 分片条件：条件在内层 WHERE，作用在原始数据上
SELECT customer_id AS __biz_key, src.*
  FROM (SELECT * FROM (SELECT * FROM bill_left) src WHERE LENGTH(customer_id) = 4) src
  ORDER BY __biz_key

-- 聚合维度：先按原始数据过滤，再 GROUP BY 维度表达式
SELECT customer_id AS __biz_key, SUM(amount) AS amount
  FROM (SELECT * FROM (SELECT * FROM bill_left) src WHERE LENGTH(customer_id) = 4) src
  GROUP BY customer_id ORDER BY __biz_key
```

- 分片条件在**内层** `WHERE`，作用在原始数据上，**不是**作用在分组结果上。
- 聚合维度只产出「维度 + measures」，`src.*` 那套原始列不再出现在结果里。

## shard

```yaml
shard:
  count: 4                                            # 分片数 = 并行单位 = 断点重跑单位
  parallelism: 4                                       # 本规则并发上限，实际线程数 = min(它, recon.engine.parallelism)
  whereLeft:  "MOD(id, {shards}) = {index}"
  whereRight: "MOD(id, {shards}) = {index}"
```

- `{shards}` 替换为分片总数，`{index}` 替换为分片下标（0 起）。
- `count > 1` 时 **`whereLeft` 与 `whereRight` 都必填**：解析器对两侧分别校验，缺任何一个都会报
  `shard.count > 1 时必须配置 shard.whereRight`；且两侧都必须**同时含 `{index}` 与 `{shards}`** 两个占位符。
- **不存在"`whereRight` 缺省时继承 `whereLeft`"**。两侧用同一个业务键口径是**要显式写出来**的事，
  不是可以默认继承的事 —— 静默继承正是"同一笔业务被切到不同分片、结果凭空多出一堆单边缺失"最常见的来源。
  （这条以前写成"`whereRight` 缺省与 `whereLeft` 相同"，与解析器实现不符，已按代码修正。）
- **分片条件不能把同一个分组切开**（`dimension` + `measures` 时尤其重要）：两侧各算一半再比对，只会报假差异。
  例如按 `dimension: [customer_id, status]` 汇总时，要按 `customer_id` 分片，**不能按 `status` 分片** ——
  `customer_id` 是分组键的一部分，同一个分组必然落在同一个分片。仓库里的 `customer-status-recon.yml` 就是这么切的。
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
> `CREATE INDEX … ((MOD(n, 8)))`，或生成列 + 普通索引），规则里写
> `shard_bucket = {index} AND shard_bucket < {shards}`（`{shards}` 不能省，解析器要求两个占位符同时在场）。
> **这条现在有现成实现**：MCP `recon_build_model` / `POST /api/models/{ruleCode}` 会按规则粒度把数据物化成模型表，
> 桶列是它建的生成列 `biz_key_bucket`（带索引），自动注册的 `<CODE>__MODEL` 规则两侧写
> `biz_key_bucket = {index} AND biz_key_bucket < {shards}`（两侧同一个桶函数，同一个业务键必然落同一片；
> `< {shards}` 只是桶号的天然上界）。1000 万行宽表 21.47 s → 7.8 ms，见 [mcp.md](mcp.md) 与 benchmark.md §3.10。
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
- 一侧用了聚合维度（`measures`）时，`field` 必须是那一侧 `measures` 产出的别名，否则启动期直接报错。

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

## 规则库与版本

规则的权威来源是数据库表 `recon_rule`。规则目录（`recon.rules-location`，默认 `classpath:rules/*.yml`）在启动时与库对齐：

1. 库里没有该 `code` → 作为 v1 导入（`created_by` 记 `seed:<文件名>`）；
2. 最新启用版本与文件内容**逐字段相同** → 什么都不做（重启不会凭空长版本）；
3. 内容不同 → 按文件**覆盖**成新版本：追加一个版本、不删旧版本，历史任务照旧回放；被覆盖的那一版如果是人改的
   （`created_by` 不是 `seed:` 开头），日志里会用 `WARN` 点出它原来是哪个操作人哪一版改的；
4. 该 `code` 已被 `DELETE /api/rules/{code}` 显式下线 → 不覆盖也不复活。

想让规则库完全自己说了算（库里的改动不再被文件覆盖），设 `recon.seed-rules: false` —— 此时启动完全不碰规则库。

| 列 | 说明 |
| --- | --- |
| `code` / `version` | 规则编号 + 版本号，唯一索引 `(code, version)` |
| `spec_json` | 规则原文（归一化后的 JSON），可原样回放 |
| `enabled` | 下线后为 `FALSE`，规则列表里不再出现 |
| `created_by` / `created_at` | 来源与时间；seed 导入的 `created_by` 是 `seed:<文件名>` |

接口：

| 方法 | 说明 |
| --- | --- |
| `GET /api/rules` | 每个 `code` 的最新启用版本 |
| `GET /api/rules/{code}` | 最新版本 + 全部版本历史 |
| `POST /api/rules` | 定义/修改规则：校验通过 → 追加新版本（`version = max(version) + 1`）→ 立即生效 |
| `DELETE /api/rules/{code}` | 下线该 `code` 的全部版本（历史任务与快照不受影响） |

```bash
curl -X POST http://localhost:8080/api/rules \
  -H 'Content-Type: application/json' -H 'X-Operator: alice' \
  -d @my-rule.json          # Content-Type 含 yaml 时按 YAML 解析
```

- 成功返回 `201` + 规则描述（含新的 `version`）；校验不过返回 `400` + `{"error": "…"}`，提示里带"改哪里"。
- `X-Operator` 记录操作人，缺省 `api`。
- **无需重启**：规则一落库就生效，该 `code` 的缓存随即失效，下一个任务就能用上新维度。

版本怎么钉在任务上：

- 每个任务创建时把当时生效的 `rule_version` + `rule_snapshot`（规则原文）写进 `recon_job`。
- 重跑与差异归因都优先用那份快照解析规则，所以之后改规则、下线版本都不会让历史任务语义漂移。
- 只有老任务没有快照时才退回归档版本，最后才退最新版本。
- 请求里内联的规则（`rule` / `ruleYaml`）不落库（`version` 为 null），但仍然存快照，因此照样可以断点重跑。

规则目录（seed）：

- 启动时把 `recon.rules-location`（缺省 `classpath:rules/`，MySQL profile 是 `file:./rules/`）下的 `*.yml`
  里**库里还没有的 `code`** 导入为 v1。
- 库里已有该 `code`：内容与文件一致则不动；**不一致就按文件覆盖成新版本**（升级版本，旧版本仍可回放）。
- 已被 `DELETE /api/rules/{code}` 下线过的 `code` 不会被种子复活。
- `recon.seed-rules: false` 可完全关闭对齐（启动不碰规则库），此后改规则只走 `POST /api/rules`。

仓库自带 4 个示例规则：

| code | 文件 | 用途 |
| --- | --- | --- |
| `BILL_VS_SETTLE` | `bill-recon.yml` | 按 `order_no` 逐笔比对金额（容差 0.01）与状态；4 分片按订单号末两位取模，`maxAttempts: 2` |
| `CUSTOMER_SUM_RECON` | `customer-sum-recon.yml` | 自定义 `sql` 按 `customer_id` 汇总后比对金额，相对容差 `tolerancePercent: 0.005` |
| `RESOURCE_VS_BILL` | `resource-recon.yml` | 声明式复合维度 `dimension: [account_id, resource_id]`，生成 `CONCAT(account_id, '\|', resource_id) AS __biz_key` |
| `CUSTOMER_STATUS_RECON` | `customer-status-recon.yml` | `dimension: [customer_id, status]` + `measures`（`amount` / `order_count`）的聚合维度示例 |

## 启动期校验清单

规则体走的是**同一套**校验（`dsl/RuleParser#parseMap`）：classpath 的 YAML seed、`POST /api/rules` 的 JSON/YAML、
任务里的内联规则，宽松度完全一致。所以启动期发现的问题，在 API 提交时同样会以 `400` + `{"error": "…"}` 返回。

- `code` 格式、`left` / `right` 段存在
- `table` 与 `sql` 必须二选一
- `keyField` 与 `dimension` 必须二选一（两个都不给也报错）；`dimension` 列表里不能出现空列名、不能是空列表
- 声明了 `dimension` 就不能再写 `orderBy`；`keyField` 写法下 `orderBy` 必须等于 `keyField`
- `measures` 必须配合 `dimension` 使用，每一项都要写成 `别名: SQL 表达式`
- 聚合维度下 `compare` 的每个 `field` 必须由该侧 `measures` 产出
- `left/right.where` 不支持 `{index}` / `{shards}` 占位符（分片条件只能写在 `shard.whereLeft/whereRight`）
- `shard.count` / `parallelism` 至少为 1；`count > 1` 时 `where` 必须同时含 `{index}` 与 `{shards}`
- `compare` 至少一个字段；`diffType` 在规则内唯一
- 容差只允许出现在 `DECIMAL` / `LONG` 字段上且非负
- `missing` 段必须存在
- （WARN）分片 `where` 未引用业务键

运行时校验：每个分片开跑前会检查业务键列（`keyField` 或生成的 `__biz_key`）与所有 `compare.field`
是否真的出现在结果集列里，缺列时报出的错误带上实际列名 —— 自定义 SQL 写错别名是最常见的手误。

> 改规则**不需要重启**：`POST /api/rules` 落一个新版本就立即生效（见「规则库与版本」）。
> 启动期校验针对的是 `recon.rules-location` 下那些 `*.yml`；它们只在规则库还没有该 `code` 时被导入。
