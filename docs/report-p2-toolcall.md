# P2 决策式改造报告：从「判一个意图」到「模型直选工具」

> 执行单：[plan-p2-toolcall.md](plan-p2-toolcall.md)
> 实测环境：真模型 `deepseek-flash`，端口 18097，文件库 `work/p2-demo`，2026-10-28 00:37–00:52（Asia/Shanghai；含写判据统一后的复跑）
> 回归：`./mvnw -o test` → **878 tests / 0 failures**（本轮起点 835）
> 原始证据：`logs/accept-p2.json`（三句话 / 8 轮预算 / 写动作回执的完整事件流）

一句话结论：**模型现在可以直接说「调哪个工具、带什么参数」，`ChatIntent` 退到遥测与 P1 兼容的位置。**
四条验收（工具 / 反问 / 结束、预算、写动作回执、守卫逐字）全部真跑通过；改造过程中暴露并修掉了四个只有真模型才会犯的错（见 §四）。

---

## 一、改了什么

### 1.1 四步对照

| 步骤 | 改前 | 改后 | 为什么 |
|---|---|---|---|
| ① 计划形态 | `ChatPlan` 只有 `Call`（意图 + 槽位）；模型只能选 15 个枚举值 | `ChatPlan` 多两个分量 `toolName` / `toolArgs`，外加 `reply`；模型输出三选一：`{"action":"tool","name":…,"args":{…}}` / `{"action":"reply","text":…}` / `{"action":"done"}` | 枚举是词表的形状，不是环境的形状。工具清单本来就是「能力」的唯一事实源，让模型直接对着它选 |
| ② 预算 | 单一档：facts 1200 / history 1500 / summary 600 / 合计 6100，历史按轮**按比例**截断 | 分两档：`UNDERSTANDING`（facts 2000 / history 4000 / summary 600 / 合计 12000）与 `ACTING`（原 6100）；理解档历史**整轮丢弃**，不砍半；新增会话级 `tokensUsed` 账本 | 「先理解清楚意图再谈预算」：意图没定时把用户原话砍掉，模型只会猜得更离谱。整轮丢弃保证活下来的是完整的句子 |
| ③ 执行 | `dispatch(call)` → `switch(call.intent())` 15 个固定分支 | `dispatch(plan)`：`plan.toolName()!=null` → `registry.find(name)` → `spec.invoke(args)` → `narrate`；其余仍走原分支 | 工具已经是注册表里的一等对象，不该再反推成枚举、再翻译回工具名（两次翻译就是两次走样） |
| ④ 路由 | 词表先命中即返回；模型只接管「词表不确定」的句子 | 模型可用时默认走模型；词表只剩两个出口：**零参数零歧义的短命令**快路径 + 熔断兜底 | 上一轮实测「这几张表什么关系」被词表当成「看数据」，答成表清单。词表擅长命令，不擅长问句 |

### 1.2 新增/改动的类

| 类 | 变化 |
|---|---|
| `chat/plan/ChatPlan.java` | `record ChatPlan(Call, confidence, reason, Source, options, Evidence, toolName, toolArgs, reply)` + `isToolCall()/isReply()` |
| `chat/plan/ToolCallPlanner.java`（新） | 协议层：把 `ToolCatalog.specs()` 拼成工具段提示词（含参数名）、解析三选一 JSON；未知工具**拒收**（返回空，交回老路径），不抛 |
| `chat/TurnBudget.java` | `enum Phase {UNDERSTANDING, ACTING}` + `rounds()`（整轮丢弃，不产生截断标记） |
| `chat/TurnLedger.java`（新） | 会话级账本 `Map<sessionId, tokensUsed>`，到 100k 才切 `ACTING` 档 |
| `chat/plan/LlmPlanner.java` | 提示词带工具清单；`parse` 认 `action=tool/reply/done`；历史改成整轮装填 |
| `chat/plan/CompositePlanner.java` | 工具/回复计划不被事实冲突与词表确定命中顶掉；问句不走快路径 |
| `chat/ChatRuntime.java` | `dispatch(plan)`、`dispatchTool`、工具写动作的回执（含暂存参数）、`narrate` 的 verbatim 通道、`writeAction` 统一写判据（表单 `confirmed=true` 例外）、起草工具接回 `draftStep` 收参 |
| `chat/DraftIntake.java` | `Hints.withoutHint()`（问过不存在的字段就别再问）、`withSides(left,right)`（单说的一张表归右） |
| `chat/plan/LlmChatClient.java` | `completeJson` 的 `max_tokens` 400 → 900（半截 JSON 会触发熔断） |

