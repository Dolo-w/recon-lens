# 未决问题与决定台账

> 用途：六批改造已收口，这里是「单租户 Agent 做扎实」阶段的开盘清单。
> 分四类：**已定**、**已给你口径**、**已核实**（结论 + 证据）、**待办**。
> 规则：所有点确认完再开工；开工顺序按依赖排，不按聊天顺序。

---

## 一、已定

### 1. 闸门模型：两道人工闸门 + 一个机械前置

- 只保留两道**人工**闸门：影子试算（软审批，看证据）、试算通过后的审批生效（硬审批，人按按钮）。
- `RuleSafetyValidator`（闸门 1）不承担安全职责的地位不变，但定位改成「**碰数据之前的机械前置**」，
  而不是「写草稿的入口检查」。
- 因此 `recon_define_rule` 与聊天 `DEFINE_RULE` **不接**校验器：写 DRAFT 无副作用，草稿可以不合规。
- `define` 在文档里明确定义为「**闸门 0：只做结构校验**」。

```
写草稿（不校验，无副作用）
   ↓
[机械前置] RuleSafetyValidator      ← 必须在"要碰数据"之前
   ↓
影子试算                            ← 软审批（人看证据）
   ↓
人工确认 + 生效                      ← 硬审批（人按按钮）
```

判断依据：影子试算会**对生产表发查询**（`SampleWindowProbe` 抽样、`RuleTrialRunner` 跑左右两侧）。
`MULTI_STATEMENT` / `SQL_NOT_READ_ONLY` / `HASH()` 若等试算跑起来才发现，语句已经发出去了。
所以校验器保护的对象不是草稿，是「**试算那一次查询**」。

### 2. 多租户：延后，不是排除

先把单租户做扎实，再扩展。延后期间只守三条零成本约束（见 `plan-roadmap.md` 五·二）。

### 3. 三条明确不做

置信度校准（无标注数据）、语料 ACL（ADR D8 产品决策）、真 ReAct（有意的成本/安全取舍）。

---

## 二、已给你口径（2026-10-27）

| # | 你的决定 | 落成什么 |
|---|---|---|
| Q1 | 「先审批再生效，审批这道关不能改」 | 改 `mcp-smoke.mjs` 走「建模型 → 草稿 → 审批 → 再跑」，不标 expected-fail |
| Q2 | 「LLM 的 key 是 `sk-cf36…`（DeepSeek）；启动命令里另有一把 key，是审批时传的，用 `vv`」 | **两把锁分开**：`--recon.review.api-key=vv`（审批，人签字）、`--recon.chat.llm.api-key=sk-…`（模型，可缺）。真模型用 `deepseek-flash`（该 key 可见的模型：`deepseek-flash`/`deepseek-v4-pro`）；key 不进仓库，只走环境变量/启动参数 |
| Q3 | 「看 false 和 true 谁收益高，实测完再看，场景要足够多、不能有幸存者偏差」 | 见 §五 实验设计 |
| Q4 | 「不配就起不来是说**第二把**（审批 key），不是 LLM 的 key；没有 LLM key 就降级 P1」 | 生产 profile 下 `recon.review.api-key` 缺失 → 启动即失败；**LLM key 缺失不算故障**，只记一条「按 P1 档运行」的 INFO。`recon.security.api-key`（数据面写接口）沿用原策略 |

---

## 三、已核实（2026-10-27，读代码）

### 1. `approve` 是否强制要求试算 PASS —— ✅ 强制，而且比预期严

`RuleLifecycleService.approve`（`rule/RuleLifecycleService.java:231`）三道关：

- `lifecycle != REVIEWING` → 拒；
- `token` 必须等于 `tokenFor(code, version, currentSha)` —— **绑内容哈希**，批的不是现在这一份就拒；
- `state.approval()`：`NotRun` → `NOT_APPROVABLE`，抛「还没跑试算，不能批准」；
  `FAIL` / `INCONCLUSIVE+NONE` → `BLOCK_HARD`，「任何身份都不能批准」。

