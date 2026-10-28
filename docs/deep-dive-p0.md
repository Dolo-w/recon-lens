# P0 实现详解：零依赖对账引擎

> 适用档位：`P0`（无模型、无向量库、无 Redis）。一个 jar、一个 H2/MySQL、一套工具。
> 本文只讲**怎么做的**，不讲"为什么需要 P0"；边界与取舍见 `docs/tech-by-stage.md` §9。

---

## 1. 这一档到底有什么

| 能力 | P0 状态 | 实现在哪 |
|---|---|---|
| 配置化对账（规则即 YAML） | 有 | `dsl/RuleSpec` + `engine/ReconEngine` |
| 分片并行 + 只重跑失败分片 | 有 | `engine/ShardPlanner`、`engine/ShardRunner` |
| 归并式比对（内存可控） | 有 | `engine/MergeMatcher`、`engine/JdbcRowCursor` |
| 差异落库 + 模板归因 | 有 | `engine/BatchDiffSink`、`explain/RuleBasedExplainer` |
| 一套工具（12 基础 + 6 Agent） | 有 | `tool/ReconToolRegistry`、`mcp/AgentMcpTools` |
| MCP 暴露（stdio / HTTP） | 有 | `mcp/McpSpecifications`、`mcp/McpServerRunner` |
| 表单会话（8 个固定动作） | 有 | `web/ChatController`（`POST /api/chat/actions`）+ `ChatRuntime#submit`，与聊天**共用** `dispatch` 与同一套闸门 |
| 三道闸门 | 有 | `agent/RuleSafetyValidator`、`agent/ShadowTrialService`、`rule/ApprovalRelay` |
| 意图理解 / 摘要 / 人话叙述 | **没有** | 由 P1/P2 提供 |

**P0 为什么能零 LLM**：对账的输入是"规则 + 两张表"，不是自然语言。规则由人写 YAML（或从模板复制），
工具由人点按钮或由宿主按固定动词调用，归因由 `RuleBasedExplainer` 按 `DiffKind` 查模板。
LLM 在这里提供的是"把话变成规则"和"把差异说成人话"，这两件事 P0 主动放弃，换来的是**可复现**。

---

## 2. 启动顺序（一个 jar 里发生了什么）

```
java -jar recon-lens-0.1.0.jar
  → Spring Boot 起来
  → spring.sql.init：schema.sql（建 22 张表）→ data.sql（演示数据：bill_left/bill_right 各 10 行）
  → RuleCatalog.load(seed-rules=true)：rules/*.yml 与库对齐（库里没有 → v1；内容不同 → 新版本；已下线不复活）
  → recon.engine.run-demo-on-startup=true → 跑一次演示对账（job #1）
  → StartupSafety（InitializingBean）：生产 profile 下四道自检，失败直接不启动
  → Tomcat 监听
```

`StartupSafety` 是**生产才咬人**的那道：`prod` profile 下
①`recon.model.require-approval=false` → 启动失败（它会让机器生成的规则跳过试算与审批直达 ACTIVE）；
②`recon.security.api-key` 空 → 失败（数据面所有写接口没锁）；
③`recon.review.api-key` 空 → 失败（审批接口一律 403，闸门 3 无法通过）；
④两把 LLM key 都空 → 只 INFO「按 P1 档运行」，**不是失败**（设计内的降级）。

---

## 3. 数据模型（4 张核心表 + 约束）

```sql
recon_job (id, rule_code, rule_version, status, total_shards, finished_shards,
           left_rows, right_rows, matched, diff_count, error_msg, created_at, finished_at)
recon_shard (id, job_id, shard_index, status, attempt, error_msg, updated_at)
recon_diff (id, job_id, shard_index, biz_key, diff_type, field, left_value, right_value,
            severity, explained)
recon_rule (id, code, version, status, spec_json, spec_sha, created_by, created_at)
```

| 约束 | 为什么是它 |
|---|---|
| `recon_diff` 唯一键 `uk_diff_shard_key(job_id, shard_index, biz_key, diff_type)` | 重跑同一分片时"先 DELETE 再 INSERT"是幂等的**第一道**保险；唯一键是第二道（并发/漏删时直接冲突而不是重复） |
| `recon_rule` 唯一键 `uk_rule_code_version(code, version)` | 版本只追加，永不覆盖；`version = MAX(version)+1` |
| `recon_shard` 唯一键 `(job_id, shard_index)` | 分片是（任务 × 下标）的唯一实体；`attempt` 记录重试次数 |
| 状态列 `VARCHAR` 而非 ENUM | 迁移不加锁、H2/MySQL 一致；枚举在 Java 侧（`JobStatus`/`ShardStatus`） |

