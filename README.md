# recon-lens

<p>
  <a href="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml"><img alt="build" src="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml/badge.svg"></a>
  <a href="https://github.com/Dolo-w/recon-lens/releases"><img alt="release" src="https://img.shields.io/github/v/release/Dolo-w/recon-lens?sort=semver"></a>
  <img alt="java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="spring boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-6db33f">
  <img alt="mcp" src="https://img.shields.io/badge/MCP-stdio-6f42c1">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

**配置化的对账引擎**：规则描述"对账维度 + 要对的字段"（复合键、聚合口径都能声明），存进规则库提交即生效 →
分片并行归并对账 → 差异自动分类 → 规则模板 / 大模型给出归因；
同时是一个 MCP server，Agent 可以直接调这些能力，也能自己提交一版新规则。

> Reconciliation engine for money pipelines: rule-as-YAML, shard-parallel merge join, streamed cursors,
> idempotent re-runs, and diff attribution (rule templates + optional OpenAI-compatible LLM).

---

## 它解决什么问题

线上对账脚本通常长这样：一段硬编码 SQL + 一堆 `if`，跑出几万条差异丢给运营，然后运营来问"为什么差"。
真出事的时候更麻烦 —— 脚本跑到一半挂了，只能整批重来；数据涨到千万级，直接 OOM。

recon-lens 把这件事拆成四件确定的事：

| 传统脚本 | recon-lens |
| --- | --- |
| 对账逻辑写在代码里，改规则要发版 | 规则即数据：对账维度（复合键 / 聚合口径）与比对字段都声明式描述，提交新版本立即生效，落库前先被 DSL 校验拦一遍 |
| 全量 `SELECT` 进内存再比对 | 按业务键升序流式取数 + 双指针归并，内存与数据量无关 |
| 失败只能整批重跑，差异表越跑越脏 | 按分片记录进度，只重跑失败分片；先删本分片旧差异再写，重跑天然幂等 |
| 只给差异数量，原因靠人猜 | 每条差异自动分类 + 生成归因（规则模板兜底，可接大模型） |

## 30 秒跑起来

需要 JDK 17+。没装 Maven 就用仓库自带的 wrapper（Windows 用 `mvnw.cmd`）：

```bash
./mvnw spring-boot:run          # 或 mvn spring-boot:run
```

启动日志里会直接打出一份演示对账报告（H2 内存库自带 20 条账单 / 21 条结算数据 + 8/9 条资源数据，
埋了 5 类典型差异；仓库自带 4 个演示规则，启动时作为 v1 导入规则库）：

```
================ recon-lens 启动演示 ================
任务 #1 状态=SUCCESS 分片=4
扫描行数: 左侧 20 / 右侧 21，匹配 19，差异 6 条
差异分类: {AMOUNT_MISMATCH=2, DUPLICATE_KEY=1, MISSING_LEFT=1, MISSING_RIGHT=1, STATUS_MISMATCH=1}
归因补齐: 6 条（解释器=rule-template）
----------------------------------------------------
[MEDIUM] STATUS_MISMATCH key=ORD20260004 field=status UNPAID -> PAID
    归因: 账单 与 结算 的 status 不一致：左侧 UNPAID，右侧 PAID。常见原因：入账时点不同、手续费/汇率进位、撤单退单未同步、数据被人工修改。
[MEDIUM] DUPLICATE_KEY key=ORD20260019 账单 1 条(id=19) -> 结算 2 条(id=18/19)
    归因: 业务键 ORD20260019 出现重复记录（账单 1 条(id=19) / 结算 2 条(id=18/19)）。常见原因：重复入账或消息重放，需要按幂等键去重后重跑本分片。
[HIGH] MISSING_RIGHT key=ORD20260017 order_no=ORD20260017, amount=3300, status=PAID -> （无）
    归因: 业务键 ORD20260017 在结算侧没有记录，只有账单侧有（order_no=ORD20260017, amount=3300, status=PAID）。常见原因：对方延迟入账或推送失败、我方已受理但未落库、批次边界跨天。
[HIGH] AMOUNT_MISMATCH key=ORD20260003 field=amount 3000 -> 2999
    归因: 账单 与 结算 的 amount 不一致：左侧 3000，右侧 2999，相差 1（相对 0.03%）。常见原因：入账时点不同、手续费/汇率进位、撤单退单未同步、数据被人工修改。
----------------------------------------------------
试一下: curl http://localhost:8080/api/jobs/1
====================================================
```

