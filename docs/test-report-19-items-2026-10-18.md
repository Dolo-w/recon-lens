# 19 条优化项：改动与实测报告（2026-10-18）

> 对象：`recon-lens`（`HEAD` 起于 `c886ea6`）
> 要求：把评审列出的 19 条可优化项全部改掉、全部实测、出一份报告
> 结论：**19 条全部落地；全量测试 422 用例 / 0 失败；空白库 + 62 万行/片的端到端链路全部跑通；过程中另外发现并修掉 3 个真实缺陷。**

---

## 0. 结论速览

| 项目 | 改动前 | 改动后 |
| --- | --- | --- |
| 全量测试 | 409 用例 | **422 用例 / 0 失败 / 0 错误**（+13） |
| `__MODEL` 规则 | 建表即落 ACTIVE，可立刻跑生产 | 默认落 DRAFT，试算 PASS 后由人审批才生效 |
| Agent 自批规则 | 一次 MCP 调用 47 ms 批掉自己起草的规则 | 必须出示人领的一次性确认码；无码直接被 schema 拒 |
| 篡改检测 | 只有冷缓存那一刻有效，改库不重启永远发现不了 | 每次 `resolve` 先读一行守卫，改库后**下一个请求**即 400 并降级 REVIEWING |
| 写接口鉴权 | 全裸（`/api/jobs`、`/api/models`、`DELETE /api/rules`） | `X-Api-Key` 常量时间比较，缺/错 403 `api_key_required` |
| 文件导入 | GBK 静默变 U+FFFD；无字节上限；多余列静默丢弃 | 非 UTF-8 直接报错并给转换命令；512 MB 上限；多列报行号列数 |
| 生产路径摘要开销 | 每条差异多做一次归一化 + sha256（≈0.5 µs/条） | 只有试算路径算摘要 |
| 试算 T2 | 摘要溢出 → 两张空表相等 → 假 PASS | 溢出判 `INCONCLUSIVE`（`DIGEST_OVERFLOW`），可批性 `BLOCK_SIGNABLE` |
| 试算阻塞 | `recon_define_rule` 同步等 14.9~23.9 s | 排队后 62~83 ms 返回，`recon_trial_status` 轮询 |
| 导入阻塞 | 62 万行同步灌 21~26 s，客户端超时即前功尽弃 | `async: true` 4 ms 返回 taskId + `recon_import_status` |
| 关键索引 | 试算窗口回查整段扫描；任务列表用不上索引 | `idx_diff_job_key`、`idx_job_kind_id` 实测被选中 |
| 端到端跑批 | — | 62.5 万 × 62.4 万行，11 s，5,747 条差异，四类与独立 Python 口径**逐类相同** |

---

## 1. 验证环境

| 项 | 值 |
| --- | --- |
| OS / 时区 | Windows 11 / Asia-Shanghai |
| JDK | JBR 21.0.8（`D:\App\ideaIC-2025.3\jbr`），构建用仓库自带 `./mvnw` |
| MySQL | 8.0.28（`D:\App\mysql-8.0.28-winx64`），端口 3307，root 空密码，`sql_mode` 含 `STRICT_TRANS_TABLES` |
| 空库 | `recon_e2e3`，由 `src/main/resources/schema-mysql.sql` 建出 **14 张表 0 行** |
| 数据 | `recon-data` 生成器重跑的 shard0：左 **625,000** 行、右 **624,955** 行，20 列 |
| 对账规则 | `dimension = [customer_name, order_no, pay_time]`，`settle_amount` DECIMAL 容差 `0.01` |
| 服务 | `java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mysql …`，18080，`Started … in 1.6 s` |
| Agent | `--recon.agent.enabled=true`，白名单 `bill_left_shard0,bill_right_shard0`；MCP 工具 **14** 个（关 Agent 时 9 个） |

---

## 2. 19 条逐条：问题 → 改法 → 证据

### A. 安全与审批

**① `POST /api/models` 直接注册生效的 `__MODEL` 规则**
- 问题：`ModelBuilder` 用 `catalog.defineMap(...)` 落库，`lifecycle=ACTIVE`、`trial_verdict=NULL`，建完即可跑生产。
- 改法：新增 `recon.model.require-approval`（默认 `true`）→ 走 `catalog.defineDraft(...)`；控制器把试算排进后台队列并返回 `trialRunId` / `modelRuleLifecycle`。非 Agent 部署可显式关掉退回旧行为（文档写明等于接受机器生成规则免审）。
- 证据：`ModelApprovalTest`（默认 DRAFT + 用草稿跑批 400）；E2E 建模型返回 `modelRuleLifecycle=DRAFT`、`trialRunId=3`，试算 `PASS×FULL` 后 REST 三步审批 → `ACTIVE` → 跑批 `SUCCESS 5,747`。