**每张表都没有 `tenant_id`**：P0 是单实例单租户。多租户延后是明确决定（见 `docs/plan-roadmap.md` §五·二），
约束是"查询一律走 Repository、会话一律走 `SessionScope`"，这样将来加租户列时只改一处。

---

## 4. 规则 DSL：一条 YAML 如何变成一次对账

```yaml
code: BILL_VS_SETTLE
left:  { label: 账单, table: bill_left,  dimension: [customer_id, order_no] }
right: { label: 结算, table: bill_right, dimension: [customer_id, order_no] }
shard: { count: 2, parallelism: 2,
         whereLeft:  "MOD(CAST(SUBSTRING(order_no,10,2) AS SIGNED), {shards}) = {index}",
         whereRight: "MOD(CAST(SUBSTRING(order_no,10,2) AS SIGNED), {shards}) = {index}" }
compare:
  - { field: amount, type: DECIMAL, tolerancePercent: 0.0001, diffType: AMOUNT_MISMATCH, severity: HIGH }
missing:   { leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH }
duplicate: { diffType: DUPLICATE_KEY, severity: MEDIUM }
```

### 4.1 三种取数写法，收敛到同一个键列

`SideSpec` 支持 ①`table + keyField` ②`sql`（自定义，业务键别名 `keyField`）③`dimension`（列/表达式 + 可选 `measures`、`where`）。
三者的产出都是"一条按业务键升序的 SELECT"，键列统一叫 `__biz_key`（`SideSpec.KEY_ALIAS`）：

- 复合键 `dimension: [a, b]` → `CONCAT(a, '|', b) AS __biz_key`
- 表达式键 `dimension: "CONCAT(...)"` → 原样作为键表达式
- 聚合维度 `measures: { amount: SUM(amount) }` → 外层 `GROUP BY __biz_key`

**为什么收敛到一个别名**：引擎（归并/比较/落库）完全不知道维度长什么样，
新增"按日期区间对账"不需要改引擎；也保证左右两侧的键表达式可以不同但键**同型**。

### 4.2 容差是比率，不是百分比数字

`tolerancePercent: 0.0001` 表示 0.01%（0.0001 × 100%）。比较式：

```
|left - right| <= max(|left|, |right|) * tolerancePercent + toleranceAbsolute
```

`toleranceAbsolute` 是为"金额为 0 时相对容差恒为 0"准备的；两者都进闸门 1 的检查
（相对容差放大超阈值 → `TOLERANCE_INFLATED`；绝对容差超过绝对值上限 → `TOLERANCE_ABSOLUTE_LIMIT`）。

### 4.3 分片：两种写法，各有代价

| 写法 | 生成谓词 | 能走索引？ | 同键同片？ |
|---|---|---|---|
| 模板 `whereLeft/whereRight` | 用户原样 + `{shards}/{index}` 替换 | 表达式分片**不能**（`MOD(CRC32(col),4)` 全表扫） | 靠用户保证两侧同函数 |
| `rangeKey: id` | 探两侧 `MIN/MAX` → 均分 → `col >= lo AND col < hi` | **能**（纯列比较） | 由引擎保证（同一列 + 同一组边界） |

`rangeKey` 的代价：只支持数值列、多两次 MIN/MAX（走索引）。
`shard.count` 有上限（`AgentProperties.Safety.maxShardCount`，默认 256）——
分片爆炸（比如规则写 9999 片）会让每个分片只处理几行，调度开销远大于收益。

---

## 5. 一次对账的执行链

```
ReconEngine.run(rule)
  ├─ resolve   规则 JSON 内联 > 规则库最新 ACTIVE 版本；dimension 归一化
  ├─ prepare   ShardPlanner 生成 N 个分片 → INSERT recon_job + N 行 recon_shard(status=PENDING)
  ├─ execute   ThreadPoolExecutor(min(parallelism, CPU*2))，每个分片一个 ShardRunner
  └─ finish    status = SUCCESS / PARTIAL_FAILED / FAILED
```

`ShardRunner` 单分片一次尝试（**幂等**的四步）：

1. `markRunning(jobId, index)` — 更新 `attempt`；
2. `DELETE FROM recon_diff WHERE job_id=? AND shard_index=?` — 先删旧差异（重跑不叠加）；
3. 开左右两条 `JdbcRowCursor`（`fetchSize=2000`，`ORDER BY __biz_key`）→ 校验比对列存在 → `MergeMatcher` 双指针归并；
4. `BatchDiffSink` 500 条一批写入 → `markSuccess`。