---

## 二、四组证据

### 2.1 三句话 → 三种输出（真模型）

同一会话连发，逐条看 `plan` 事件与 `tool` 事件：

| 输入 | 计划 | 工具 | 回复（节选） |
|---|---|---|---|
| `看下差异` | `KEYWORD / LIST_DIFFS / 0.78` | `recon_list_diffs` | 「最近这次生产任务 #5 查到 4 条差异，其中 3 条高风险、1 条中风险。最值得看的是 C002\|ORD20260003 的金额差：左边 1500，右边 1500.3（相对偏差 -0.02%）……」 |
| `这几张表什么关系` | `LLM / HELP / 0.80 / reply=true` | 无 | 「bill_left 是账单侧，bill_right 是结算侧，按 `__biz_key`（客户+订单号）匹配后比 amount……要我把某条规则的匹配字段和维度列出来吗？」 |
| `对完了` | `LLM / reply=true` | 无 | 「好，这轮就到这儿；要看差异、任务或规则，直接说。」 |

三句分别命中「调工具 / 说话 / 结束」——
第一句仍是词表快路径（4 个字、无参数、非写），这正是步骤 4 要保留的那条出口；
第二、三句在第一版里都被词表接走了（答成表清单 / 答成功能菜单），修法见 §四·2 与 §四·1。

### 2.2 预算：8 轮长消息，用户原话一句没少

每轮约 420 字，头尾各带一个可检索的口令（`HEAD-N-7K2` / `TAIL-N-9Q5`）：

| 轮 | 本轮新增 token | `tokensUsed` 累计 | 档位 |
|---|---|---|---|
| 1 | 847 | 847 | UNDERSTANDING |
| 2 | 1137 | 1984 | UNDERSTANDING |
| 3 | 1427 | 3411 | UNDERSTANDING |
| 4 | 1717 | 5128 | UNDERSTANDING |
| 5 | 2007 | 7135 | UNDERSTANDING |
| 6 | 2297 | 9432 | UNDERSTANDING |
| 7 | 2587 | 12019 | UNDERSTANDING |
| 8 | 2877 | 14896 | UNDERSTANDING |

跑完回读 `/api/chat/sessions/{id}?limit=100`：**落库用户消息 8 条，逐字一致 8 条，含「预算截断」标记 0 条。**
对照改前：历史桶 1500 token、按轮按比例分，第 4 轮起每轮只能分到 ~250 token，用户原话一定会被 `…[预算截断]` 切开。
现在理解档给 4000 token，装不下就整轮丢弃并在提示词里说明「更早的 N 轮没有放进上下文，需要细节请让用户再说一次，不要猜」——
模型不知道的事就明说，不会把半句话当成完整需求。

### 2.3 写动作：模型直接点名 `recon_run`，停在回执

```
[发] 直接调 recon_run 工具，ruleCode=BILL_VS_SETTLE
[计划] LLM / tool=recon_run / args={ruleCode:BILL_VS_SETTLE} / 0.90
[回执] 我理解你要用规则 BILL_VS_SETTLE 跑一次对账，确认执行吗？（回「确认」或「取消」）
[tool 事件] 0 个；任务数 5 → 5
[发] 确认
[tool 事件] recon_run → 任务 #9｜BILL_VS_SETTLE｜SUCCESS｜左10右10｜匹配9｜差异4
```

关键点不在「能跑起来」，而在**两段式仍然完整**：工具路径的写动作在 `handle` 里就被 `ToolNames.WRITES`
拦成回执，参数（规则编码）随回执一起暂存；用户点头后 `handleConfirm` 用**暂存的原始参数**执行，不重新从槽位猜一遍。
另外 `dispatchTool` 里留了第二道防线（`!confirmed` 时才拦）——第一版没有 `confirmed`，
「确认」之后又被要了一次「确认」，死循环（§四·3）。

### 2.4 守卫话术：逐字送达

上一轮实测：`IntentReview` 判定为反问并回「你这句是在问……不是要我执行……」，
但这句话随后被总结层改写成了「好的，我来帮你跑一下」——一句话把「我没有动手」变成了「我要动手」。