```bash
./mvnw test     # 单元测试 + 端到端测试（真 H2、真 SQL、真并发）
```

## 核心设计

```mermaid
flowchart LR
    R[rules/*.yml] -->|RuleParser 校验，首次导入 v1| CAT[(recon_rule<br/>规则库 / 版本 / 快照)]
    API[POST /api/jobs<br/>POST · DELETE /api/rules] --> CAT
    AGENT[Agent / Claude Desktop] -->|stdio JSON-RPC| MCP[MCP server<br/>8 个工具]
    MCP --> CAT
    MCP --> ENG
    MCP --> DB
    CAT --> ENG[ReconEngine]
    ENG -->|ShardPlanner 替换 shards / index 占位符| SP[分片计划 ×N]
    SP --> POOL[ThreadPool<br/>min 规则并行度, 全局并行度]
    POOL --> RUN[ShardRunner 每个分片]
    RUN -->|先删本分片旧差异| DB[(recon_job /<br/>recon_shard /<br/>recon_diff)]
    RUN --> CUR[流式游标<br/>ORDER BY 业务键]
    CUR --> MM[MergeMatcher<br/>双指针归并]
    MM -->|VALUE_MISMATCH<br/>MISSING_LEFT / RIGHT<br/>DUPLICATE_KEY| RUN
    DB --> EXP[ExplainService]
    EXP --> TPL[RuleBasedExplainer<br/>离线模板]
    EXP --> LLM[LlmDiffExplainer<br/>OpenAI 兼容, 失败降级]
    EXP --> API2[GET /api/diffs<br/>POST /api/jobs/id/retry]
```

### 1. 规则即配置

一条对账规则的完整定义（`src/main/resources/rules/bill-recon.yml`）：

```yaml
code: BILL_VS_SETTLE
name: 账单与结算逐笔对账
left:  { label: 账单, table: bill_left,  keyField: order_no, keyType: STRING, orderBy: order_no }
right: { label: 结算, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no }

shard:                      # 分片 = 并行的单位，也是断点重跑的单位
  count: 4
  parallelism: 4
  # 两侧必须按「同一个业务键口径」切分：按各自的 id 取模会把同一笔业务切到不同分片，凭空多出单边缺失
  whereLeft:  "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"
  whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"

compare:
  - { field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH }
  - { field: status, type: STRING,  ignoreCase: true,  diffType: STATUS_MISMATCH,  severity: MEDIUM }

missing:                    # 单边缺失必须显式声明归到哪种差异，避免被静默丢弃
  leftMissingDiffType: MISSING_LEFT
  rightMissingDiffType: MISSING_RIGHT
  severity: HIGH

duplicate: { diffType: DUPLICATE_KEY, severity: MEDIUM }
options:   { maxAttempts: 2 }
```

`left`/`right` 还能直接写自定义 SQL（聚合对账、跨表口径），只要把业务键别名为 `keyField` 并保证按它升序：

```yaml
left:
  label: 客户账单
  keyField: biz_key
  keyType: STRING
  orderBy: biz_key
  sql: SELECT customer_id AS biz_key, SUM(amount) AS amount, COUNT(*) AS bill_count FROM bill_left GROUP BY customer_id
```

更彻底一点：**不写 SQL 也能自定义对账维度**。用 `dimension` 声明"一笔业务靠什么区分"，用 `measures` 声明聚合口径：

```yaml
# 复合维度：账号 + 资源ID（src/main/resources/rules/resource-recon.yml）
left:
  label: 资源清单
  table: resource_left
  keyType: STRING
  dimension: [account_id, resource_id]   # 引擎生成 CONCAT(account_id, '|', resource_id) AS __biz_key

# 聚合维度：按「客户 + 状态」汇总后再比对（src/main/resources/rules/customer-status-recon.yml）
left:
  table: bill_left
  dimension: [customer_id, status]
  measures:
    amount: SUM(amount)                  # compare 里只能比 measures 产出的别名
    order_count: COUNT(*)
```