另有 `requireTrialMatchesContent`（`:336`）：试算结论绑 `trial_spec_sha`，
试算之后内容被改过 → 结论作废；哈希缺失按 fail-closed 处理。
`TrialState` 用 sealed interface 把「有没有判定结果」拆成类型，`Trialed` 的构造器本身就是非法组合的编译期防线。

**结论：软审批不可被绕过，硬审批是真正的关。你的口径与现状一致，不需要改。**

### 2. `ReconEngine#run` 之前有没有校验器 —— ✅ 不需要有，路径不存在

`RuleCatalog.resolve` 只取 `runtimeVisible()`（即 ACTIVE，`rule/RuleCatalog.java:110`）。
DRAFT / REVIEWING 的版本根本不在 resolve 结果里，所以「审批前就执行」除了试算本身没有别的入口，
而试算前面站着闸门 1。

### 3. 试算被拒时 violations 是否结构化回传 —— ⚠️ **有洞**

`agent/ShadowTrialService.java:115-117`：

```java
List<SafetyViolation> violations = safetyValidator.validate(candidate, baseline);
if (!violations.isEmpty()) {
    throw new RuleException("闸门 1 拒绝 " + code + " v" + version + "，未进入试算：" + violations);
}
```

violations 被**拼进异常消息文本**，调用方拿到的是一个字符串。
REST / MCP / 老 smoke 脚本都只能靠正则从文字里抠，结构化信息（`code`/`field`/建议）丢了。
Agent 侧没这个问题，因为 `RuleDrafter` 自己调校验器拿结构化结果。

**改法（M-b）**：改成带 violations 的领域异常，HTTP 层返回
`{"error": "...", "violations": [{"code": "SHARD_COUNT", "field": "shard.count", "message": "..."}]}`，
MCP 侧放进 `structuredContent`。与批 6 的 `diagnose-blocked` 对齐。

### 4. MCP stdio 下 Agent 会话跨进程不可见 —— ⚠️ 确认是设计使然的缺陷

`TrialTaskRegistry` / `ImportTaskRegistry` 都是内存态，stdio 模式下每次 `tools/call` 可能落在不同 JVM。
批 6 实测撞到：`recon_draft_rule` 起的 runId，下一次 `recon_trial_status` 查不到。

**两条路**：(a) 文档规定「Agent 必须走 `/mcp`（streamable HTTP）」；(b) 任务态落库。
(a) 成本为零但限制宿主；(b) 才是真支持 stdio 多轮。建议先做 (a)，把 (b) 记成候选。

### 5. 新发现：还有一个能绕过审批的显式开关 —— `recon.model.require-approval=false`

`model/ModelBuilder.java:114-121`：

```java
boolean requireApproval = properties.getModel().isRequireApproval();
RuleCatalog.Resolved modelRule = requireApproval
        ? catalog.defineDraft(modelRuleBody, ..., "由 POST /api/models 机械生成：...仍需试算与人工审批后才能生效")
        : catalog.defineMap(modelRuleBody, "recon_build_model(" + spec.code() + ")", who);
```

默认 `true`（`ReconProperties.java:113`），所以**默认没有洞**；但这个开关一拨成 `false`，
`recon_build_model` 就直接写 ACTIVE，跳过试算与审批——与「审批这道关不能改」直接冲突。

**建议**：删掉这个开关（或只在 `default`/测试 profile 下允许），
`defineMap` 直通 ACTIVE 的通路保留给历史数据导入即可。
顺带解释清了 Q1 那两条红断言：smoke 是按「build_model 直通 ACTIVE」写的，而默认已是草稿，
所以它去 `recon_run` 一个 DRAFT 规则必然「不存在」。Q1 的改法（先审批再跑）与默认值天然一致。

### 6. 「IntentReview 只做反问」—— ✅ 已闭环（2026-10-28）

原来这条的未决点是：`IntentReview` 只负责判定"这句话是问句，不是命令"，并给出一段固定话术；
但这段话说出去之后会经过 `ChatNarrator` 的改写，实测被改成了"好的，我来帮你跑一下"——
话术的语义（我没有动手）当场反转。

