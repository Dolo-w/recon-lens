# recon-lens M3–M5 代码评审意见

> **评审对象**：`HEAD = 5709d11` + 66 项未提交改动（M3 起草链 / M4 差异知识库 / M5 巡检），约 **+5,900 行**
> **对照基线**：`recon-lens-Agent化技术方案设计.md` v0.7（1,684 行）+ 本人的 `docs/agent-design-review.md` §1–§12（六轮设计评审）
> **评审日期**：2026-10-17
> **测试基线**：`mvn -B test` → 31 个测试类 / **340 用例 / 0 失败 / BUILD SUCCESS**（上一轮 243）
> **一句话结论**：功能面已经可以演示，**安全面还差 4 条才能上生产**；而这 4 条里 1 条是新代码引入的，3 条是"闸门写了但没接上"。
>
> ⚠️ **状态更新（2026-10-17 晚复核，工作区未提交项 67 → 82）**：本文件写完后，工作区又被改过一轮，
> **P0-1、P0-2、P0-4、P1-6 四条已修复或属我误判**（证据见 §0.2 状态列）。当前仍成立的 P0 只有 **P0-3**，
> P1 还剩 **P1-1～P1-5、P1-7**。修复进度请看 §0.2，不要只看下面的原始描述。

---

## 0 总览

### 0.1 问题分布

| 级别 | 数量（初评） | **当前仍成立** | 含义 |
| -- | -- | -- | -- |
| **P0** | 4 | **1**（P0-3） | 不修则"三道闸门"可被绕过，或试算结论不可信 |
| **P1** | 7 | **6**（P1-5/6/7 之外的 P1-1～P1-5、P1-7；其中 P1-6 已修） | 闸门在跑，但验的不是那件事（会给出"看起来通过"的错结论） |
| **P2** | 6 | **6** | 工程卫生、运维可见性、测试缺口 |

### 0.2 本轮的实际形态

**这是"往前加功能"的一轮，不是"回头修缺陷"的一轮。** 上一轮评审报的 P0/P1 里，复核结果（状态列为**当前工作区**的复核结论）：

| 上轮问题 | 状态 | 证据 |
| -- | -- | -- |
| `POST /api/rules` 直写 ACTIVE | ✅ **我已误判** | 开关早已实装：`config/ReconProperties.java:79 legacyDefineEnabled = false`、`web/RuleController.java:101/108` 抛 `LegacyDefineDisabledException`、`application.yml:35`、`RuleApiTest.java:28` 显式打开做测试 |
| 试算未绑定内容哈希 | ✅ 已修 | `schema.sql:93` / `schema-mysql.sql:96` 有 `trial_spec_sha`；`store/RuleRepository.java:168-189` `saveTrial(..., trialSpecSha)` + `findTrialSpecSha` |
| `INTERSECT` 需 MySQL 8.0.31 | ✅ 已修 | `agent/ShadowTrialService.java:341-347` 改成半连接并保留"为什么不用 INTERSECT"的注释；`agent/CommonKeysSqlTest.java:70-76` 断言 `doesNotContainIgnoringCase("INTERSECT")` |
| `skipTrial` 手拼 JSON | ✅ 已修 | `rule/RuleLifecycleService.java:132-135` 改 `LinkedHashMap` 审计 + `saveTrial(..., audit)` |
| `markTrusted` 无并发保护 | ❌ 未改 | `store/GateHealthRepository.java:57-59` 仍 `upsert(gateVersion, true, ...)`，`upsert`（`:78-87`）仍是 update-then-insert，无 CAS |
| T3 基线取截断值 | ❌ 未改 | `ShadowTrialService.java:357-365` 仍读 `recon_diff` 的 `biz_key`/`*_value` |
| 双重聚合校验缺失 | ❌ 未改 | `grep DOUBLE_AGGREGAT\|双重聚合` 无命中 |
| 新规则跳过 tampering | ❌ 未改 | `agent/RuleSafetyValidator.java:113-114` 仍 `baseline == null` 整段跳过 |
| `actExisting` 跑 PROD 对账 | ❌ 未改 | `agent/AgentOrchestrator.java:281-287` 仍 `engine.run(resolved, batch, ...)` |
| `learn()` 绕预算 | ❌ 未改 | `agent/AgentOrchestrator.java:391-398` 仍 `explain.explainJob(jobId, 20)` |