声明式维度下引擎生成的取数 SQL（分片条件仍套在最内层，作用于原始数据）：

```sql
-- 非聚合
SELECT CONCAT(account_id, '|', resource_id) AS __biz_key, src.*
  FROM (SELECT * FROM (SELECT * FROM resource_left) src WHERE <分片条件>) src
 ORDER BY __biz_key
-- 聚合
SELECT CONCAT(customer_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS order_count
  FROM (SELECT * FROM bill_left) src
 GROUP BY CONCAT(customer_id, '|', status) ORDER BY __biz_key
```

- `dimension` 与 `keyField` 二选一：声明了维度就**不用（也不许）写 `orderBy`**，引擎固定按生成的 `__biz_key` 升序取数
- 维度既可以是列名列表（多列用 `CONCAT` 拼起来，单列直接用列名、还能命中索引），也可以是任意 SQL 表达式：`dimension: "CONCAT(customer_id, '#', status)"`
- 配了 `measures` 就是聚合维度：引擎先 `GROUP BY __biz_key` 再逐字段比对，`compare` 的字段必须是 `measures` 里的别名，否则启动期直接报错
- **分片条件不能把同一个分组切开**：按 `customer_id` 分片安全，按 `status` 分片会把一个分组劈成两半分到不同分片，两侧各算一半 → 假差异

### 2. 归并式对账 + 流式游标

两侧数据都按业务键升序取，引擎用双指针推进：键相等就比对字段，左边小就是右侧缺失，右边小就是左侧缺失。
JDBC 游标（`TYPE_FORWARD_ONLY` + `fetchSize`）保证只驻留有限行 —— 千万级对账不需要把数据读进内存。

### 3. 分片并行 + 断点重跑

- `recon_job` 记录任务，`recon_shard` 记录每个分片的扫描行数/匹配数/差异数与状态
- 分片并行执行（线程池大小 = `min(规则并行度, 全局并行度)`），单分片失败按 `maxAttempts` 退避重试
- **幂等**：每次分片执行前先 `DELETE FROM recon_diff WHERE job_id=? AND shard_id=?`，重跑不会让差异表翻倍
- 重跑只做 `FAILED` 的分片，已经跑完的分片一次都不重复计算

> **切分口径必须统一**：分片条件只能依赖对账维度（`keyField` 或 `dimension` 里的列），不能让两侧各按自己的代理主键
> （如 `MOD(id, {shards}) = {index}`）切分 —— 那样同一笔业务会被切到不同分片，两边都看不到对方，结果是一堆
> 假缺失。解析器会在 `where` 完全不引用业务键时打 WARN 提醒。

> **切分谓词要能命中索引**：`WHERE MOD(SUBSTRING(order_no, …)) = {index}` 这类"列上套函数"的谓词用不了索引，
> 每个分片都要全表扫一遍。用百万级数据实测（`docs/benchmark.md`）：H2 上同一份数据 8 分片 37.4 秒、1 分片 4.3 秒，
> **差异结果完全一致，但分片越多越慢**；换到独立 MySQL 进程上则是 8 分片 2.3 秒、1 分片 6.4 秒 ——
> 并行全表扫的收益盖过了重复读，但把分片桶做成索引列后还能再快一倍（8 分片 1.08 秒）。
> 要让分片真的提速，先把分片桶做成索引列
> （MySQL 8 函数索引 `CREATE INDEX … ((MOD(n, 8)))`，或生成列 + 普通索引，或数据写入时就物化这一列），规则里只引用这个列，
> 而且最好让"桶列 + 排序键 + 要比较的列"共用一个复合覆盖索引。

### 4. 差异分类 + 归因

引擎内部的差异语义固定为四类（`DiffKind`）：`VALUE_MISMATCH` / `MISSING_LEFT` / `MISSING_RIGHT` / `DUPLICATE_KEY`；
规则里的 `diffType` 是自由字符串（`AMOUNT_MISMATCH`、`STATUS_MISMATCH`…），归因只管这四类，规则怎么改名都不影响。

归因有两级，默认纯离线：