异常：`attempt < maxAttempts` → 退避 `200ms × attempt` 重试；否则 `markFailed`，任务终态 `PARTIAL_FAILED`。
**归因在任务结束后异步做**（`ExplainService`）：大模型优先、模板兜底、绝不留空。

### 5.1 为什么用归并而不是哈希连接

哈希连接要把两侧全读进内存（或落盘），内存 ≈ 数据量；归并只需要"当前键的两侧游标"，
内存 ≈ `fetch-size` + 同键重复数。代价是两侧都必须 `ORDER BY` —— 这是规则 DSL 里
"三种取数写法都生成按业务键升序的 SELECT"的原因，也是 `ShardSpec` 存在的原因
（大表不切分，单游标扫全表仍要很久）。

---

## 6. 工具层：一套注册表，两个入口

```
ToolSpec(name, description, inputSchema, outputSchema, handler, write, suggestion)
ToolRegistry.specs() → List<ToolSpec>
  ├─ ReconToolRegistry   12 个基础工具（永远在）
  └─ AgentMcpTools        6 个 Agent 工具（recon.agent.enabled=true 才在）
ToolCatalog（@Primary）= 合并两份，重名即启动失败
```

**为什么 18 个工具必须来自一个目录**：此前 `/api/tools`（控制面）报 12、MCP 宿主 `tools/list` 报 18，
两边差的 6 个正是 Agent 工具 —— 同一个能力，两个数字，问题迟早出现在"文档说 15、脚本按 15 写、实际 18"上。
现在 `ToolCatalog` 是唯一来源，`McpServerRunner` 与 `ToolController` 都只读它。

12 个基础工具（*写* = `write=true`，会走两段式确认）：

| 工具 | 参数 | 做什么 | 失败形态 |
|---|---|---|---|
| `recon_list_rules` | — | 列规则（code/name/version/status） | — |
| `recon_list_jobs` | `limit` | 列执行历史 | — |
| `recon_list_tables` | — | 列允许的表（配置 ∪ 动态注册） | — |
| `recon_describe_table` | `table` | 列结构；样例值默认打码 | 不在白名单 → 拒绝 |
| `recon_define_rule`* | `ruleBody`(YAML) | 建草稿（DRAFT），要试算+审批才生效 | YAML 结构错 → 结构化错误 |
| `recon_build_model`* | `ruleCode` | 抽样建 `__MODEL` 预处理规则（DRAFT） | 表不在白名单 → 拒绝 |
| `recon_run`* | `ruleCode` | 跑一次对账（分片并行） | 无 ACTIVE 版本 → 拒绝并说明 |
| `recon_job_summary` | `jobId` | 单次任务统计 | 任务不存在 → 明确错误 |
| `recon_list_diffs` | `jobId, offset, limit` | 差异分页 | — |
| `recon_explain_diff` | `diffId` | 单条归因 | — |
| `recon_explain_job` | `jobId, limit` | 整单归因（每条差异一条） | — |
| `recon_diagnose` | `jobId` / `ruleCode` | 诊断（为什么全 FAILED 等） | — |

6 个 Agent 工具（只在 P1/P2 档注册）：`recon_draft_rule`（自然语言 → 过闸门 1 的草稿）、
`recon_trial_status`、`recon_register_table`、`recon_import_file`、`recon_import_status`、`recon_submit_approval`。

**`write=true` 的作用**：会话层的两段式确认不再靠"意图枚举里有没有 RUN"来判断，
而是读工具自己的声明（`ToolSpec.write`）。新增一个写工具时，忘了改意图枚举不会漏掉确认。

**`suggestion` 的作用**：`"下一步：recon_trial_status(runId=...)"` 这类话不再拼进工具正文
（模型会把正文当事实复述），而是放在结构化字段里，由 MCP 的 `structuredContent` 或控制台展示。

---

## 7. MCP 暴露：一个协议，两种传输

```
McpSpecifications.build(jsonMapper, registry)
  → List<SyncToolSpecification>（ToolSpec 的 schema 原样透传，handler 内部 try/catch → isError=true）
```