现在 `IntentReview.question(...) != null` 的回复走 `narrate(..., verbatim=true)`，**不过总结层、不清 agent 尾巴**。
单测 `ChatRuntimeToolTest#反问守卫的回复逐字送达_不被总结层改写` 直接比对
`text.stripTrailing()` 与 `IntentReview.question(message, LIST_DIFFS)` 的返回值；
`CompositePlannerTest#短问句不走快路径` 守住上游（问句不被词表接走），
`LlmPlannerTest#done给收尾句` 守住下游（`done` 不再落回功能菜单）。

---

## 三、回归与测试

- 全量：`./mvnw -o test` → **878 / 0 / 0**（起点 835；本轮四步做完时 868，晚间的会话 42 修复又加 10 例）。
- 新增测试文件：`chat/plan/ToolCallPlannerTest`（10 例）、`chat/ChatRuntimeToolTest`（10 例：工具执行 / 写动作回执 / 守卫逐字 / 起草收参五条）。
- 补测后的规模：`TurnBudgetTest` 9 例（理解档不被砍半、整轮丢弃不产生截断标记、账本到阈值切档）、
  `LlmPlannerTest` 25 例（其中 `done` 收尾句与 `done` 带总结是实测后补的）、
  `CompositePlannerTest` 14 例（工具计划不被事实冲突拦下、不被词表确定命中顶掉、短问句不走快路径）。
- 写动作的两段式没有新增测试去「证明」`confirmed`——它由 `ChatRuntimeToolTest#写工具停在回执_确认后按暂存参数执行`
  的 `doesNotContain("确认执行吗")` 守着，这条断言是踩了套娃之后补的。

---

## 四、真模型暴露的五个问题（都已修）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 「对完了」被答成「我能做这些：看规则 / 看数据 / …」 | `{"action":"done"}` 被解析成 `empty`，交回词表兜底，词表只能给 HELP | `done` 带上一句收尾话（`ChatPlan.reply(DONE_CLOSING)`）；`done` 也可以带模型自己的总结 |
| 2 | 「这几张表什么关系」被答成表清单 | 短句（9 字）+ 词表「看数据」确定命中 + 无实体 → 走快路径，模型根本没上场 | 快路径加问句闸：`吗/呢/？/？` 结尾或含「什么/怎么/为什么/关系/如何…」就不算命令。**不能含「哪」**——「有哪些规则」会被误判，实测撞过一次 |
| 3 | 回执后回「确认」，又被问一次「确认执行吗」 | `dispatchTool` 的写动作防线不知道「这次是确认后的执行」，永远拦 | 加 `confirmed` 参数：只有 `handleConfirm` 传 `true` |
| 4 | 同一句「跑一下 BILL_VS_SETTLE」有时问、有时不问 | 写动作有两条判据：工具形态一律回执；意图形态在"原话出现大写规则编码"时豁免 | 合并成 `writeAction(plan)` 一条判据（§5.1），豁免删掉；表单入口显式 `confirmed=true` |
| 5 | 「起草个规则：按订单、资源对账」→ 回执里出现 `recon_draft_rule`，确认后直接起草，**全程没问「资源」是哪一列** | 回执文案把工具注册名当人话；`plan.isToolCall()` 分支直接 `spec.invoke`，绕过了 P1 的起草参数收集 | 起草工具改接 `defineRule → draftStep` 收集路径；回执话术换成中文动作名（§5.2） |

五条都属于「单测测不出、桩模型也测不出」的语义问题——它们不是崩溃，是**说得通但不对**。

---

## 五、已知边界（不打算在这轮解决）

1. ~~**意图路径仍然存在，且它的写动作豁免规则与工具路径不同。**~~ **已统一（2026-10-28 追加，见 §5.1）。**
2. **一次请求仍然最多一次工具调用**（开 `loop.enabled` 时两步）。真 ReAct 的自由循环不在本轮范围。
3. **词表快路径保留**：≤12 字、零参数、非写、非问句的短命令（「看下差异」「有哪些规则」）仍然 0 延迟 0 成本。
   它是熔断时的唯一兜底，删掉等于把可用性押在模型上。