现在这类回复走 `ChatRuntime#narrate(..., verbatim=true)`，**不过总结层**；`ChatRuntimeToolTest#反问守卫的回复逐字送达_不被总结层改写`
逐字比对 `IntentReview.question(...)` 的返回值。上游还有 `CompositePlannerTest#短问句不走快路径` 守着
（问句不被词表接走，否则根本走不到守卫）。见 [report-p2-toolcall.md](report-p2-toolcall.md) §2.4。

**剩下的问题改了措辞**：守卫只覆盖「`不是…吗/难道…吗`」这一种句式；"这几张表什么关系"这类真问句
靠的是模型直接 `reply`，不经过守卫。也就是说**守卫是补丁，不是意图分类器**——这条如果以后要扩大，
应该扩到"问句就不该有任何写动作"，而不是继续加句式。

### 7. 起草的口述参数：一句里带多个值（2026-10-28，新开）

决策式改造后，模型可以在第一跳就点名 `recon_draft_rule`，但它现在会落回 `defineRule → draftStep` 收参数
（见 [deep-dive-p2.md](deep-dive-p2.md) §⑤）。收集器沿用 P1 的 `DraftIntake.answer()`，它**默认"一句话只给一个值"**：
用户说「按订单、资源对账」时，「订单」「资源」被登记成两个"待定字段"，随后才逐个问"对应哪一列"，
中间那句「订单、资源」整句回过去会被判成"这句我没接上"。

现在能走通（现场六轮验证过），但**问的轮数比人期望的多**。两条路可选：
① 在 `answer()` 里做分隔符切分（顿号/逗号/和），按待定字段顺序各取一个；
② 干脆把"待定字段"这个小状态机交给模型（它已经知道还差哪一列）。
先记录，不在这轮做——它影响的是体验不是正确性。

> 2026-10-28 晚补充：单值限制现在有兜底了——翻不出来的词不会再被模型"顺手续写"成某一列，
> 而是先问一句「我准备就按 `updated_at` 写；是的话回「是」……」（`Hints#untraceable`，
> 见 [report-p2-toolcall.md](report-p2-toolcall.md) §5.3）。上面两条路仍是更彻底的做法。

### 8. 自由循环的跨轮状态：模型记不住"这一轮该收哪个槽"（2026-10-28，新开）

`recon.chat.mode=loop` 下不写 slots，模型靠**双向对话历史**（`ChatRuntime#recentDialogue`，助手侧截 120 字）
记上下文。实测（`report-loop-mode.md` §二）：用户回「bill_left」之后，下一轮模型仍会追问「资源指哪一列」——
它记得左表是 bill_left，但没有"只剩资源没收"这个概念，于是又从"两张表都看一遍"开始。

两条路可选：
① **模型自述状态**：循环里每轮让它输出 `state={left:bill_left, pending:"compare"}`，系统只存不用（下一轮拼进提示词）；
② **系统记账**：把 loop 模式的槽位重新接回来，但只当"事实"喂给模型，不拿它做分支（否则又变成编排）。

先记录：它影响的是"问的轮数"，不是正确性；而且和"不编排"的目标有张力（任何记账都是半个状态机）。

### 9. legacy 的起草收集器会劫持整个会话（2026-10-28，新开）

回退到 `legacy` 时，起草一旦挂起（`pendingAction=DRAFT_INTAKE`），**后续任何一句与起草无关的话都被翻译成"我还缺一个表名"**。
实测同一份八句剧本：`这几张表什么关系`、`用 BILL_VS_SETTLE 跑一次对账`、`确认`、`对完了` 全被回成
「这句我没接上。我还在等：右侧表」；只有只读工具调用（`recon_list_diffs`）能"让路"通过。证据：`logs/loop-probe-legacy.json`。

`loop` 模式没有这个问题（每句都由模型判断），但它不是修复——它绕过了这套状态机。
真要修 legacy，得给收集器加"这还不是回答，先放行"的出口（比如待定超过两轮就让路），
或者干脆承认"收集器在 loop 里退休"。**切模式时必须清 `pendingAction`**，否则悬着的回执会被下一条消息当确认。

### 10. 起草回执被 `max_tokens` 裁在半句话 —— ✅ 已闭环（2026-10-28 晚）

