# recon-lens

<p>
  <a href="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml"><img alt="build" src="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml/badge.svg"></a>
  <a href="https://github.com/Dolo-w/recon-lens/releases"><img alt="release" src="https://img.shields.io/github/v/release/Dolo-w/recon-lens?sort=semver"></a>
  <img alt="java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="spring boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-6db33f">
  <img alt="mcp" src="https://img.shields.io/badge/MCP-stdio-6f42c1">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

**配置化的对账引擎**：YAML 写规则 → 分片并行归并对账 → 差异自动分类 → 规则模板 / 大模型给出归因；
同时是一个 MCP server，Agent 可以直接调这些能力。

> Reconciliation engine for money pipelines: rule-as-YAML, shard-parallel merge join, streamed cursors,
> idempotent re-runs, and diff attribution (rule templates + optional OpenAI-compatible LLM).

---

## 它解决什么问题

线上对账脚本通常长这样：一段硬编码 SQL + 一堆 `if`，跑出几万条差异丢给运营，然后运营来问"为什么差"。
真出事的时候更麻烦 —— 脚本跑到一半挂了，只能整批重来；数据涨到千万级，直接 OOM。

recon-lens 把这件事拆成四件确定的事：

| 传统脚本 | recon-lens |
| --- | --- |
| 对账逻辑写在代码里，改规则要发版 | 规则是 YAML，改完重启即生效，且上线前被 DSL 校验拦一遍 |
| 全量 `SELECT` 进内存再比对 | 按业务键升序流式取数 + 双指针归并，内存与数据量无关 |
| 失败只能整批重跑，差异表越跑越脏 | 按分片记录进度，只重跑失败分片；先删本分片旧差异再写，重跑天然幂等 |
| 只给差异数量，原因靠人猜 | 每条差异自动分类 + 生成归因（规则模板兜底，可接大模型） |

## 30 秒跑起来

需要 JDK 17+。没装 Maven 就用仓库自带的 wrapper（Windows 用 `mvnw.cmd`）：

```bash
./mvnw spring-boot:run          # 或 mvn spring-boot:run
```