4. **`tokensUsed` 是估算值**（`Tokens.estimate`），不是模型 `usage`。账本只用来「决定要不要收紧档位」，
   精度够用；要精确到账单仍得接 `usage`。
5. **`ACTING` 档在实测里一次也没触发**（14896 远低于 100k 阈值）。分档逻辑由单测覆盖，真模型下尚未观察到切换行为。

### 5.1 写动作判据统一（追加，2026-10-28）

**问题**：同一句「用 BILL_VS_SETTLE 跑一次」，模型给 `action=tool` 时回执、给 `action=RUN`（意图形态）时
因原话里出现大写规则编码而被 `needsWriteConfirm` 豁免 → **直接执行**。两种行为对用户不可解释——
用户看到的只是"有时候问、有时候不问"，取决于模型这次选了哪种 JSON。

**修法**（`ChatRuntime.java`）：

```java
// 写动作只有一个判据：不管是工具形态还是意图形态，参数齐了就一律回执
boolean write = writeAction(plan);

private static boolean writeAction(ChatPlan plan) {
    if (plan.isToolCall()) return ToolNames.WRITES.contains(plan.toolName());
    return plan.call() != null && writeReady(plan.call());   // RUN/BUILD_MODEL 要 ruleCode，RETRY 要 jobId，DEFINE_RULE 要正文
}
```

- 删掉 `needsWriteConfirm` 里两条「原话点名了就不问」的豁免（`ruleCode` 大写命中、`jobId` 命中）。
- `dispatch` 里补第二道同判据（`!confirmed && writeReady(call)`）：第二步、确认后执行、表单之外的所有入口都从这里进，
  漏一个就是"没点头就跑了"。
- **表单入口显式传 `confirmed=true`**（`ChatRuntime#runGuarded`）：表单项是"点名动作 + 填参数"的一次提交，
  等价于已经确认；再问一句只会让人点两次。这是唯一允许跳过回执的入口，理由是它不经自然语言。

**现场验证**（`work/p2-check-writegate.ps1`，真模型 18097）：

```
[1] 意图形态的写动作停在回执：PASS     plan.intent=RUN / confidence=0.90 / tool=recon_run
    tool 事件 0 个；任务数 3 → 3
    回执：我理解你要用 BILL_VS_SETTLE 给最近的任务跑一次对账，确认执行吗？
[2] 确认后按暂存参数执行：PASS         tool 事件 recon_run；任务数 3 → 4
    任务 #4｜规则 BILL_VS_SETTLE｜状态 SUCCESS
```

单测 `ChatRuntimeTest#点名了规则也要先回执_写动作的判据不分形态` 用词表形态（`action=RUN`，没有 LLM key）
把这条路钉死：首轮 `doesNotContain("tool")`，确认后才出现 `tool`，且不再问第二次。

**代价**：`ChatRuntimeTest` 里三条「跑一下 BILL_VS_SETTLE 直接执行」的用例改成两段式（新增 `confirmedRun` 辅助）；
`ChatFormApiTest` 不受影响（表单路径）；`ChatStatsApiTest` 的 `byIntent.RUN` 从 1 变 2——
**口径变化要记住：`byIntent` 按"执行轮次"记，写动作两段式会把一次 RUN 记成两轮。**

### 5.2 起草：从「点一下就执行」回到「先问清楚」（追加，2026-10-28）

这是用户拿真界面撞出来的（原话："现在运行的不是最新的吗…不存在的字段也不跟我确认"），
一开始我怀疑是包没更新，查下来**跑的确实是最新包**，是两个真的缺口：

**现象**：`起草个规则：按订单、资源对账` → 界面 `模型选择工具 recon_draft_rule` → 回执
「我理解你要起草一条按订单、资源对账的规则，**对应执行 recon_draft_rule**，确认执行吗？」→ 确认后
「已创建 Agent 会话 #2，起草与影子试算在后台进行…」。全程没问过「订单」「资源」是哪一列。

**两个根因**：

| # | 根因 | 位置 |
|---|---|---|
| 1 | 回执文案把工具注册名当人话：`describeTool` 在 `intent==HELP` 时退化成 `"执行 " + toolName`，再被 `ChatNarrator.confirm` 改写照样原样保留 | `ChatRuntime#describeTool` |
| 2 | `plan.isToolCall()` 分支直接 `spec.invoke(args)`，绕过了 P1 的起草参数收集 `draftStep`（`DraftIntake` 解析表/业务键/比对字段） | `ChatRuntime#dispatch` |

