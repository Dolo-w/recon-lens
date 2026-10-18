# 对《M3–M5 代码评审意见》的核实与处理

> **评审对象**：`docs/code-review-2026-10-17.md`（17 条：P0 × 4 / P1 × 7 / P2 × 6）
> **核实方式**：逐条对着源码复现，不采信"评审说了就算"；凡判定"成立"的，都给一条会红的用例
> **处理日期**：2026-10-17　**代码仍未提交**，留工作区过 diff
> **测试基线**：`mvn -o test` → **353 通过 / 0 失败**（评审时 340，本轮 +13）

---

## 0 一句话结论

**4 条 P0 全部成立，已修**；P1 里 3 条成立并已修、4 条成立但按设计取舍留账；P2 里 1 条已过期、5 条留账。
评审有一处**严重性高估**（P1-7）和一处**前提说反了**（P1-1），下面逐条说明。

---

## 1 P0：四条全部成立，已修

| # | 判定 | 修法 | 钉住它的用例 |
| -- | -- | -- | -- |
| **P0-1** `POST /api/rules` 无鉴权且直接 ACTIVE | ✅ 成立 | 新增 `recon.rules.legacy-define-enabled`，**默认 `false`**；打开后仍强制 `X-Api-Key`（复用 `recon.review.api-key`），缺失/不匹配一律 403；放行时打 `LEGACY_DEFINE` 级别 WARN | `RuleDefineGuardTest`（默认 403 且不落库）、`RuleDefineWithoutKeyTest`（开关开但没密钥 → 403）、`RuleApiTest`（显式打开 + 密钥 → 201） |
| **P0-2** 试算结论没绑内容哈希 | ✅ 成立 | 新增 `recon_rule.trial_spec_sha`；`saveTrial` **必须**传入"试算跑的那份快照"的哈希（不是在仓储里现读现算 —— 那会把试算后改动的内容绑到旧结论上）；`approve` 比对失败即拒 | `RuleLifecycleServiceTest.试算之后改过内容_旧结论不能拿来批准` / `跳过试算之后改过内容_也不能直接批准` |
| **P0-3** `recon_draft_rule` 顺手跑生产对账 | ✅ 成立 | `AgentGoal` 增加 `Intent`（默认 `DRAFT_RULE`）；规划层只在 `RUN_RECONCILIATION` 时才走 `EXISTING_RULE → engine.run()` | `AgentOrchestratorTest.draftIntentOnAnExistingRuleDoesNotRunProductionReconciliation` |
| **P0-4** `INTERSECT` 需 MySQL 8.0.31 | ✅ 成立 | 改成 `DISTINCT + EXISTS` 半连接；两侧先投影成单列派生表，表达式键也能用 | `CommonKeysSqlTest`（断言 SQL 不含 `INTERSECT` + 去重计数 + 表达式键两例） |

### P0-2 为什么必须新增一列，而不是"提交时清掉试算状态"

`reviewed_spec_sha` 与 `trial_spec_sha` 钉的是时间轴上两个不同时刻：

- `reviewed_spec_sha`：审批那一刻 → 防"批完再改"（已有 `verifyReviewedContent` 降级）；
- `trial_spec_sha`：试算那一刻 → 防"试算完再改、重新提交、用新 token 批准"。

只做前者时，下面四步全部合法且能通过：`trial()` → `UPDATE spec_json` → `submit` + `issueToken`（绑新内容）→ `approve`（token 对得上，`findTrial` 读到旧 PASS）。**批准了一份从未试算过的内容。**

"提交时清空 `trial_*`" 能堵住这条，但它把正常流程（草稿 → 试算 → 提交 → 审批）也一起废了：每次审批都会变成 NOT_APPROVABLE。所以选了加一列。

### P0-1 为什么开关打开也要求密钥

开关的语义是"我知道这条通路有风险，仍然要开"；密钥的语义是"只有我知道怎么开"。把 boolean 单独当边界，等于所有能发 HTTP 的人都能开。

> **未做**：评审建议的"落一行审计"。目前是 `WARN` 日志 + 规则行的 `created_by` / `source`，没有专表。理由见 §4 的 D13。