- `RuleBasedExplainer`：零成本中文模板，带 delta / 相对比例 / 常见原因话术
- `LlmDiffExplainer`：OpenAI 兼容 `/chat/completions`，批量一次请求解释多条；**没配 key、超时、模型乱答都会安静落回模板**

```bash
export RECON_LLM_ENABLED=true
export RECON_LLM_BASE_URL=https://api.openai.com/v1
export RECON_LLM_API_KEY=sk-...
./mvnw spring-boot:run
```

## 规则库：换对账维度不用改代码、不用重启

规则本身也是数据，存在 `recon_rule` 表里。提交一次就是**一版新规则**，立即生效：

```bash
curl -X POST http://localhost:8080/api/rules \
     -H 'Content-Type: application/yaml' -H 'X-Operator: alice' \
     --data-binary @src/main/resources/rules/resource-recon.yml
```

- `code` 相同 = 升级同一个规则（版本号自增），`code` 不同 = 新增一条规则
- 每个任务把当时生效的**版本号 + 规则快照**钉在自己身上：之后改规则、停用规则，历史任务重跑和补归因用的仍是当时那份定义，不会出现"同一个 jobId 两种结果"
- `classpath:rules/*.yml` 只在规则库里没有该 `code` 时作为 v1 导入（`recon.seed-rules: false` 可关闭）；生产上也可以把规则目录挂成 `file:./rules/`
- 请求级内联规则：`POST /api/jobs` 带 `rule`（JSON）或 `ruleYaml` 字段、MCP 的 `recon_run` 带 `ruleJson`，用于临时试算 —— 不落库、不产生版本

> 提示：运行时提交规则 = 运行时改 SQL，生产上这个入口要挂在网关鉴权后面（示例里的 `X-Operator` 只是记操作人）。

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/jobs` | 发起对账，`{"ruleCode":"BILL_VS_SETTLE","batchId":"2026-10-11","sync":true}`；也可用 `rule` / `ruleYaml` 内联一份不落库的规则 |
| GET | `/api/jobs` | 任务列表 |
| GET | `/api/jobs/{id}` | 任务详情（含生效的 `ruleVersion`）+ 每个分片状态 + 差异分类汇总 |
| POST | `/api/jobs/{id}/retry?onlyFailed=true` | 断点续跑（按任务自带的规则快照执行） |
| GET | `/api/diffs?jobId=1&diffType=AMOUNT_MISMATCH&offset=0&limit=50` | 差异分页查询 |
| GET | `/api/diffs/summary?jobId=1` | 差异按类型 / 严重级别汇总 |
| POST | `/api/diffs/{id}/explain` | 单条归因 |
| POST | `/api/diffs/explain?jobId=1&limit=200` | 批量补齐归因 |
| GET | `/api/rules` | 规则库列表（每个 `code` 的最新版本 + 对账维度） |
| GET | `/api/rules/{code}` | 规则详情 + 版本历史 |
| POST | `/api/rules` | 提交一版新规则（`Content-Type: application/json` 或 `application/yaml`，`X-Operator` 记操作人） |
| DELETE | `/api/rules/{code}` | 停用规则（版本保留，历史任务仍能用快照重跑） |
| POST | `/api/models/{ruleCode}?shards=4&operator=ops` | 预处理物化：把大表按该规则的粒度算成模型表，并注册 `<CODE>__MODEL` 规则（等价于 MCP `recon_build_model`） |
| GET | `/api/models` | 已物化的模型表清单（规则 / 版本 / 粒度 / 行数 / 构建耗时 / 构建时间） |

## 用 MCP 让 Agent 直接调对账

REST 是给人和系统的，MCP 是给 Agent 的：模型自己决定调 `recon_run` 跑对账、调 `recon_list_diffs` 看差异、
调 `recon_explain_job` 拿归因，不用人拼 URL。两个入口共用同一套 service（`ReconEngine` / `ReconRepository` / `ExplainService`），
结果必然一致。

```bash
./mvnw -DskipTests package
java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mcp     # stdio，8 个工具
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar                    # 不装客户端也能验证
```

工具：`recon_list_rules` / `recon_define_rule` / `recon_run` / `recon_build_model` / `recon_job_summary` / `recon_list_diffs` / `recon_explain_diff` / `recon_explain_job`。
其中 `recon_define_rule` 让 Agent 自己提交一版新规则（换对账维度、换比对字段，立即生效），`recon_run` 的 `ruleJson` 参数可以内联一份规则临时试算，
`recon_build_model` 把大表按规则粒度提前物化成模型表（之后每轮只读模型表，实测 21.47 s → 7.8 ms，见 [docs/benchmark.md](docs/benchmark.md) §3.10）；
注意它只对"每轮都要重付的源侧扫描 + 汇总"有收益：汇总口径的规则在真实 MySQL 200 万行上实测 3.6 s → 0.16 s，而纯行级明细规则物化后行数不变，建一次 28.6 s、之后每轮还更慢（5 s → 12 s），**这种规则不要建模型表**（§3.10.2）。
客户端配置、真实会话输出、以及 stdio 传输的两个坑（logback 状态输出会污染 stdout、2.x 起 SDK 的制品拆分）
见 [docs/mcp.md](docs/mcp.md)。

## 生产使用（MySQL）

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

`application-mysql.yml` 已带上关键参数 `useCursorFetch=true`（否则 MySQL 驱动会把结果集整体拉进内存，流式取数形同虚设）
与 `rewriteBatchedStatements=true`（差异批量写入提速）。生产库的表结构给 DBA 执行
`src/main/resources/schema-mysql.sql`（含 `recon_rule` 规则表，MySQL 8.0.29 上从空库连跑两遍验证通过）；
`schema.sql` 是 H2 用的，里面的 `CREATE INDEX IF NOT EXISTS` MySQL 不支持。
改动规则推荐走 `POST /api/rules` 而不是改文件；规则目录也可以挂 `file:./rules/` 当首次导入的种子
（**只导一次**，同名规则码不会被覆盖）。连接池默认 24：每个分片同时持左右两个游标，池子至少要
`2 × recon.engine.parallelism`，否则会出现"8 个分片全在等连接"的假慢（实测慢 6~11 倍，见 `docs/benchmark.md`）。

已经用旧版本建过库的，升上来只要补这一段（`spring.sql.init.mode=never`，DDL 由 DBA 执行）：

```sql
-- 任务钉住"跑的时候用的是哪一版规则"，重跑与归因都按它还原
ALTER TABLE recon_job ADD COLUMN rule_version INT NULL;
ALTER TABLE recon_job ADD COLUMN rule_snapshot VARCHAR(8000) NULL;