**修法**：

```java
// 1) 回执只说人话：工具名是给宿主大模型看的，不往界面上搬
private static String describeTool(ChatPlan plan) {
    if (plan.call() != null && plan.call().intent() != ChatIntent.HELP) return describeCall(plan.call());
    return switch (plan.toolName()) {
        case ToolNames.DRAFT_RULE -> "起草一条对账规则";
        case ToolNames.RUN -> "跑一次对账" + …;
        … default -> "做这一步";
    };
}

// 2) 起草不是"一次动作"，先接回参数收集
if (ToolNames.DRAFT_RULE.equals(plan.toolName())) {
    return defineRule(scope, new Call(ChatIntent.DEFINE_RULE, null, …), slots, message, tools, sink);
}
```

配套的三处（都是实测逼出来的）：

- `writeAction` 对 `DRAFT_RULE` 返回 `false`：起草要**先收参数**，回执由骨架生成之后再要
  （"我理解你要起草规则 ORDER_AMOUNT_RECON，以 order_no 为维度、按 amount 字段做比对，确认执行吗？"）。
- **收集期间收集器优先**：`resumePending` 里原来 `if (plan.isToolCall()) return plan`，会让"回 bill_right"被
  模型选的 `recon_describe_table` 抢走、收集状态卡住。现在 `intakeOwnsTurn = intakePending && (!readOnlyTool || 解析得出参数)`，
  为真就把 plan 覆盖成"接着收起草参数"（只读工具让路；写工具和闲聊照旧）。
- **问过"这个字段不存在"就摘掉 hint**（`DraftIntake.withoutHint()`）：否则每轮都重问同一句，
  而且已收的表/键不能因为一句听不懂就丢——`draftStep` 改成把已收参数 + `question` 一起塞回 pending。
- **只差右侧表时，单说的一张表归右**（`Hints.withSides(prior.left, hints.left)`）：否则会出现
  「左 bill_right，右 bill_right」这种自己跟自己对的骨架。这里先写成 `withRight(hints.left())`，**
  实测仍然错**（内部保留了被覆盖的旧左表），换成 `withSides` 才对——单测 `只差右侧表时_单说的表归到右边` 钉住。

**现场验证**（`work/p2-check-draftintake.ps1`，真模型 18097，六轮一句话）：

```
你> 起草个规则：按订单、资源对账
它> 好，先说说要对哪两张表。左侧用哪张表？可对账的表 bill_left / bill_right
   （已记下：待定字段「订单、资源」）
你> bill_left
它> 记下了。不过「资源」在 bill_left 的列里没找到（现有 id、order_no、customer_id、amount、status、updated_at）。
   是要去掉这一维，还是它对应哪一列？
你> bill_right
它> 已记下：左 bill_left，右 bill_right，业务键 order_no。比对看哪些字段？（比如 amount、status）
你> 资源就用状态吧
它> 我理解你要起草规则 ORDER_STATUS_RECON，以 order_no 为维度、按 status 字段做比对，确认执行吗？   ← 无工具名
你> 比金额
它> 我理解你要起草规则 ORDER_AMOUNT_RECON，以 order_no 为维度、按 amount 字段做比对，确认执行吗？
你> 确认
它> 规则 ORDER_AMOUNT_RECON 的草稿 v1 已落库（操作人 tester）…影子试算已排队
```

**代价与口径**：

- `ChatRuntimeToolTest` 从 3 例长到 10 例；其中"收集途中模型插话""收集途中只读工具让路""只差右侧表"三条
  是现场才发现的边界，桩模型写不出来。
- 起草收集轮**不发模型请求**（收集器接管），所以 `tool 选择率` 的分子分母里都不含这些轮——
  它们看起来像"没有工具调用"的对话轮，实际是"还没到调工具的时候"。
- `LlmChatClient.completeJson` 的 `max_tokens` 从 400 提到 **900**：起草时模型会把长参数原样回传，
  400 装不下的 JSON 被截断成半截 → 解析失败 → 三次后熔断 300 秒，接下来几轮全落词表
  （复跑验收时 A2/A3 突然变成表清单和 HELP 菜单，就是这条）。

### 5.3 回执被裁在半句话，和模型替用户认列名（追加，2026-10-28 晚）

