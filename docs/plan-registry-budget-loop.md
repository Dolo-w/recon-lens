# 三个补丁：工具目录合一 / 每轮 token 预算 / 单请求最多三步

> 结论先行：三个补丁可以独立上线，顺序 1 → 2 → 3。
> 1 是结构修正（半天，零行为变化）；2 是结构修正 + 配置（一天，零行为变化）；3 是唯一有行为变化的（两天，默认关闭）。
> 3 必须在 1、2 之后：它要求"工具只有一份"和"预算只有一个账本"，否则 loop 会把两处漂移放大成两倍。

## 0. 先把数字钉死（补丁一的动机）

| 入口 | 现在给几个 | 来源 |
|---|---|---|
| `GET /api/tools`（控制台/脚本） | **12** | 注入的是 `ReconToolRegistry`，只含基础工具 |
| 宿主 `tools/list`（MCP） | **18** | `McpSpecifications.build()` 里 12 基础 + 6 Agent |
| README | 18（P0 段写 12） | 与实际相符，但与 `/api/tools` 不符 |
| `McpServerRunner` 类注释 | 15（9 + 6） | 陈旧 |
| `AgentMcpTools` 类注释 | 14（9 + 5） | 陈旧，漏了 `recon_import_status` |

差距是 6 个 Agent 工具只在宿主侧存在。ADR-0001 D1 承诺的"三档共用同一份目录"因此只兑现了一半：
**控制台看不到、脚本看不到、内建会话也调不到这 6 个工具**（`ChatRuntime` 只有 `TRIAL_STATUS` 一个走这里）。

顺带一个连带的漂移：`ToolNames` 里有 `DRAFT_RULE / TRIAL_STATUS / REGISTER_TABLE / IMPORT_FILE / SUBMIT_APPROVAL`
五个常量，但 `AgentMcpTools` 里写的是字面量 `"recon_draft_rule"`；而 `recon_import_status` 连常量都没有。
常量写了不用，等于没写。

---

## 补丁一：`ToolCatalog` —— 只留一个目录

### 改什么

新增 `tool/ToolCatalog.java`（`@Primary`，唯一的 `ToolRegistry` Bean）：

```java
/**
 * 合并后的唯一目录：基础工具 +（Agent 开启时）Agent 工具。
 *
 * <p>为什么必须是 @Primary 的一个 Bean，而不是"两个 Bean 各自被注入"：
 * 只要有两个注入点，就会有人只注入其中一个 —— {@code /api/tools} 当初就是这么少掉 6 个的。
 * 合成一个之后，"能列出来的"和"能调到的"是同一个对象，不可能再分叉。
 */
@Primary
@Component
public class ToolCatalog implements ToolRegistry {

    private final List<ToolSpec> specs;

    public ToolCatalog(ReconToolRegistry base, ObjectProvider<AgentToolRegistry> agent) {
        List<ToolSpec> merged = new ArrayList<>(base.specs());
        AgentToolRegistry extra = agent.getIfAvailable();
        if (extra != null) {
            merged.addAll(extra.specs());
        }
        // 重名即启动失败：静默覆盖会让「宿主能调、控制台调不到」这类问题重新长回来，
        // 而它只在用户点下去的那一刻才暴露。
        Set<String> seen = new HashSet<>();
        for (ToolSpec spec : merged) {
            if (!seen.add(spec.name())) {
                throw new IllegalStateException("工具重名：" + spec.name()
                        + "（检查 ReconToolRegistry / AgentToolRegistry）");
            }
        }
        this.specs = List.copyOf(merged);
    }

    @Override public List<ToolSpec> specs() { return specs; }
    @Override public Optional<ToolSpec> find(String name) {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
```

新增 `tool/AgentToolRegistry.java`（`@ConditionalOnProperty(recon.agent.enabled)`），
把 `AgentMcpTools.specifications()` 里那 6 段 `McpToolFactory.tool(...)` 翻译成 `ToolSpec`：
名字走 `ToolNames`，handler 调 `AgentMcpTools` 的业务方法，结构化视图仍走 `ToolOutcome.of(text, view)`。

然后三处删除：

| 文件 | 删除 | 理由 |
|---|---|---|
| `mcp/McpSpecifications.java` | `build(jsonMapper, registry, agentTools)` 的第三参、`agentTools.forEach(...)` | 目录已合并，翻译只做一次 |
| `mcp/McpServerRunner.java` | 字段 `List<AgentMcpTools> agentTools` 与构造参数 | 同上；`specifications()` 只需 `registry` |
| `mcp/AgentMcpTools.java` | `specifications(McpJsonMapper)` 整个方法 | 它不再是"MCP 工具"，只是业务方法集合 |