> 误判原因（记下来避免重犯）：`git grep` 默认**不搜未跟踪文件**，而 `agent/`、`llm/`、`explain/` 整个包当时都是
> `??`；PowerShell `Select-String -Path 'src\main\java\**\*.java'` 的 `**` 也不会递归。正确的搜法是
> `git grep --untracked` 或直接对目录做递归搜索。

### 0.3 值得肯定的地方（这些不要改）

新增代码的整体质量高于同类项目平均水平，几个关键判断都落到了注释里，后人不会轻易磨掉：

1. **审批不进入 MCP**（采纳 R1）：`recon_review_rule` 刻意没注册；`web/RuleReviewController.java:50-141` 是 fail-closed——`recon.review.api-key` 没配就一律 403；
2. **R3 的键定义**：`explain/CauseKey.java:15-22` 明写"查库时你还不知道 cause，拿答案去查索引命中率恒为 0"，键是 `(ruleCode, diffType, fieldName, featureSignature)`，`cause` 是值；
3. **R10 的注入防护**：`llm/PromptGuard.dataBlock` 分段注入，`agent/RuleDrafter.java:192-203` 把业务描述 / schema / 基线 / few-shot 全部包进数据段，违规回灌也经 `scrub`；
4. **R16 的异步试算**：`agent/TrialTaskRegistry.java:20-34` 交代了"21s 对账 vs 30s 客户端超时"的矛盾，以及为什么选"单线程 + 有界队列 + 拒绝"而不是排队；
5. **R18 只报告不动手**：`agent/PerceptionService.java:26-44` 明写"把观察者变成参与者"的风险，且抑制必须可见（`suppressed` 与 `findings` 分列）；
6. **矩阵单一事实源**：`domain/TrialState.approval()` 是唯一判定入口，`rule/RuleLifecycleService.java:31` 明写"本类不写自己的 if"；
7. **状态机并发**：`transition(code, version, from, to)` 返回影响行数为 0 即抛"已被并发修改"，比"先查后改"可靠；
8. **内容哈希绑定**（部分）：`RuleCatalog.verifyReviewedContent` 会在 `spec_json` 与 `reviewed_spec_sha` 不一致时把版本降级回 REVIEWING。

---

## 1 P0：不修则安全模型不成立

### P0-1　`POST /api/rules` 无鉴权且直接生效

> ✅ **已证伪（我的误判）**：这个开关在我评审时就已经存在，只是 `RuleController` 我只看清了前 60 行。
> 现状：`config/ReconProperties.java:79 legacyDefineEnabled = false`，`web/RuleController.java:101/108`
> 在关闭时抛 `LegacyDefineDisabledException`。剩下的真实缺口只有一条：**打开开关之后**这个入口仍无 key、
> 无审计（本节的修法 A 后半段仍然有效）。

- **位置**：`web/RuleController.java:66-72` → `rule/RuleCatalog.java:239-247`（4 参数 `insert`，默认 ACTIVE）
- **决策注释**：`RuleCatalog.java:257-259` 说明保留直通理由（"改成草稿会让 CI 脚本/运维在升级当天发现规则不生效"）
- **现象**：全仓 `grep` 不到任何 `OncePerRequestFilter` / `HandlerInterceptor` / `SecurityFilterChain`；`RuleController` 类上也没有 API Key 校验。同一套系统里，**审批接口（`/api/rules/{code}/{version}/approve`）加了 key 且默认关闭，而"绕过全部闸门、直接让规则生效"的入口既无 key、无开关、无审计**。
- **影响**：一条 `curl -X POST /api/rules -d @patch.json` 即可把 `tolerancePercent` 从 0.1 改成 10（正是设计 §0 里点名要防的那件事），闸门 1/2/3 全程不介入。这不是"兼容性权衡"，是**安全模型的后门**。
- **修法（二选一）**：
  - **A（推荐）**：加开关 `recon.rules.legacy-define-enabled`，默认 `false`；打开时也强制 `X-Api-Key` + `origin=HUMAN_REST`，落一行审计（`recon_agent_step` 或专表）；
  - **B**：让 `defineJson/defineYaml` 一律落 `DRAFT`，把"要立刻生效"做成显式的 `POST /api/rules/{code}/{version}/activate`（同样要 key + 审计）。