-- 规则库：同一个 code 多版本共存，任务创建后改规则不影响已跑完的任务
CREATE TABLE recon_rule (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    version    INT          NOT NULL,
    spec_json  VARCHAR(8000) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by VARCHAR(64),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_rule_code_version ON recon_rule (code, version);
```

MySQL 没有 `IF NOT EXISTS` 的加列语法，重复执行会报 `Duplicate column name`，属预期。

千万级数据的造数与压测方法见 [docs/benchmark.md](docs/benchmark.md)。

## 压测：百万级实测（H2 一键复现 + 独立 MySQL 真机数字）

```bash
./mvnw -Dtest=BenchH2Test -Dsurefire.excludedGroups= \
    -Dsurefire.argLine="-Xmx2g -Dfile.encoding=UTF-8" test
```

| 跑法 | 分片 | 耗时 | 左侧扫描 | 右侧扫描 | 差异 |
| --- | --- | --- | --- | --- | --- |
| `BIG_VS_SETTLE` | 8 | 37,426 ms | 1,000,000 | 1,000,100 | 800 |
| `BIG_VS_SETTLE_SINGLE` | 1 | 4,334 ms | 1,000,000 | 1,000,100 | 800 |

两次的差异分类逐项相同（`AMOUNT_MISMATCH=400 / STATUS_MISMATCH=100 / MISSING_RIGHT=100 / MISSING_LEFT=100 / DUPLICATE_KEY=100`），
测试里就是这么断言的：**分片只影响耗时，不影响结果**。8 分片反而更慢，原因与解法见
[docs/benchmark.md](docs/benchmark.md)。

### 独立 MySQL 进程上的真机数字（引擎对 2M×2，堆压 `-Xmx256m`）

| 口径（都是 501 条差异，结果逐条一致） | 分片 | 墙钟 | 备注 |
| --- | --- | --- | --- |
| 窄投影 4 列 | 1 | 6,367 / 6,482 ms | 覆盖索引有序读，没有排序 |
| 宽投影 51 列（`SELECT *`） | 1 | 67,268 / 67,593 ms | 优化器退化成全表扫 + filesort，**10.6 倍** |
| 函数谓词 `MOD(CAST(SUBSTRING…) AS UNSIGNED)` | 8 | 2,106 ~ 2,329 ms | 每片读全表 199.99 万行 |
| 桶列 `shard_bucket = {index}` + 复合覆盖索引 | 8 | 1,083 ~ 1,159 ms | 每片只读 26 万行 |
| 桶列口径 + 连接池 16（改默认值之前） | 8 | 11,928 ms | 分片全在等连接，见 benchmark.md 3.5 |

`-Xmx256m` 跑完四个口径：991 次 GC、**0 次 Full GC**、GC 后堆 2~38 MB（形态是锯齿，不随扫描行数上升）。
装置（SQL / PowerShell 脚本 / 规则）原样入库在 [`tools/mysql-bench/`](tools/mysql-bench/)，
完整数字、EXPLAIN 原文与"被压测逼出来的 5 个问题"见 [docs/benchmark.md](docs/benchmark.md)
（文首有 **结论速览**：每条结论一句话 + 关键数字）。

## 项目结构

```
src/main/java/io/github/dolow/reconlens/
├── dsl/        规则解析与校验（YAML / JSON → RuleSpec，错误配置在这里就被拦住；含声明式维度 DSL）
├── rule/       规则库（RuleCatalog）：版本、缓存、内联规则、classpath 种子导入
├── engine/     归并匹配、容差比较、分片规划、并行编排、流式游标
├── store/      JdbcTemplate 持久化（任务 / 分片 / 差异 / 规则版本）
├── explain/    归因：规则模板 + 大模型，统一入口 ExplainService
├── web/        REST 接口（对账任务 + 规则 CRUD + 模型表构建）
├── model/      预处理物化：把大表按规则粒度提前算成模型表（影子表切换，MySQL / H2 方言只在这里）
├── mcp/        MCP server（stdio）：把对账能力暴露成 Agent 可调用的 8 个工具
└── bootstrap/  启动演示
docs/           架构说明、规则 DSL 手册、MCP 接入、压测方法
tools/          造数脚本、MCP stdio 冒烟客户端
```

## 后续计划

- [ ] `recon_shard` 落的检查点支持「同一分片内部」续跑（当前粒度是分片）
- [ ] `shard.strategy: RANGE`：按业务键范围切分。当前 where 模板只有 `{index}` / `{shards}` 两个占位符，
      所有分片共用一段 where，表达不了"每个分片一段连续键"，而键范围才是真正走索引的切法 ——
      这个缺口是这次百万级压测逼出来的
- [ ] 模型表的刷新策略：现在源数据变了要手动再调一次 `recon_build_model`（全量重算 + 影子表切换），
      `recon_model` 里只记了 `created_at` 能看新鲜度，还没有"定时刷新 / 增量刷新 / 过期自动失效"
- [ ] 差异结果导出（CSV / 飞书表格）与按批次对比
- [ ] 规则的多环境发布（草稿 → 审核 → 生效）与操作审计
- [ ] 归因结果回写人工标注，做一个"差异原因"知识库

## 开发与贡献

```bash
./mvnw test                                             # 79 个用例，CI 跑的就是这条
./mvnw -DskipTests package                              # 产出 target/recon-lens-0.1.0.jar
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar    # MCP 协议级冒烟（Node 18+）
```

CI（[build.yml](.github/workflows/build.yml)）在每次 push / PR 上跑测试 + MCP 冒烟；推一个 `v*` tag 会触发
[release.yml](.github/workflows/release.yml) 自动发 Release 并附上可执行 jar。改动记录见
[CHANGELOG.md](CHANGELOG.md)，提 PR 前请读一下 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

Apache-2.0