真实会话（alice，session 42）里，回执是「我理解你要起草规则 ORD_CUST_CONSU（回「确认」或「取消」）」：
编码都没说完。根因不是提示词，是额度——`ChatNarrator.confirm` 只给 160 token，
而这类模型先推理再说话，正文撞上 `finish_reason=length`。用同一段 prompt 直连复现过。

修法两条腿：额度提到 600，另加两道"宁可用模板也不发半句"的兜底（末字符必须是句末标点；
摘要里有大写规则编码、回执里却没有就退回模板）。`decorate` 同理。
证据：[report-p2-toolcall.md](report-p2-toolcall.md) §5.3。

### 11. legacy 下模型有时不调工具、只回话（2026-10-28 晚，新开）

同一句「左 bill_left 右 bill_right」，探针三次里有一次模型直接答「你是想建一条对账规则吗？请再给我规则名……」——
一次工具都不调，起草参数收集压根没启动；换一句「起草个规则：左 bill_left 右 bill_right」就正常。
一旦进入收集器，模型偶发调 `recon_describe_table` 去查「日期」，如实答"只有 `updated_at` 一个字段"
（只读工具让路，收集状态保住）——这一支是对的。

这是"模型自己决定下一步"的必然抖动，不是 bug 也不是本轮能修的：要么接受（用户再补一句就进正轨），
要么在回执里给一个"我要起草"的显式入口。**切到 `loop` 模式后这类抖动会减少但不会消失**。

---

## 四、待办（确认后开工，按依赖排序）

| # | 事项 | 依赖 | 量级 |
|---|---|---|---|
| M-d | `mcp-smoke.mjs` 改「建模型 → 草稿 → 审批 → 跑」（Q1） | — | 0.25 天 |
| M-e | `recon.security.api-key` 非 default profile 缺失即启动失败（Q4） | — | 0.25 天 |
| M-f | 删/收敛 `recon.model.require-approval=false` 这个可绕过审批的开关（§三·5） | — | 0.25 天 |
| M-b | 试算拒绝带结构化 `violations[]`（§三·3） | — | 0.5 天 |
| M-a | `suggestion` 补齐剩下 7 处（工具文本 → 结构化字段） | — | 0.25 天 |
| M-g | 文档规定 Agent 走 `/mcp`，stdio 明确标注「单轮」 | — | 0.1 天 |
| M-c | `p2` 与 `p1` 并入同一条 CI 命令（`EVAL_LABEL` 已支持） | Q2 | 0.25 天 |
| M-h | Q3 实验：循环开/关两档 × 70 条用例 | Q2 | 1 天 |

---

## 五、Q3 实验设计（避免幸存者偏差）

**语料分层**（每层 10 条，共 70 条，不按"能不能两步走通"挑）：

| 层 | 例子 | 为什么必须有 |
|---|---|---|
| L1 单动作明确 | 「列出规则库」 | 看循环有没有无谓地多调一次 |
| L2 链式隐含 | 「看下最近跑的任务」→「它差在哪」 | 循环的目标场景 |
| L3 缺参 / 歧义 | 「跑一下」「解释下那个」 | 循环会不会把猜的参数当真 |
| L4 写动作 | 「重新跑第 3 个任务」 | 必须停在回执，不能自动连做 |
| L5 闲聊 / 帮助 | 「你好」「你能干嘛」 | 不该触发工具 |
| L6 第二步会失败 | 首跳结果为空 / 任务 FAILED | 失败恢复，最容易出事故 |
| L7 参数依赖首跳文本 | jobId 只在首跳回执正文里 | 检验 `continuePlan` 的取值能力 |

**指标**（每档 70 次，人工判定完成度 1–5）：任务完成率、模型调用数、工具调用数、token、P50/P90 延迟、
重复执行次数、**误执行率**（不该写的写了）、第二步采纳率。

**判定规则（先写死，避免事后解释）**：
默认 `true` 的条件 —— 完成率提升 ≥ 10pp **且** 误执行率不升 **且** P90 延迟增幅 ≤ 60%；
否则保持 `false` + 白名单灰度。

**注意**：桩模型只能测"管道"（调用次数、第二步是否被拒），测不出"理解"。
L2/L3/L7 必须用真模型（Q2 的 `vv`），桩模型那部分只作管道回归。