- **验收**：`web/RuleApiTest` 增加"未带 key 的 `POST /api/rules` 返回 403"+"默认配置下新增版本落 DRAFT"。

### P0-2　试算结论没有绑定内容哈希

> ✅ **已修复**：`trial_spec_sha` 已落库（`schema.sql:93`、`schema-mysql.sql:96`），
> `store/RuleRepository.java:168-189` 的 `saveTrial(..., trialSpecSha)` / `findTrialSpecSha` 都已就位，
> 用的正是本节建议的"存试算时那份的哈希、批准时比对"的方案。下面的攻击链现在走不通了。

- **位置**：
  - `docs/migrations/2026-10-17-trial.sql` —— 无 `trial_spec_sha` 列；
  - `rule/RuleLifecycleService.java:81-93` `submit` —— 只做 `DRAFT → REVIEWING`，**不清试算状态**；
  - `:99-106` `issueToken` —— token 绑定"当前内容的 sha"；
  - `:141-179` `approve` —— 只比对 token 与当前哈希，`findTrial` 读的是**历史结论**。
- **攻击/误操作链（四步，全部合法调用）**：
  1. 某版本 `trial()` 得到 `PASS × FULL`；
  2. 原地 `UPDATE recon_rule SET spec_json = ...`（DBA 脚本、热修、回放导入都会这么干）；
  3. `submit` + `issueToken` —— token 绑定**改后的**内容，校验通过；
  4. `approve` —— token 对得上、`findTrial` 读到旧 PASS → **批准了从未试算过的内容**。
- **为什么 `reviewed_spec_sha` 挡不住**：它只防"批完再改"（`verifyReviewedContent` 降级），不防"试算完再改、再批"。
- **修法**：`ALTER TABLE recon_rule ADD trial_spec_sha CHAR(64)`；`saveTrial` 写入当时的 sha；`approve` 里 `findTrial` 之后比对 `trial_spec_sha.equals(currentSha)`，不等则拒绝并提示重跑；或在 `submit` 时清空 `trial_*`（更省事，但用户要重跑）。
- **验收**：`RuleLifecycleServiceTest` 增加"试算通过 → 改内容 → 重新提交 → 用新 token 批准 → 被拒"。

### P0-3　`recon_draft_rule` 会顺手跑一次生产对账

- **位置**：`agent/AgentOrchestrator.java:267-271`（`plan()` 命中 `EXISTING_RULE`）、`:274-287`（`actExisting`，`:280` `engine.run(...)`）
- **现象**：`recon_draft_rule` 的入参里有可选 `ruleCode`。若该 code 已存在，PLAN 判定走确定性路径，ACT 直接 `engine.run()` —— 跑的是 **PROD** 任务，写 `recon_job`/`recon_diff`，进 `PerceptionService` 的环比基线，也可能触发下游告警。
- **影响**：① 语义越界——一个名为"起草"的工具产生了生产副作用；② 成本不可控——单次 21s+，模型重试几次就是几分钟的生产负载；③ 可观测性——运维会看到"没人点过对账，却出现了 PROD 任务"。
- **修法**：`actDraft` 路径只起草不跑批；需要"用现有规则跑一次"另开工具 `recon_run_rule`（显式、可关、带预算与 `remark` 标记），并在描述里写清它会写生产数据。
- **验收**：`McpToolCatalogTest` 断言"`recon_draft_rule` 调用后 `recon_job` 无新增 PROD 行"。