| 传输 | 启动方式 | 适合 | 致命限制 |
|---|---|---|---|
| stdio | `--recon.mcp.stdio` / `McpServerRunner` | 单次工具调用、脚本 | **进程内存态**：`TrialTaskRegistry`/`ImportTaskRegistry` 在 JVM 堆里，下一次连接是另一个进程，查不到上一轮的任务 |
| streamable HTTP | `--recon.mcp.http.enabled` → `/mcp` | 多轮 Agent | 需要宿主支持 MCP over HTTP（握手 `protocolVersion=2025-06-18`、`Mcp-Session-Id`） |

**工具错误不走异常**：`ToolSpec.handler` 抛 `IllegalArgumentException` → 转成 `isError=true` 的结果文本
（"写错了"是模型能读懂并改的反馈；500 会让宿主直接放弃）。真正的程序错误才向上抛。

---

## 8. 审批闭环（gate 3 的最后一道）

```
POST /api/rules/{code}/{version}/trial   → 落 trial_verdict（PASS/FAIL/INCONCLUSIVE）
POST /api/rules/{code}/{version}/approve → 要 confirmationCode
```

`ApprovalRelay` 的确认码 = `sha256(ruleCode|version|spec_sha)` 的前若干位，由**控制台/接口**返回给操作人，
再随审批请求回传。三个性质：

1. **聊天里说"我同意"不算**：确认码只出现在审批接口的响应里，会话层拿不到；
2. **绑内容**：`spec_sha` 变了（哪怕改一个空格）旧码失效；
3. **试算是前置**：`TrialState.NOT_RUN` → `NOT_APPROVABLE`；`FAIL` → `BLOCK_HARD`（不可覆盖）。

---

## 9. 可观测（P0 就已经有）

```
recon_agent_step(id, run_id, seq, phase, action, input_json, payload, digest, truncated, status, cost_ms, tokens_est, created_at)
```

- `phase`：`OBSERVE / PLAN / ACT / LEARN / RESUME`；`action`：`draft-round-1`、`shadow-trial`、`run-reconciliation`…
- `payload` 截断（`PromptGuard.truncateTokens`，2000 token）+ `truncated=true`；`digest` 是**未截断内容**的 sha256
- `cost_ms` 用 `System.nanoTime()` 记墙钟；`tokens_est` 记**估算**（真模型档会写入实测 usage）
- 读：`GET /api/audit/runs/{id}`（**两个 header 都要**：`X-Operator`，配了 `recon.security.api-key` 时还要 `X-Api-Key`）；`…/steps.csv` 导出

---

## 10. 性能与踩坑（都实测过）

| 现象 | 根因 | 结论 |
|---|---|---|
| `Connection is not available, timed out after 10004ms` | 每分片占 2 条连接（左右游标），池 16 在 8 分片 + 并行 8 时刚好占满 | 池 ≥ `2 × parallelism` + 余量（默认改 24） |
| `Function "crc32" not found` | H2 没有 `CRC32`，而提示词要求用 `MOD(CRC32(...))` 分片 | `H2Compatibility` 往 H2 注册 `CREATE ALIAS`；**不改提示词**（改了等于让规则迁就演示库） |
| 8 分片比 1 分片慢 1.5~8.6 倍 | 表达式分片用不到索引，8 次全表扫 | 用 `rangeKey`，或减少分片 |
| 内存炸 | 哈希连接 / 无 `fetchSize` | 归并 + `fetchSize=2000`；实测 `-Xmx256m` 跑 200 万 × 2 |

---

## 11. 追问预案

**Q：规则为什么要版本化，不能覆盖吗？**
A：任务要能重放。`recon_job` 钉住 `rule_version` + `rule_snapshot`，归因时按"任务当时那份规则"跑；
覆盖会让历史差异的解释变成"用新规则解释旧结果"。所以 `recon_rule` 只追加，`uk_rule_code_version` 保证不重号。

**Q：分片是为性能吗？**
A：不是，分片是**重跑单位**。性能来自归并 + 游标 + 连接池。分片太多反而慢（表达式分片全表扫）。

**Q：为什么不用数据库 JOIN 做对账？**
A：JOIN 的结果集要落库（临时表或大结果集），而"差异"只是少数行；且 JOIN 无法表达"容差"和"同键重复"。
归并把比较放在应用层，容差/缺失/重复三类差异用同一套双指针状态机产出。

**Q：没有模型的 P0 怎么处理"我要对账"这种话？**
A：不处理。P0 的入口是表单（8 个固定动作）和工具调用，参数从控件来。
自然语言入口从 P1 开始（词表路由），P2 才引入模型。这条边界是有意的：**没有模型时不要假装能听懂**。