`ToolController` 不用改：它注的就是 `ToolRegistry`，现在拿到的是合并后的目录。

### 顺带修掉的两颗小雷

1. `ToolNames` 补 `IMPORT_STATUS = "recon_import_status"`，`AgentToolRegistry` 里**不许出现工具名字面量**。
2. 三个类注释里的手写数字（15 / 14 / 18）删掉，改成一句"数量以 `GET /api/tools` 为准"。
   手写数字是这份文档里已经错过两次的东西，不该再有第三份。

### 验收

- `ToolCatalogTest`：无 Agent → 12；有 Agent → 18；两条同名 spec → 启动抛 `IllegalStateException`。
- `CatalogParityTest`：同一个 Spring 上下文里，`toolController.list().get("total")` 与
  `McpSpecifications.build(jsonMapper, registry).size()` 必须相等 —— 这条断言就是 D1 的可执行版本。
- `mvnw test` 全绿；`tools/mcp-smoke.mjs` 与 `agent-smoke.mjs` 不改一行仍通过。

---

## 补丁二：`TurnBudget` —— 一个账本，五个桶，固定砍序

### 现状

预算散在至少七个常量里，互不知道对方：

| 位置 | 常量 | 管的是 |
|---|---|---|
| `AgentOrchestrator` | `MAX_STEP_PAYLOAD = 6000`、`scrub(input, 500)` | 单步轨迹与输入截断 |
| `RuleDrafter.buildPrompt` | `dataBlock(..., 4000)` × 4 段 | 起草提示词 |
| `ChatSummaryService` | `window * 0.55`、`>= 4 轮`、`>= 2000 token` | 摘要触发 |
| `ReconMcpTools.listDiffs` | `limit ≤ 500`，返回前 50 | 工具结果 |
| `ContextFacts` | 各自裁剪 | 事实卡片 |

结果：一轮会话真实占用只有事后从日志里估，事前无法控制；两个桶同时膨胀时会互相挤压，
挤掉的是谁不取决于重要性，取决于谁先拼进字符串。

### 新增 `chat/budget/TurnBudget.java`

```java
/**
 * 一轮会话的 token 账本。刻意做成"桶 + 上限 + 记账"，而不是一个总函数：
 * 只有分了桶，才能在总量超的时候按重要性砍（先砍工具结果，最后砍事实），
 * 而不是按拼接顺序砍（最后拼进来的先没）。
 */
public final class TurnBudget {

    /** 桶名即配置键，顺序即"被砍的逆序"：越靠后越先被砍。 */
    private static final List<String> CUT_ORDER =
            List.of("tool", "summary", "history", "facts");   // system 与 reply 永不砍

    private final int total;
    private final Map<String, Integer> caps;
    private final Map<String, Integer> spent = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    /** 放一段文本进预算：超桶上限就截断，并把"截掉了多少"写进正文。 */
    public String fit(String bucket, String text) {
        int cap = caps.getOrDefault(bucket, 0);
        int tokens = ChatSummarizer.estimateTokens(text);   // 复用已有的估算，不另写一套
        if (tokens <= cap) {
            spent.merge(bucket, tokens, Integer::sum);
            return text;
        }
        String cut = clip(text, cap);
        notes.add(bucket + " 裁掉了 " + (tokens - cap) + " token");
        spent.merge(bucket, ChatSummarizer.estimateTokens(cut), Integer::sum);
        // 标记必须给读者看：模型不知道自己在看节选，就会把"没看到"当成"没有"。
        return cut + "\n……（本节已按预算裁到 " + cap + " token，完整内容可让我按 offset 再取一次）";
    }

    public boolean exhausted() { return spentTotal() >= total; }

    /** 总量超了就从 CUT_ORDER 头部往后砍，砍到够为止；砍不动的桶记账不抛异常。 */
    public void rebalance() { ... }

    public String describe() { ... }   // "tool 1832/2000 · facts 1180/1200 · 合计 5310/6000"
}
```

配置（`recon.chat.budget.*`，全部有默认值，不配也能跑）：