### P0-4　`INTERSECT` 在目标库（MySQL 8.0.29）不可用

> ✅ **已修复**：`agent/ShadowTrialService.java:341-347` 现在是一段解释"为什么不能用 `INTERSECT`"的注释，
> SQL 已换成半连接；`agent/CommonKeysSqlTest.java:70-76` 用 `doesNotContainIgnoringCase("INTERSECT")`
> 把它钉死了。本节保留下来是因为那条实测证据（`ERROR 1064 ... near 'INTERSECT SELECT 1 AS a'`）
> 值得留档。

- **位置**：`agent/ShadowTrialService.java:343`
  ```java
  + " AS k FROM " + tables.tableLeft() + " INTERSECT SELECT " + trialRule.right().keyExpression()
  ```
- **实测证据**（本机 MySQL 8.0.29，仓库 README 声明的支持版本）：
  ```
  ERROR 1064 (42000): You have an error in your SQL syntax ... near 'INTERSECT SELECT 1 AS a'
  ```
  `INTERSECT` 从 MySQL **8.0.31** 才提供。
- **影响**：T5-c（跨分片键比对）在 MySQL 上必然报错 → 要么整条试算失败，要么被上层 catch 成 `INCONCLUSIVE`，等于这条最关键的"分片切错"判定在目标库上从未真正跑过。
- **修法**：改 `WHERE EXISTS`/半连接（`SELECT k FROM trial LEFT WHERE NOT EXISTS (SELECT 1 FROM trial_right r WHERE r.<key> = left.k)` + 反向一次），或物化两张临时表后 `LEFT JOIN … IS NULL`。恒等式：`A INTERSECT B ⇔ (A ∖ B) 为空且 (B ∖ A) 为空`。
- **验收**：把该 SQL 抽成常量/方法，用 `@Sql` 在同一套 schema 上跑一次（H2 + MySQL profile 各一遍）。

---

## 2 P1：闸门"看起来在跑"，但验的不是那件事

| # | 位置 | 问题 | 建议 |
| -- | -- | -- | -- |
| **P1-1** | `ShadowTrialService.java:357-365` + `ReconRepository.java:167` + 列宽 `schema.sql:47/49/50` | T3/T4 的基线取自 `recon_diff`，而该表 `biz_key VARCHAR(128)`、`left_value/right_value VARCHAR(512)`。长键或长金额的规则会把"不同"截断成"相同"，T3 判 PASS | 基线改用 `recon_job` + 快照重算，或给 `recon_diff` 加 `biz_key_hash CHAR(64)` 与 `value_digest`，比较用摘要 |
| **P1-2** | 全面缺失 | 设计 §5.2 要求检测"两侧各自 `SUM(amount)` 但粒度不同"（差异互相抵消 → 全绿），代码里没有这条（`grep DOUBLE_AGGREGAT/双重聚合` 无命中） | 在闸门 1 加静态判定：`compare` 表达式含聚合、且两侧 `group by` 维度不等 → `VIOLATION`；或在 T2 里对样本做"逐键求和对齐" |
| **P1-3** | `RuleSafetyValidator.java:113-114` | `baseline == null` 时整段 tampering 校验跳过 → **Agent 的首版规则**没有绝对上限：`tolerancePercent: 90`、`severity` 随意降、`compare` 只留一个字段都能过 | 与基线无关的**绝对上限**独立成一条校验：容差 ≤ 配置上限、`compare` 非空、`severity` 不得低于默认、禁止 `sql:` 里出现 DML |
| **P1-4** | `ShadowTrialService.java:365` vs `:394` | 采样窗口两套语义：库查询 `diffsInWindow(jobId, lo, hi)` 打在 VARCHAR 上（字典序），窗口键却用 `SampleMaterializer.literal` 生成类型化字面量 → 数值型业务键时基线侧取到空集，T3/T4 建立在空集上 | 统一走"类型化字面量 + 单侧查询"，或把 `diffsInWindow` 改成接收 `Window` 并按 `RuleSpec` 侧类型拼比较 |
| **P1-5** | `GateHealthRepository.java:57`（upsert 在 `:79-84`）、调用点 `ShadowTrialService.java:330` | `markTrusted` 无条件 `UPDATE`（0 行则 `INSERT`），无版本/CAS：两个实例同时自检，后写的覆盖先写的；canary 明明失败过一次也可能被另一个实例翻回 trusted | upsert 加 `WHERE checked_at <= ?` 或 `gate_version + checksum` 乐观锁；`markUntrusted` 优先于 `markTrusted`（失败不可被静默覆盖） |
| **P1-6** | ~~`RuleLifecycleService.java:128`~~ | ✅ **已修**：`skipTrial` 已改为 `LinkedHashMap` + `saveTrial(..., audit)`（`RuleLifecycleService.java:132-135`） | — |
| **P1-7** | `AgentOrchestrator.java:384-392` | `learn()` 调 `explain.explainJob(jobId, 20)`，**不经 `AgentBudget`** —— 预算只管起草轮数与 token，归因阶段的 LLM 成本没有上限 | 把 explain 的调用数计入 budget（新增 `spendExplain(n)`），超限按"仅模板"降级 |