现场：alice 的真实会话（`work/p2-demo`，18097 日志 18:14–18:16，session 42）。六轮里两处。

**一、回执被 `max_tokens` 裁掉**

用户看到的是「我理解你要起草规则 ORD_CUST_CONSU（回「确认」或「取消」）」——
编码都没说完。`ChatNarrator.confirm(...)` 原来给 160 token，而 `deepseek-flash` 这类模型
先推理再说话，额度烧在推理上，正文 `finish_reason=length`。用同一段 prompt 直连复现过：
`usage.completion_tokens=160`，`content` 停在「…维度为 order_no、customer_id 和 updated」。

改法（`chat/ChatNarrator.java`）：

| 改前 | 改后 |
| --- | --- |
| `completeText(CONFIRM_SYSTEM, prompt, 160)` | `CONFIRM_TOKENS=600` |
| 只判"空"和"超 200 字" | 再加两道：`looksComplete`（末字符必须是句末标点或收尾引号）、`ruleCodeIn`（摘要里有大写编码、回执里却没有 → 退回模板） |
| `decorate` 的总结被裁也照发 | 同样用 `looksComplete` 兜住，裁到半句就退回原文 |

确认这一步宁可句式死板，也不能让用户对着半句话按"确认"。现场同一句现在输出：
「我理解你要起草规则 ORDER_CUSTOMER_AMOUNT_RECON，按 order_no 和 customer_id 两个维度比对 amount，确认执行吗？」。

**二、模型替用户"认列名"没人复核**

用户说「订单、客户、日期吧」，骨架里出现 `dimension: [order_no, customer_id, updated_at]`——
`updated_at` 是真实存在的列，但用户从没这么说过；下一句「我说的日期是消费日期」也没有纠正，
直接又生成一遍。原因：`fillWithModel` 只校验"这列存在"，不校验"用户的原话指得到它"。

改法：`DraftIntake.Hints#untraceable(evidence, columns)`——只对**这一轮新填**的 key/compare 列核对，
两种来源算"指得到"：原话里出现了列名本身，或出现了能翻到它的中文别名（`ROOTS`：订单→order、
金额→amount、客户→customer、更新时间→updated…；"日期"不在表里）。对不上就先问：

```text
「updated_at」表里是有的，但你刚才那句话里没有能对上它的说法。
我准备就按它写；是的话回「是」，不对就说列名（现有：id、order_no、customer_id、amount、status、updated_at），或者回「去掉」。
```

猜的那几列先留在槽位里等点头（回「是」直接往下走，回「去掉」把这一列摘掉、别的不动）。
顺带把"字段翻不成列名"那一问补全：告诉用户不回答的话这一维就先不写进规则
（原来只问"要去掉还是要对应哪一列"，用户答了别的事就静默丢了）。

**这道核对只覆盖"新填"的列**：把前几轮用户自己说过的话重新审一遍会误伤——
实测第一批测试里，回完 `order_no` 再答「比金额」被当成"猜列"，业务键当场被丢掉。

**三、问出去的话，答案要算数**

上面那句问话当场又暴露一个新坑：用户答「就用 updated_at 吧」，系统照旧出骨架，
`updated_at` 没进维度——问是问了，答案没人接。修法：把认不出的那个词（`hinted`）记进槽位，
下一轮先看这句话里点到哪一列（整句里出现列名，或分词后能翻），点到了就并进维度再往下走。

**四、带骨架的回执不许改写**

同一轮里总结层还给回执添过一句用户没说过的「以 updated_at 为准」（模型把提示词里的词
和这一轮的输入混在一起）。骨架是要落库的正式文本，改一个字段就是改规则——
所以 `narrate` 现在见到正文里有 ` ```yaml ` 直接原样返回，不过总结层。

现场（18097，`work/p2-check-guesscol.ps1` + `work/p2-ask.ps1`）：

```text
你> 起草个规则：按订单、客户、日期对账，比金额，左表 bill_left 右表 bill_right
它> 「日期」在 bill_left 的列里没找到（现有：id、order_no、customer_id、amount、status、updated_at）。
    是要去掉这一维，还是它对应哪一列？
你> 就用 updated_at 吧
它> 我理解你要起草规则 ORDER_CUST_UPD_AMOUNT_RECON，以 order_no、customer_id、updated_at 为维度
    比对 amount，确认执行吗？
    （骨架 dimension: [order_no, customer_id, updated_at]，与回执逐字一致）