```yaml
recon:
  chat:
    budget:
      total-tokens: 6000     # 约等于 8k 窗口的 75%
      system: 800
      facts: 1200
      history: 1500
      summary: 600
      tool: 2000
      reply: 1200
```

### 接线

| 位置 | 改动 |
|---|---|
| `ChatRuntime.handle` | 入口处 `TurnBudget budget = budgets.forSession(session, properties)`，往下传 |
| `ContextFacts.build` | 加 `TurnBudget` 参数；每张卡片过 `budget.fit("facts", …)`，规则目录**按行砍**（砍整条规则，不砍半句） |
| `ChatRuntime.composeContext` | 摘要过 `fit("summary")`、历史过 `fit("history")` |
| `ChatRuntime.call()` | 工具返回后 `outcome = budget.fitTool(name, outcome)`；保留 `ReconMcpTools` 已有的"还有 N 条，用 offset=" |
| `ChatSummaryService` | `window * 0.55` 改成 `budget.summaryThreshold()`，常量只剩一份 |
| `ChatRuntime` 收尾 | 超 80% 时 `log.info(budget.describe())`；SSE 可加一个 `ChatEvent.budget(...)`（可选） |

### 两个设计约束（评审要看这两条）

1. **`facts` 与 `system` 永不裁剪到 0**。它们被砍光的直接症状不是报错，是模型开始编表名、
   编规则码 —— 那是这个项目最贵的一类故障。
2. **截断不静默**。每一处裁剪都在正文留一句"本节已裁"，同时进 `notes` 与日志。
   静默截断最难查：模型答得通顺，但答的是半张表。

### 验收

- `TurnBudgetTest`：单桶裁剪、`rebalance` 的砍序（超量时先砍 tool、facts 最后）、标记文案、
  与 `ChatSummarizer.estimateTokens` 口径一致。
- `ContextBudgetIT`：造 50 轮会话 + 一张 80 列的 `describe_table`，断言 `composeContext` 总长
  ≤ `total-tokens`，且"规则目录仍在、表名仍在、最近 3 轮原文仍在"。
- 回归：`chat-test-report.md` 覆盖的会话逐句重跑，答案不应变化（除非原本就是被挤掉的那类问题）。

---

## 补丁三：`ChatLoop` —— 一次请求最多三步，且只走已有的那一扇门

### 形态选择：不做真 ReAct

真 ReAct（模型自由选工具 + 自己决定停 + 无限轮）在这里不划算：

- 预算不可控：每轮一次 LLM，P95 延迟随步数线性涨，且没有自然上界；
- 轨迹难回放：`recon_agent_step` 的"每步落库"变成一串无结构文本；
- 闸门要写第二遍：`dispatch` 里那套白名单/确认/幂等如果绕过，ADR-0001 D1 就作废了。

所以做的是 **受限串行**：模型只能说"我还要再做一步 X"，X 必须是 `ChatIntent` 枚举里已有的值，
执行仍然走 `dispatch`。这给的是"一句话做完两件事"，不是"自己决定做什么"。

### 数据结构：不动 `ChatPlan`，新增 `PlanSequence`

```java
/** 一次请求的动作序列：首步就是现在的 ChatPlan，then 最多 1 条。 */
public record PlanSequence(ChatPlan first, List<ChatPlan> then) {
    public static PlanSequence single(ChatPlan plan) { return new PlanSequence(plan, List.of()); }
    public List<ChatPlan> steps() { ... }          // 上限 LOOP_MAX_STEPS
}
```

这样 `ChatPlan` 的构造器、静态工厂、`Evidence` 语义、既有测试一律不动 —— 加字段到 record 上会连带改十几处。

`LlmPlanner` 的 system prompt 加一段："如果你判断要完成这个请求还差一次动作，把它写进 `then`
（最多一条，字段与首步相同）；不确定就留空。" 解析失败或 `intent` 不在枚举里 → 整条丢弃，只跑首步。

`KeywordPlanner` 永远返回 `PlanSequence.single(...)` —— **P0/P1 两档行为零变化**。

### 执行器 `chat/loop/ChatLoop.java`