---

## 3 P2：工程卫生与可观测性

| # | 位置 | 问题 | 建议 |
| -- | -- | -- | -- |
| **P2-1** | `TrialTaskRegistry.java:74-75`、`:99-114` | 无幂等：模型重试同一需求 → 起两次试算、两份 DDL 样本表；`tasks`/`draftIndex` 两张 Map 永不淘汰（长跑内存单调增长） | 用 `(ruleCode + 需求摘要)` 做在途去重，命中直接返回原 `runId`；完成任务保留 N 条/超时淘汰 |
| **P2-2** | `PerceptionService.java:278-295`、`:60` | 每轮巡检 `startRun` 落一条 `recon_agent_run`（默认 1h 一轮，两实例即 48 条/天），无保留策略；抑制表 `lastAlert` 在进程内 → 多实例各报一遍（R18 残留） | 巡检轨迹单独表或加 `retention`；抑制状态落库（`perception_alert` 表），或文档明确"单实例假设" |
| **P2-3** | 全仓 | **0 处 `@Transactional`**：`approve`（`transition` + `recordReview`）、`deleteJob`（job/diff/shard 三删）、`GateHealthRepository` upsert（update + insert）都可能半完成 | 给这三处加事务；`deleteJob` 尤其（孤儿 diff 会污染 `countBy(column, null)` 的全表聚合） |
| **P2-4** | `RuleLifecycleService.java` token 生成 | `recon.review.token-secret` 未配置时用进程级随机密钥 → 重启或多实例后 token 全部失效，运维看到的是"审批失败但不知道为什么" | 启动时检测：生产 profile 下缺失即 fail-fast；开发环境才允许随机并 warn |
| **P2-5** | `McpServerRunner.java:250` | `recon_diagnose` **无条件注册**，工具数变 11；但类注释（`:36-37`）与设计 §8.2 都说"基础 8 + `agent.enabled` 时 +2 = 10" | 决定口径：要么把 diagnose 也放进 `agent.enabled` 分支，要么改注释/文档为 11（diagnose 只读、走 KB/模板，放外面可接受，但必须写清） |
| **P2-6** | 测试 | 缺 `CanaryInjectorTest`；缺"草案 → 试算 → 审批"端到端用例（`AgentSwitchTest` 只验开关，`ShadowTrialServiceTest` 只到 `trial()`） | 补两条：① canary 注入前后 `COUNT(*)` 变化断言；② `recon_draft_rule`(MCP) → `recon_trial_status` → REST `approve` 的完整链 |

---

## 4 修复顺序