**② `skipTrial` 不校验生命周期**
- 问题：对已 ACTIVE 的版本调用会把审批时的 `trial_*` 证据改写成"人工跳过"。
- 改法：只接受 `DRAFT` / `REVIEWING`，其余抛 `RuleException` 并说明"已生效版本的试算结论正是 approve 判定可批性的依据"。

**③ "审批入口不对 Agent 开放"只靠包结构约定**
- 问题：ArchUnit 只禁 `agent..` 依赖 `RuleLifecycleService`，审批桥住在 `mcp..` 包天然绕过。
- 改法：抽出 `rule/ApprovalRelay`（只有一个 `confirmApproval(...)`，必须核销一次性确认码），`AgentMcpTools` 只依赖它；ArchUnit 新增"`mcp` 包不得依赖 `RuleLifecycleService`"。
- 证据：`AgentBoundaryTest` 5 用例全绿。

**④ 没有多租户/命名空间边界**
- 改法：`docs/architecture.md` 新增"部署边界：一库一租户"一节 —— 所有表都没有 tenant 列，`batchId` 只是标签不是隔离，「一个库服务多个互相不可见的租户」当前不支持；给出可行方向（按库/按 schema 隔离 + 连接级账号），不做半成品。

### B. 数据正确性

**⑤ 白名单大小写不敏感**
- 问题：`SchemaCatalog.allows()` 用 `equalsIgnoreCase`，导入 `ORDERS` 可让真实表 `orders` 过闸（MySQL `lower_case_table_names=0` 下是两张表）。
- 改法：`equals` 精确匹配（只忽略 `schema.` 前缀），`find()` 缓存键不再小写化。
- 证据：`SchemaCatalogTest` 断言 `BILL_LEFT` 不再命中 `bill_left`。

**⑥ `columns_snapshot VARCHAR(2048)` 溢出**
- 改法：两套 schema 改为 `TEXT`；迁移脚本 `docs/migrations/2026-10-18-perf-indexes.sql` 一并给 `ALTER TABLE … MODIFY`。

**⑦ 文件导入无事务、失败留半成品**
- 现状复核：`loadRows` 失败已 `safeDrop`，注册失败也 `safeDrop`（`FileImporterTest` 有回滚用例）；`columns_snapshot` 溢出导致的"表建了但没注册"由 ⑥ 消除。报告本节记录该判定，不再重复造轮子。

**⑧ T2 摘要只比键、不比值**
- 改法：`DiffSetDigest.line()` 把 `leftValue/rightValue/deltaValue` 一起入哈希；`deltaValue` 先 `stripTrailingZeros` 归一，避免 `0.10` 与 `0.1` 判成不同。
- 证据：`DiffSetDigestTest` 新增两例（改值 → 摘要变；`0.1`/`0.10` → 摘要同）。

**⑨ CSV 未闭合引号 / `\N` / Excel 多 Sheet**
- 改法：EOF 仍在引号内 → 明确报错；`\N` 与大小写不敏感 `NULL` 按空值；Excel 新增 `sheet` 参数（名称或序号，越界列出候选）。
- 证据：`FileImporterTest` 新增未闭合引号用例；E2E 导入走 CSV 路径。

**⑩ 类型推断只看前 500 行**
- 改法：`recon.agent.file-import.inference-sample-rows`（默认 2000）；`convert` 失败时报行号、列名与实际值，并提示调大采样行数。

### C. 性能与工程

**⑪ 缺关键索引**
- 改法：`idx_diff_job_key (job_id, biz_key)`、`idx_job_kind_id (kind, id)`，H2/MySQL 两套 schema + 迁移脚本。
- 证据（`EXPLAIN`，14 张表 11,745 条差异的真库）：
  - 试算窗口回查：`type=range, key=idx_diff_job_key, key_len=522`；
  - 任务列表：`type=ref, key=idx_job_kind_id, Extra=Backward index scan; Using index`。

**⑫ 生产路径也算摘要**
- 改法：只有带 `DiffCapture`（试算）才包 `DigestingDiffSink`；生产 `digest == null`、`digestOverflowed == false`。
- 证据：`ShardRunnerDigestTest` 改为"生产路径无摘要 / 试算路径有摘要"。