---

## 2 P1：三条已修，四条留账

| # | 判定 | 处理 |
| -- | -- | -- |
| **P1-3** `baseline == null` 时 tampering 整段跳过 | ✅ 成立 | 加了**与基线无关**的绝对上限 `recon.agent.safety.max-tolerance-percent`（默认 5%），新违规码 `TOLERANCE_ABSOLUTE_LIMIT`。`compare` 非空、`sql:` 只读这两条本来就不依赖基线（解析器与 `checkSide` 已覆盖），评审把这三件事混在一条里了 |
| **P1-4** 采样窗口两套语义 | ✅ 成立 | `diffsInWindow(jobId, lo, hi, keyType)`：数值键把两边 `CAST` 成 `DECIMAL` 再比；字符串键保持字典序 |
| **P1-6** `skipTrial` 手拼 JSON | ✅ 成立 | 改走 `ObjectMapper`，`Map<String,String>` 序列化，失败即抛 |
| **P1-7** `learn()` 的 explain 不计预算 | ⚠️ 部分成立，**严重性高估** | 见下 |
| **P1-1** T3/T4 基线取截断值 | ⚠️ 成立，但前提与评审说的相反 | 见下 |
| **P1-2** 双重聚合校验缺失 | ✅ 成立，**但已在账本** | 设计文档 §18.3 早就记着"`GROUP BY` 与 `measures` 的双重聚合冲突校验仍没做"。评审把它当成新发现；真正缺的是**可实现的判定式**（评审附录 B 自己也问了） |
| **P1-5** `markTrusted` 无并发保护 | ✅ 成立 | 留账（D12）：当前是单实例假设（D8），失败不可被静默覆盖这点已在 `canary` 侧 fail-closed |

### P1-7 的严重性：一次 run 只多出 1 次 LLM 调用，不是每条差异一次

`ExplainService.explainJob` 的实现是**一次批量调用**：

```java
Map<Long, String> batch = llm.available() && !needLlm.isEmpty()
        ? llm.explainBatch(rule, needLlm)      // ← 一批一次
        : Map.of();
```

所以"归因阶段 LLM 成本没有上限"这句话在**调用次数**上不成立（每次 run ≤ 1 次），只在**单次 prompt 体积**上成立（≤ 20 条差异，且 `PromptGuard` 逐字段截断）。
仍记入账本 D11：预算维度目前只管起草轮数与 token，把 explain 也纳入是"更整齐"，但不是这一轮的安全问题。

### P1-1 的前提：项目自己的 sql_mode 是严格的，所以实际表现是"任务失败"而不是"静默截断"

仓库 README 与迁移脚本都声明目标库 `sql_mode` 含 `STRICT_TRANS_TABLES`。在这种模式下，`left_value VARCHAR(512)` 装不下的值会直接 `ERROR 1406`，**不是**悄悄截断成前 512 字符。

所以评审描述的"长金额被截断成相同 → T3 判 PASS"只在**非严格模式**下发生；严格模式下会先炸在写入。但评审指出的根本问题仍然成立：**长键/长值的规则拿不到可用基线**，T3/T4 的输入是残缺的。留账 D10（要加 `biz_key_hash` / `value_digest` 两列并改比较逻辑，属于 schema 变更，不与本轮混在一起）。

---

## 3 P2：一条已过期，五条留账

| # | 判定 | 说明 |
| -- | -- | -- |
| **P2-5** 工具数口径 | ⚠️ 已过期 | 评审看的是一份旧文档（`v0.7`，1,684 行；当前 `v0.16`，2,4xx 行）。现在：`recon_diagnose` 不挂开关（读已有任务数据），关闭 9 / 打开 11。类注释本轮已改成明确写 9 / 11 |
| **P2-1** 无幂等 + Map 不淘汰 | ✅ 成立 | 留账 D14 |
| **P2-2** 巡检每轮落一条 run + 抑制在进程内 | ✅ 成立 | 已在 D8（单实例假设）里记过；多实例抑制落库仍未做 |
| **P2-3** 0 处 `@Transactional` | ✅ 成立 | 留账 D15（`deleteJob` 的孤儿 diff 会污染全表聚合，确实该修，但属于独立 PR） |
| **P2-4** token 密钥未配时随机 | ✅ 成立 | 现在 `WARN`；生产 profile fail-fast 属配置治理，留账 |
| **P2-6** 缺 `CanaryInjectorTest` 与端到端链 | ✅ 成立 | 留账 D16 |

