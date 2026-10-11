# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 新增

- **自定义对账维度（声明式）**：`left`/`right` 新增 `dimension`（列名列表或任意 SQL 表达式，引擎统一生成为键列 `__biz_key`）、`dimensionSeparator`、`measures`（`别名: SQL 表达式`，声明后就是聚合维度，引擎先 `GROUP BY` 再比对）、`where`。
  `dimension` 与 `keyField` 二选一，声明维度时不需要（也不允许）写 `orderBy`；复合键按 `CONCAT` 拼接，单列维度直接用列名以命中索引
- **规则库与热更新**：规则存 `recon_rule` 表（`code` + 自增 `version`），新增规则 CRUD —— `GET /api/rules`、`GET /api/rules/{code}`（含版本历史）、`POST /api/rules`（JSON 或 YAML，提交即生效、不用重启）、`DELETE /api/rules/{code}`（停用，历史版本保留）
- **任务钉住规则版本**：`recon_job` 增加 `rule_version` + `rule_snapshot`，任务把当时生效的规则快照留在自己身上；重跑与补归因都走快照，改规则不会让历史任务换一种结果
- **请求级内联规则**：`POST /api/jobs` 支持 `rule` / `ruleYaml` 字段，MCP `recon_run` 支持 `ruleJson`（不落库、不产生版本）
- **MCP 工具 6 → 7**：新增 `recon_define_rule`（Agent 可自己提交一版新规则），`recon_run` 支持内联规则体
- **声明式维度演示**：新增 `rules/resource-recon.yml`（复合维度：账号 + 资源ID）与 `rules/customer-status-recon.yml`（聚合维度：客户 + 状态）及配套演示数据；测试 40 → 68 个
- **文档守卫测试**：`MermaidDocsTest` 扫根目录与 `docs/` 下所有 markdown 的 ```` ```mermaid ```` 块，禁掉会破坏 GitHub 渲染的花括号（`{shards}` 这种标签文本；合法的 diamond 节点 `A{判断}` 放行），图数为 0 时同样报错。起因：README 架构图的边标签里写了 `{shards} {index}`，mermaid 把 `{` 当成 diamond 起始符，GitHub 上整张图渲染失败（`Parse error ... got 'DIAMOND_START'`）
- 修复：单列维度不再生成 `CONCAT(order_no)`（H2 直接报错），单列直接用列名
- 修复：`left.where` / `right.where` 之前"解析了但没人用"（静默无效），现在与分片条件是 **AND** 关系真正生效；写了 `{index}` / `{shards}` 占位符会在解析期报错（分片条件只能写 `shard.whereLeft/whereRight`）

### 变更

- **MCP SDK 0.18.4 → 2.0.1**：上游 2.x 把制品拆成 `mcp-core` + 各 JSON 实现，聚合包 `mcp` 只带 Jackson 3；
  本项目跑在 Spring Boot 3（Jackson 2）上，因此显式依赖 `mcp-core` + `mcp-json-jackson2`（代码零改动，
  `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper` 包名未变，依赖树里也不会多出一套 Jackson 3）。
  协议版本随之从 `2024-11-05` 升到 `2025-06-18`：客户端请求新版本不再回落、不再打 WARN。
- **CI 与文档统一走仓库自带的 `./mvnw`**（wrapper 分发 Maven 3.10.0）：本地和 CI 用同一套 Maven，贡献者只要有 JDK 17 就能构建。

### 修复

- **游标打开失败时的连接泄漏**：`JdbcRowCursor` 在取连接/建语句抛异常时没有归还 `Connection`，压力一上来就把连接池占满（实测 8 分片 × 左右两个游标 = 16，正好占满默认池，日志里是 `SQLTransientConnectionException ... total=16, active=16, idle=0, waiting=7`）。新增 `JdbcRowCursorTest` 覆盖这条路径。
- **`error_msg` 超长导致任务落不了库**：`ReconRepository.finishJob` 落库前不截断（`markShardFailed` 是截断的），失败原因一长 MySQL 就报 `Data too long for column 'error_msg'`，`POST /api/jobs` 直接 500、任务状态没落库。现在统一截到 1000 字符，失败原因最多拼 3 条并附 `共 N 个分片失败`；回归测试 `ReconEngineEndToEndTest.overlongFailureReasonIsTruncatedInsteadOfBlowingUp`。
- **连接池默认值 16 → 24**：每个分片同时持有左右两个游标，8 分片就把 16 条连接占满，写差异 / 写分片状态只能等到超时再重试 —— 结果仍然全对，但耗时涨 6~11 倍（实测 2,329 ms → 13,992 ms，1,083 ms → 11,928 ms）。默认值改成"至少 `2 × engine.parallelism` 再加余量"。
- **MySQL 建表脚本**：新增 `src/main/resources/schema-mysql.sql`（索引写进 `CREATE TABLE`，MySQL 8.0.29 上从空库连跑两遍验证通过）。原来的 `schema.sql` 里有 `CREATE INDEX IF NOT EXISTS`，MySQL 不支持，README 又让 DBA 照着它建表 —— 照着跑会停在那两行索引语句上。

### 文档

- **`docs/benchmark.md` 第三节改成真机数字**：独立 MySQL 8.0.29 进程上对 2M×2 真对账、`-Xmx256m` 跑完（991 次 GC / 0 次 Full GC / GC 后堆 2~38 MB）、8 分片两种分片谓词的计划与墙钟对照、投影宽度导致的执行计划退化（10.6 倍）、连接池饿死的对照实验，以及千万行大表上排序 / 聚合 / 分片 / 分区 / 倾斜的纯 SQL 对照。装置（SQL、PowerShell 脚本、规则）入库到 `tools/mysql-bench/`，可以照着重跑。
- **新增 3.8 状态计数器的可归因增量**：`SHOW GLOBAL STATUS` 是累计值、`FLUSH STATUS` 只清会话计数器，所以只能"相邻两张状态表相减"；场景文件头部的 `ALTER/ANALYZE/CTAS` 会污染首块，于是新增 `tools/mysql-bench/24_counter_probe.sql`（文件内零 DDL，逐块可归因）复测首块。结论：排序读的行数完全相同、差别在 `Sort_merge_passes`（窄行 581 趟 vs 50 列宽行 2,651 趟，因为排序缓冲按行宽切）；覆盖索引让 `Sort_rows` 直接归零；表达式键分组让 `Innodb_rows_read` 从 10M 变 20M 并落一次临时表；分区裁剪与表达式替换的收益是**读得更少**（708,163 vs 10,000,000 行；命中桶列 1,300,000 vs `AS SIGNED` 10,000,000 行）；同时列出不可归因的块（`S4-2/S4-3`、`S4-5`、`17_s4b`、各文件首块）与原因。3.7 的数据倾斜行补上 S6-6 采样边界的真实分段行数（4,999,999 / 0 / 0 / 2,000,000 / 0 / 1,000,000 / 750,021 / 1,249,980，max/avg = 4.00、3 个空段）。

## [0.1.0] - 2026-10-11

首个可用版本：一个能用 YAML 规则跑千万级数据的对账引擎，同时是一个 MCP server。

### 新增

- **规则 DSL（YAML）**：`left`/`right` 支持表名或自定义 SQL、`shard` 分片模板、`compare` 字段比较（绝对 / 相对容差、忽略大小写）、`missing` 单边缺失、`duplicate` 重复键、`options.maxAttempts`；
  启动期做结构校验（code 命名、orderBy 必须等于 keyField、占位符完整性、diffType 不重复），运行期做结果集列名校验
- **归并式对账引擎**：两侧按业务键升序流式取数（`TYPE_FORWARD_ONLY` + `fetchSize`）+ 双指针归并，内存占用与数据量解耦；同键组内按位配对，重复键只报一条
- **分片并行**：`{index}` / `{shards}` 占位符生成分片计划，线程池并行度为 `min(规则并行度, 全局并行度)`，单分片失败按 `maxAttempts` 退避重试
- **幂等断点重跑**：分片即检查点，`retry?onlyFailed=true` 只重跑失败分片；每个分片执行前先删本分片旧差异，重跑不会让差异表翻倍
- **差异分类与归因**：固定四类语义（`VALUE_MISMATCH` / `MISSING_LEFT` / `MISSING_RIGHT` / `DUPLICATE_KEY`）+ 两级解释器
  （离线中文模板 `RuleBasedExplainer`、OpenAI 兼容 `LlmDiffExplainer`，无 key / 超时 / 乱答都安静落回模板）
- **REST API**：发起与查询任务、差异分页与汇总、单条与批量归因、规则清单
- **MCP server（stdio）**：6 个工具 —— `recon_list_rules` / `recon_run` / `recon_job_summary` / `recon_list_diffs` / `recon_explain_diff` / `recon_explain_job`，
  与 REST 共用同一套 service
- **开箱演示与生产路径**：H2 内存库自带 20 条账单 / 21 条结算数据（埋 5 类典型差异），启动即打印一份演示对账报告；MySQL profile 已带 `useCursorFetch=true` / `rewriteBatchedStatements=true`
- **测试与工具**：40 个测试（DSL 校验、容差比较、归并匹配、分片规划、端到端、MCP 工具）；`tools/gen_bill_data.sql` 百万级造数、`tools/mcp-smoke.mjs` 协议级冒烟客户端
- **文档**：`docs/architecture.md`（架构与设计决定）、`docs/rule-dsl.md`（规则手册）、`docs/mcp.md`（Agent 接入）、`docs/benchmark.md`（百万级压测）

### 实测

- 百万级（H2，JDK 17，`-Xmx2g`）：`BIG_VS_SETTLE` 8 分片 37,426 ms、`BIG_VS_SETTLE_SINGLE` 1 分片 4,334 ms，
  两次差异分类逐项一致（共 800 条）—— **分片只影响耗时，不影响结果**；8 分片更慢的原因（列上套函数的谓词用不到索引）与解法见 `docs/benchmark.md`
- MCP 冒烟：`initialize` → `tools/list`（6 个）→ 跑对账 → 查差异 → 批量归因，stdout 噪声 0 行，关闭 stdin 后进程 exit 0

[0.1.0]: https://github.com/Dolo-w/recon-lens/releases/tag/v0.1.0