**⑬ 关键写入无事务**
- 改法：`confirmApproval` / `approve` / `reject` / `skipTrial` 加 `@Transactional`（码核销一并回滚）。
- 证据：`RuleLifecycleServiceTest` 14 用例全绿。

**⑭ 试算无幂等**
- 改法：`TrialTaskRegistry.submitTrial` 入队前按 `code#version` 查内存任务表，已有排队/进行中直接返回原 `runId`。
- 证据：`TrialTaskRegistryTest` 两处断言"同一版本返回同一个 runId"。

**⑮ 重启丢排队任务**
- 改法：`TrialTaskRegistry` 实现 `ApplicationRunner`，启动时把遗留会话标 `BLOCKED`；判定窗口 `recon.agent.stale-run-minutes`（默认 10 分钟），避免 Web/MCP 共库时误伤另一进程正在跑的试算。
- 证据：E2E 重启（`stale-run-minutes=1`）日志 `启动清理：1 条排队/运行中的 Agent 会话被标记为失败`。

**⑯ 没有现成 MCP 客户端**
- 改法：新增 `tools/mcp-client.mjs`（`list` / `call <tool> <json|@file>`），自带握手、协议版本协商、`structuredContent` 打印，零依赖；文档 `docs/mcp.md` 同步。

**⑰ 文件导入同步阻塞**
- 改法：`recon_import_file` 增加 `async: true` → 立即返回 `taskId`；新增工具 `recon_import_status`（QUEUED / RUNNING / SUCCESS（rows）/ FAILED（error）），独立线程池避免与试算互相饿死。
- 证据：E2E 异步提交 **4 ms** 返回 taskId，轮询到 `SUCCESS rows=625000`。

**⑱ 文档口径**
- 改法：`docs/agent-quickstart.md` 补"试算 → 领码 → 审批"的 REVIEWING 前置与 400 语义；`docs/mcp.md` 工具数 9/15、`async`/`sheet`/`inference-sample-rows`/`max-bytes`；README 补 `__MODEL` 默认草稿与文件导入错误文案。

**⑲ demo 默认开且无兜底**
- 改法：默认 profile 才开 `run-demo-on-startup`；`DemoRunner` 包 `try/catch`（演示失败只 WARN，不再让应用起不来）。

---

## 3. 过程中新发现并修掉的 3 个缺陷

| # | 缺陷 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | `recon_trial_status` 按 `ruleCode+version` 查时缺 `message`，MCP SDK 校验失败报"未找到所需属性 message" | 该工具一半用法不可用 | `message` 兜底为"试算结论见上；生效仍需人工审批"，回归用例 `McpToolCatalogTest.trialStatusByRuleCodeAndVersionCarriesTheRequiredMessageField` |
| 2 | `TrialTaskRegistry.executeTrial` 成功/异常两条路径都没写库终态 | 会话永远停在 `RUNNING`，查不到结论也看不出失败原因 | 两条路径都 `runs.finishRun(...)`；E2E 重启后 run #4 = `SUCCESS` |
| 3 | 模型构建器生成的 `recon_model_*` 表不在 Agent 白名单，`__MODEL` 草稿永远 `UNKNOWN_TABLE` | 默认 `require-approval=true` 的部署里模型规则永远无法生效 | `SchemaCatalog.allows()` 放行 `recon_model_[a-z0-9_]{1,80}`（前缀固定、机械派生）；放行后闸门仍查列，表不存在照样报 `UNKNOWN_TABLE` |

---

## 4. 全量测试