启动日志里会直接打出一份演示对账报告（H2 内存库自带 20 条账单 / 21 条结算数据，埋了 5 类典型差异）：

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
    R[rules/*.yml] -->|RuleParser 启动期校验| REG[RuleRegistry]
    API[POST /api/jobs] --> ENG[ReconEngine]
    AGENT[Agent / Claude Desktop] -->|stdio JSON-RPC| MCP[MCP server<br/>6 个工具]
    MCP --> ENG
    MCP --> DB
    REG --> ENG
    ENG -->|ShardPlanner 替换<br/>{shards} {index}| SP[分片计划 ×N]
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

### 2. 归并式对账 + 流式游标

两侧数据都按业务键升序取，引擎用双指针推进：键相等就比对字段，左边小就是右侧缺失，右边小就是左侧缺失。
JDBC 游标（`TYPE_FORWARD_ONLY` + `fetchSize`）保证只驻留有限行 —— 千万级对账不需要把数据读进内存。

### 3. 分片并行 + 断点重跑

- `recon_job` 记录任务，`recon_shard` 记录每个分片的扫描行数/匹配数/差异数与状态
- 分片并行执行（线程池大小 = `min(规则并行度, 全局并行度)`），单分片失败按 `maxAttempts` 退避重试
- **幂等**：每次分片执行前先 `DELETE FROM recon_diff WHERE job_id=? AND shard_id=?`，重跑不会让差异表翻倍
- 重跑只做 `FAILED` 的分片，已经跑完的分片一次都不重复计算

> **切分口径必须统一**：分片条件只能依赖业务键（`where` 里出现 `keyField`），不能让两侧各按自己的代理主键
> （如 `MOD(id, {shards}) = {index}`）切分 —— 那样同一笔业务会被切到不同分片，两边都看不到对方，结果是一堆
> 假缺失。解析器会在 `where` 完全不引用业务键时打 WARN 提醒。

> **切分谓词要能命中索引**：`WHERE MOD(SUBSTRING(order_no, …)) = {index}` 这类"列上套函数"的谓词用不了索引，
> 每个分片都要全表扫一遍。用百万级数据实测（`docs/benchmark.md`）：同一份数据，8 分片 37.4 秒、1 分片 4.3 秒，
> **差异结果完全一致，但分片越多越慢**。要让分片真的提速，先把分片桶做成索引列
> （MySQL 8 函数索引 `CREATE INDEX … ((MOD(n, 8)))`，或生成列 + 普通索引），规则里只引用这个列。

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

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/jobs` | 发起对账，`{"ruleCode":"BILL_VS_SETTLE","batchId":"2026-10-11","sync":true}` |
| GET | `/api/jobs` | 任务列表 |
| GET | `/api/jobs/{id}` | 任务详情 + 每个分片状态 + 差异分类汇总 |
| POST | `/api/jobs/{id}/retry?onlyFailed=true` | 断点续跑 |
| GET | `/api/diffs?jobId=1&diffType=AMOUNT_MISMATCH&offset=0&limit=50` | 差异分页查询 |
| GET | `/api/diffs/summary?jobId=1` | 差异按类型 / 严重级别汇总 |
| POST | `/api/diffs/{id}/explain` | 单条归因 |
| POST | `/api/diffs/explain?jobId=1&limit=200` | 批量补齐归因 |
| GET | `/api/rules` | 当前实例加载了哪些规则 |

## 用 MCP 让 Agent 直接调对账

REST 是给人和系统的，MCP 是给 Agent 的：模型自己决定调 `recon_run` 跑对账、调 `recon_list_diffs` 看差异、
调 `recon_explain_job` 拿归因，不用人拼 URL。两个入口共用同一套 service（`ReconEngine` / `ReconRepository` / `ExplainService`），
结果必然一致。

```bash
./mvnw -DskipTests package
java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mcp     # stdio，6 个工具
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar                    # 不装客户端也能验证
```

工具：`recon_list_rules` / `recon_run` / `recon_job_summary` / `recon_list_diffs` / `recon_explain_diff` / `recon_explain_job`。
客户端配置、真实会话输出、以及 stdio 传输的两个坑（logback 状态输出会污染 stdout、2.x 起 SDK 的制品拆分）
见 [docs/mcp.md](docs/mcp.md)。

## 生产使用（MySQL）

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

`application-mysql.yml` 已带上关键参数 `useCursorFetch=true`（否则 MySQL 驱动会把结果集整体拉进内存，流式取数形同虚设）
与 `rewriteBatchedStatements=true`（差异批量写入提速）。生产库的表结构见 `src/main/resources/schema.sql`，
规则目录建议挂 `file:./rules/`，改规则不用重新构建。

千万级数据的造数与压测方法见 [docs/benchmark.md](docs/benchmark.md)。

## 压测：百万级实测（仓库内可复现，不需要 MySQL）

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

## 项目结构

```
src/main/java/io/github/dolow/reconlens/
├── dsl/        规则解析与校验（YAML → RuleSpec，错误配置在这里就被拦住）
├── engine/     归并匹配、容差比较、分片规划、并行编排、流式游标
├── store/      JdbcTemplate 持久化（任务 / 分片 / 差异）
├── explain/    归因：规则模板 + 大模型，统一入口 ExplainService
├── web/        REST 接口
├── mcp/        MCP server（stdio）：把对账能力暴露成 Agent 可调用的 6 个工具
└── bootstrap/  启动演示
docs/           架构说明、规则 DSL 手册、MCP 接入、压测方法
tools/          造数脚本、MCP stdio 冒烟客户端
```

## 后续计划

- [ ] `recon_shard` 落的检查点支持「同一分片内部」续跑（当前粒度是分片）
- [ ] `shard.strategy: RANGE`：按业务键范围切分。当前 where 模板只有 `{index}` / `{shards}` 两个占位符，
      所有分片共用一段 where，表达不了"每个分片一段连续键"，而键范围才是真正走索引的切法 ——
      这个缺口是这次百万级压测逼出来的
- [ ] 差异结果导出（CSV / 飞书表格）与按批次对比
- [ ] 规则热重载（不重启刷 `file:` 目录）
- [ ] 归因结果回写人工标注，做一个"差异原因"知识库

## 开发与贡献

```bash
./mvnw test                                             # 40 个用例，CI 跑的就是这条
./mvnw -DskipTests package                              # 产出 target/recon-lens-0.1.0.jar
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar    # MCP 协议级冒烟（Node 18+）
```

CI（[build.yml](.github/workflows/build.yml)）在每次 push / PR 上跑测试 + MCP 冒烟；推一个 `v*` tag 会触发
[release.yml](.github/workflows/release.yml) 自动发 Release 并附上可执行 jar。改动记录见
[CHANGELOG.md](CHANGELOG.md)，提 PR 前请读一下 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

Apache-2.0