---

## 4 新增/更新的账本

| 编号 | 内容 | 为什么这轮不修 |
| -- | -- | -- |
| **D10** | `recon_diff` 长键/长值：加 `biz_key_hash` / `value_digest`，基线比较用摘要 | schema 变更 + 影响写入路径，需要单独一轮回归 |
| **D11** | `learn()` 的 explain 未计入 `AgentBudget` | 每次 run 至多 1 次批量调用；先修更贵的（P0） |
| **D12** | `GateHealthRepository.upsert` 无 CAS，多实例可能互相覆盖 | 单实例假设期内不构成风险；多实例方案要连带 D8 一起设计 |
| **D13** | 规则直写缺"专表审计"（现在只有 WARN 日志 + 行内 `created_by`） | 需要一张表与查询接口；先把"能不能写"关上比"写得全不全"更急 |
| **D14** | `TrialTaskRegistry` 无在途去重、完成项不淘汰 | 需要"需求摘要"的稳定键；与 P2-2 的保留策略一起做 |
| **D15** | `approve` / `deleteJob` / gate upsert 无事务 | 独立 PR，避免和本轮安全修复混在一起 |
| **D16** | 缺 `CanaryInjectorTest`、"起草 → 试算 → 审批"端到端用例 | 需要造样本与多入口串联，工作量大 |

---

## 5 评审里说对、且本轮没有改动的地方

- **"值得肯定的 8 条"** 全部属实，尤其 `RuleLifecycleService` 不写自己的 `if`（只读 `TrialState.approval()`）—— 本轮加 `requireTrialMatchesContent` 时仍然遵守：新增的是"证据是否对应当前内容"，不是"能不能批"。
- **"审批入口 fail-closed"** 属实：`recon.review.api-key` 未配置时 `RuleReviewController` 一律 403；本轮把同一条原则扩展到了规则直写。
- **"建议 P0-2 / P0-3 / P2-3 合并一个 PR"**：只采纳了前半 —— P0-2 与 P0-3 同轮做完，P2-3（事务）单独排，因为它改的是失败回滚语义，不是安全边界。

---

## 6 复现

```bash
cd D:/App/deepseek/workspace/recon-lens
JAVA_HOME=D:/App/deepseek/tools/jdk-17.0.20.1+1 \
  ~/.m2/wrapper/dists/apache-maven-3.9.16/*/bin/mvn -o test
# → Tests run: 353, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

新增用例一览：

| 用例 | 拦的是什么 |
| -- | -- |
| `RuleDefineGuardTest` | 默认配置下直写规则 403，且不留下半条规则 |
| `RuleDefineWithoutKeyTest` | 开关打开但没配密钥仍然 403 |
| `RuleLifecycleServiceTest.试算之后改过内容_旧结论不能拿来批准` | 四步全合法的"批准未试算内容"链 |
| `RuleLifecycleServiceTest.跳过试算之后改过内容_也不能直接批准` | 同上，走 SKIPPED 分支 |
| `RuleSafetyValidatorTest.首版规则的容差也有绝对上限` | `baseline == null` 时 `tolerancePercent: 90` |
| `RuleSafetyValidatorTest.倍数合规但超过绝对上限也要拦` | 5% → 9.5%（1.9 倍，倍数放行、绝对拦截） |
| `DiffsInWindowTest` | 数值键窗口用字典序比较会把基线滤空 |
| `CommonKeysSqlTest` | `INTERSECT` 依赖 + 去重计数 + 表达式键 |
| `AgentOrchestratorTest.draftIntentOnAnExistingRuleDoesNotRunProductionReconciliation` | 起草动作调用 `engine.run()` |