```
mvnw -B test
Tests run: 422, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增用例（13 个）：`ModelApprovalTest`(1)、`ApiSecurityTest`(4)、`ApprovalConfirmationTest`(5)、`RuleTamperCacheTest`(1)、`DiffSetDigestTest`(+2)、`TrialTaskRegistryTest` 去重/清理、`ShardRunnerDigestTest` 改写、`FileImporterTest`(+3)、`SchemaCatalogTest` 改写、`TrialVerdictResolverTest`(+1)。

---

## 5. E2E 时间线（空白库，单 shard0）

| 步骤 | 耗时 | 结果 |
| --- | --- | --- |
| 导入 preview（左/右） | 1,463 / 1,469 ms | 625,000 行 × 20 列，类型推断全对 |
| 导入 confirm（同步，左/右） | 21,860 / 21,323 ms | ≈29k 行/秒 |
| 导入 confirm（`async:true`） | **4 ms** 返回 taskId | 轮询到 `SUCCESS rows=625000` |
| `recon_define_rule`（复合键规则） | **62 ms** | 草稿 + 试算排队 |
| 试算（T1–T6） | ≈30 s | `PASS × FULL`，`NO_SIGNOFF_REQUIRED` |
| MCP 无码审批 | 20 ms 被拒 | `缺少必填参数 confirmationCode` |
| 领确认码 → 带码审批 | 41 ms | `ACTIVE`，`confirmationConsumed=true` |
| 同码重放 | 5 ms | `确认码无效：这串确认码已经被用过` |
| 跑批（`customer_name` 有 NULL） | 13 s | `FAILED`，`errorMsg` 给出成因 + 排查 SQL + 清洗办法 |
| 清洗后跑批 | 11 s | `SUCCESS`，`diffRows=5747` |
| 热缓存篡改 | 下一个请求 | 400「内容在审批后被修改…已降级回 REVIEWING」，**无需重启** |
| 建模型（`POST /api/models`） | 9.9~10.4 s | DRAFT v2/v3 + `trialRunId` |
| 模型试算 | ≈45 s | `PASS × FULL`（白名单修复后） |
| 模型规则跑批 | **3.1 s** | `SUCCESS 5747`，四类与源表规则逐类相同 |

### 数据一致性三方对照（shard0）

| 口径 | 差异总数 | 分类 |
| --- | --- | --- |
| 独立 Python（`recon-data/verify_expected.py`，复合键 + 容差 0.01） | 5,747 | — |
| 引擎（原始表） | 5,747 | `AMOUNT_MISMATCH 3240 / DUPLICATE_KEY 106 / MISSING_LEFT 1125 / MISSING_RIGHT 1276` |
| 引擎（模型表 `__MODEL`） | 5,747 | 同上，逐类相同 |

---

## 6. 仍然存在的限制（不在本轮 19 条内）

1. **单实例假设**：试算队列、导入队列、感知巡检的抑制窗口都在进程内；Web 与 MCP 共库部署时，两边各自排队（已用 `stale-run-minutes` 避免互相误标）。
2. **无租户列**：一库一租户，文档已写明。
3. **导入仍是"建表 → 灌数 → 注册"三步无整体事务**：中途失败会 `safeDrop`，但极端情况下（进程被 kill）可能留未注册表；需要时可用 `recon_registered_table` 兜底清理。
4. **`recon_import_file` 的异步任务随进程消失**：stdio MCP 宿主关闭即中断，长导入需要宿主进程保活（文档已注明）。
5. **模型规则重建会累积 `recon_model_*` 表**：目前没有自动清理旧模型表的任务。

---

## 7. 复现命令（节选）

```powershell
# 建库
mysql --host=127.0.0.1 --port=3307 -uroot -e "CREATE DATABASE recon_e2e3"
mysql --host=127.0.0.1 --port=3307 -uroot recon_e2e3 < src\main\resources\schema-mysql.sql

# 起服务
java -Xms256m -Xmx1536m -jar target\recon-lens-0.1.0.jar --spring.profiles.active=mysql `
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/recon_e2e3?useCursorFetch=true&rewriteBatchedStatements=true" `
  --spring.datasource.username=root --spring.datasource.password= `
  --recon.seed-rules=false --recon.agent.enabled=true `
  --recon.agent.schema.tables=bill_left_shard0,bill_right_shard0 `
  --recon.security.api-key=write-key --recon.review.api-key=test-key --recon.review.token-secret=test-secret `
  --recon.agent.file-import.max-rows=1000000 --server.port=18080

# MCP 客户端
node tools\mcp-client.mjs list
node tools\mcp-client.mjs call recon_import_file '@steps\import-left.json'
```

---

## 8. 证据索引

| 内容 | 位置 |
| --- | --- |
| E2E 步骤与响应 | `D:\App\deepseek\workspace\verify-e2e3\`（`steps-*.json`、`last-run.json`、`tools.json`） |
| 服务日志 | `verify-e2e3\web-out*.log`、`web-err*.log` |
| 独立对账口径脚本 | `D:\App\deepseek\workspace\recon-data\verify_expected.py` |
| 迁移脚本 | `recon-lens\docs\migrations\2026-10-18-perf-indexes.sql` |
| 历史验证记录 | `docs/e2e-reverify-2026-10-18.md`、`docs/e2e-fix-verify-2026-10-18.md` |