| 批次 | 条目 | 为什么排在这里 | 验收信号 |
| -- | -- | -- | -- |
| **今天** | ~~P0-1、P0-2、P0-4~~（已完成）；**P0-3** | P0-1/P0-2/P0-4 已被后续改动解决 | P0-3：`recon_job` 无新增 PROD 行 |
| **本周** | P0-3、P1-1、P1-2、P1-3、P1-4 | 都是"结论失真"类：无害地给出 PASS | 各补一条"反例必须 FAIL"的用例 |
| **随迭代** | P1-5/6/7、P2-1/2/3/4/5/6 | 运维与健壮性 | 长跑/多实例/重启场景各一条用例 |

> 建议把 P0-2、P0-3 与 P2-3 合并成一个 PR：三者都在改同一条写路径（`saveTrial` / `approve` / `recordReview`），分三次改等于三次回归。

---

## 5 评审方法（可复现）

```powershell
cd D:\App\deepseek\workspace\recon-lens
$env:JAVA_HOME='D:\App\deepseek\tools\jdk-17.0.20.1+1'
& 'D:\App\deepseek\tools\apache-maven-3.9.16\bin\mvn.cmd' -B test
# → Tests run: 340, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

- MySQL 8.0.29 实测（`INTERSECT`）：`D:\App\deepseek\tools\mysql-8.0.29-winx64\bin\mysql.exe -u root -h 127.0.0.1 -P 3307`
- 逐层读码范围：`domain/*`（TrialState / Coverage / CoverageReason / TrialVerdict）、`agent/*`（Orchestrator / TrialTaskRegistry / PerceptionService / ShadowTrialService / RuleSafetyValidator / RuleDrafter）、`rule/*`（LifecycleService / Catalog / Change / SpecHasher）、`store/*`（GateHealth / TrialSample / Recon / Rule / AgentRun）、`mcp/*`（AgentMcpTools / McpServerRunner）、`llm/*`、`explain/*`、`web/RuleReviewController`

---

## 附录 A　已核通过的 10 条不变量

1. `recon.agent.enabled` 默认 `false`，`AgentConfiguration` 整类 `@ConditionalOnProperty`；`AgentBoundaryTest` 双向守边界（引擎不依赖 agent、agent 不依赖 web/mcp）；
2. TRIAL job 按 `kind` 隔离，生产查询只认 `PROD`；
3. canary 失败 / 闸门不可信 → coverage 强制 `NONE`（`ShadowTrialService.java:123-133`）；
4. canary 用独立 job，不污染 `DiffSetDigest`；
5. 样本用普通表 + 租约，不用 `TEMPORARY`（连接池多连接可见）；
6. T2 的 digest 只算内存态，不读库；
7. T5-d 只认 `LIMIT`/`FETCH`/`TOP` 三种截断信号；
8. 取窗取两侧并集（`lo=min`、`hi=max`）；
9. `verdict × coverage` 矩阵与 `TrialState.approval()` 单一事实源；
10. 审批入口 fail-closed（未配 key 一律 403，`RuleReviewFailClosedTest` 断言）。

## 附录 B　未验证 / 需作者确认

1. **P1-2 的判定口径**：两侧各自 `SUM` 时"粒度不同"在 SQL 层面如何探测（`GROUP BY` 维度对比？样本逐键求和？）需要作者给出可实现的判定式，否则这条只能停留在"检测到聚合语法的启发式"。
2. **`recon_diagnose` 是否消费 LLM**：工具描述写"归因抽样走知识库/模板，不消耗大模型"，但 `DiagnosisService` 依赖 `ExplainService`；`DiagnosisServiceTest` 是否用 mock 固定了"零 LLM"需要确认。
3. **迁移是否已在本机/生产执行**：`docs/migrations/*.sql` 三份脚本存在，但我只在 `recon_verify2/3` 上验证过 DDL 语法，未验证过数据迁移（`UPDATE ... WHERE lifecycle IS NULL` 这类语句的实际影响面）。