```java
Result run(SessionScope scope, String message, ChatSlots slots, List<String> tools, Consumer<ChatEvent> sink) {
    PlanSequence seq = planner.planSequence(message, slots, facts, history, contextText);
    List<Reply> done = new ArrayList<>();
    boolean ranAlready = false;

    for (int i = 0; i < seq.steps().size(); i++) {
        ChatPlan step = seq.steps().get(i);
        if (budget.exhausted() || i >= LOOP_MAX_STEPS) { noteLeft(seq, i); break; }
        if (!LEGAL_INTENTS.contains(step.call().intent())) break;            // fail-safe：只跑首步
        if (step.call().intent() == ChatIntent.RUN && ranAlready) continue;  // 一次请求只跑一次对账

        if (needsWriteConfirm(step.call(), slots, message)) {               // 写动作停在原地等确认
            slots = slots.withPending(step.call().intent().name(), argsOf(step))
                         .withPendingArg("loop.at", String.valueOf(i));
            return Result.confirm(receiptFor(step), slots);                  // 回执，不执行
        }

        Reply reply = dispatch(scope, step.call(), slots, message, tools, sink);
        slots = reply.slots();          // 跨步回填槽位（RUN 的 jobId 就是靠这一行传给 LIST_DIFFS 的）
        done.add(reply);
        if (step.call().intent() == ChatIntent.RUN) ranAlready = true;
        sink.accept(ChatEvent.step(i + 1, step.call().intent().name(), budget.describe()));
    }
    return merge(done);   // 合并成一条正文，只 narrate 一次
}
```

### 六条约束（每条都对应一个已知故障）

1. **不给新的工具调用路径**：`dispatch` 与 `call()` 仍是唯一入口，闸门一个都不少。
2. **写动作最多一步，且必须停在确认**：确认后从 `slots.pending("loop.at")` 续跑，
   **不从头重放**（否则 `recon_run` 会跑两遍，差异表的"先删后写"也救不了重复任务）。
3. **一次请求最多一次 RUN**：用户说一句"跑一下"，不能因为续跑变成两次任务。
4. **叙述只做一次**：3 次工具 + 1 次规划 + 1 次叙述 = 5 次往返，可接受；
   每步 narrate 就是 ×3 的 token 和延迟，且用户会看到三句互相打断的话。
5. **默认关**：`recon.chat.loop.enabled: false`。打开前它不存在于任何路径上。
6. **失败即收敛**：任一步抛异常 → 停，把"已完成哪几步、哪步失败、为什么"一次讲清；
   不重试整条链（链式重试会把一个失败变成三个失败）。

### 验收（按行为写，不按函数写）

| 说这句话 | 期望 |
|---|---|
| "看看最近的任务，有失败的解释一下" | 2 步（LIST_JOBS → EXPLAIN_JOB），1 条回复 |
| "跑一下 A 规则，然后看差异" | 2 步，第 2 步用第 1 步返回的 jobId（这是 loop 真正值钱的地方） |
| "把 A 跑一下"（用户没说确认） | 1 步 + 回执，等"确认"；确认后只跑一次 |
| "起草一条规则并试算" | 1 步（起草本身异步，不再串轮询） |
| 模型给了 4 步 | 只跑 3 步，末尾说明"还剩 X 没做" |
| 模型给了不存在的 intent | 只跑首步，logger 记一条 `WARN` |
| 第 2 步工具炸了 | 停下，讲清第 1 步结果 + 第 2 步失败原因 |

延迟：三步链的 P95 大约是单步的 2.5～3 倍。必须用 SSE 每步推一个 `ChatEvent.step`，
否则用户看到的是"卡住了三十秒然后突然出来一大段"。

---

## 落地顺序与工作量

| 顺序 | 补丁 | 工作量 | 行为变化 | 前置 |
|---|---|---|---|---|
| 1 | `ToolCatalog` | 半天 | 无（`/api/tools` 从 12 变 18，是修 bug） | — |
| 2 | `TurnBudget` | 一天 | 无（预算取值等于现状的散装常量之和） | — |
| 3 | `ChatLoop` | 两天 | 有（默认关） | 1、2 |

三个补丁各自一个 commit，各自可回滚。补丁 3 上线时先用配置打开，跑一周 `chat-test-report`
的会话集，确认没有"多跑一次任务"和"叙述重复"两类回归，再考虑默认开。

## 明确不做

- 不引入自由 ReAct（模型自选工具、无限轮、自主停止）：预算不可控、轨迹不可回放、闸门要重写。
- 不给 loop 任何审批能力：审批只能人做，loop 只负责"把该问的问清楚"。
- 不新增中间件（ADR-0001 D6）。
- 不为了 loop 修改 `ToolSpec` 的语义：工具仍是无状态函数，状态在 `ChatSlots` 与会话表里。