```

**已知没修的一条**：legacy 模式下模型有时不调工具、只回话（同一句「左 bill_left 右 bill_right」
三次里有一次变成"你是想建一条对账规则吗？请再给我规则名…"），起草收集压根没启动。
这属于 `docs/open-questions.md` §三·11 那条（legacy 编排与模型的边界），不是本轮范围。


---

## 六、文件清单

```text
src/main/java/io/github/dolow/reconlens/chat/plan/ChatPlan.java          改（9 分量 + 工具/回复工厂）
src/main/java/io/github/dolow/reconlens/chat/plan/ToolCallPlanner.java   新（协议 + 解析 + 工具段提示词）
src/main/java/io/github/dolow/reconlens/chat/TurnBudget.java             改（Phase + rounds 整轮丢弃）
src/main/java/io/github/dolow/reconlens/chat/TurnLedger.java             新（会话级账本）
src/main/java/io/github/dolow/reconlens/chat/plan/LlmPlanner.java        改（工具清单 + 三选一解析 + 阶段选档）
src/main/java/io/github/dolow/reconlens/chat/plan/CompositePlanner.java  改（工具/回复豁免 + 问句不进快路径）
src/main/java/io/github/dolow/reconlens/chat/plan/ChatPlanner.java       改（带 phase 的默认方法）
src/main/java/io/github/dolow/reconlens/chat/ChatRuntime.java            改（dispatch(plan) / dispatchTool / verbatim / confirmed）
src/main/java/io/github/dolow/reconlens/chat/ChatEvent.java              改（plan 事件带 tool/args/reply；新增 budget 事件）
src/main/java/io/github/dolow/reconlens/chat/ChatConfiguration.java      改（装配 ToolCallPlanner / TurnLedger）
src/main/java/io/github/dolow/reconlens/chat/DraftIntake.java             改（Hints.withoutHint / withSides）
src/main/java/io/github/dolow/reconlens/chat/plan/LlmChatClient.java        改（max_tokens 400 → 900）
src/test/java/io/github/dolow/reconlens/chat/plan/ToolCallPlannerTest.java   新（10 例）
src/test/java/io/github/dolow/reconlens/chat/ChatRuntimeToolTest.java        新（10 例）
src/test/java/io/github/dolow/reconlens/chat/ChatRuntimeTest.java            改（+1：写动作判据不分形态；3 例改两段式）
src/test/java/io/github/dolow/reconlens/web/ChatStatsApiTest.java            改（写动作多一轮「确认」）
src/test/java/io/github/dolow/reconlens/chat/TurnBudgetTest.java             改（+3）
src/test/java/io/github/dolow/reconlens/chat/plan/LlmPlannerTest.java        改（+4）
src/test/java/io/github/dolow/reconlens/chat/plan/CompositePlannerTest.java  改（+3）
work/p2-real.ps1                新（起停 18097，按 server.port 定位，避免 jar 被锁）
work/p2-accept-toolcall.ps1     新（四条验收的自动化脚本，证据写 logs/accept-p2.json）
work/p2-check-writegate.ps1     新（写动作回执的现场校验：意图形态也必须停住）
work/p2-check-draftintake.ps1   新（起草收参的现场校验：不存在的字段必须问）
```

---

## 七、复现

```powershell
$env:JAVA_HOME = "D:\App\ideaIC-2025.3\jbr"          # 机器上唯一可用的 JDK 21
.\mvnw.cmd -o test                                    # 868 绿

.\work\p2-real.ps1 -Stop                              # 先停旧的（按 server.port=18097 找 java 进程）
.\mvnw.cmd -o package -DskipTests
.\work\p2-real.ps1                                    # 起新包：真模型 deepseek-flash，库 work/p2-demo
.\work\p2-accept-toolcall.ps1                         # 跑四条，证据落 logs/accept-p2.json
.\work\p2-check-writegate.ps1                         # 写动作两条形态都先回执（§5.1）
.\work\p2-check-draftintake.ps1                        # 起草先收参数、不问工具名（§5.2）
```

注意：`-Dtest=A,B` 在 PowerShell 里必须整体加引号（逗号会被当数组分隔符，报「缺少参数」）。
