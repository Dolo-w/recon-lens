# P2 决策式改造（工具调用取代意图枚举）执行单

> 起因：P2 现在仍是「词表/模型判一个 `ChatIntent` 枚举 → dispatch 固定分支」。用户口径（m04866）：
> P1 是函数外套壳，P2 必须像 DSH 一样——给模型事实+记忆+工具，让它自己决定下一步；
> 不调工具就继续对话，直到意图清楚再调。
> 预算口径（m04870）：必须**先理解清楚意图再谈预算**，否则本末倒置。

## 目标形态

```
事实(ContextFacts) + 记忆(claims/summary/slots/history) + 工具清单(ToolCatalog.specs())
        ↓
模型输出三选一：① 调工具(name+args) ② 说话/反问 ③ 结束
        ↓
① → 同一个 ToolRegistry 执行 → 结果回灌 → 继续
```

## 步骤（按序，每步可独立编译+跑绿）

### 1. `Plan.tool` + `ToolCallPlanner`

- `chat/plan/ChatPlan.java`：加静态工厂 `tool(String name, Map<String,String> args, double confidence)`；
  内部用一个 `Call` 包装（`intent` 取 `ChatIntent.HELP` 仅作占位，真实工具名放 `evidence` 或新增 `toolName` 分量）。
  建议直接在 record 里加 `String toolName` 分量（`null` 表示走老路），最小侵入。
- 新建 `chat/plan/ToolCallPlanner.java`：输入 facts/claims/summary/history/slots + `List<ToolSpec>`；
  提示词要求只输出 JSON：`{"action":"tool","name":"recon_list_diffs","args":{...}}` /
  `{"action":"reply","text":"..."}` / `{"action":"done"}`。
  解析失败→返回空（交回老路径），不抛。
- 测试：`ToolCallPlannerTest`（合法工具调用 / 未知工具拒收 / 非 JSON 退回空 / reply 形态）。
- 验收：`mvnw test -Dtest=ToolCallPlannerTest` 绿。

### 2. 预算分档 + 会话累计

- `chat/TurnBudget.java`：新增 `enum Phase { UNDERSTANDING, ACTING }`；
  `UNDERSTANDING` 档：facts 2000 / history 4000 / summary 800 / 合计 12000，且 history 超限时**按整轮丢弃**（不砍半）；
  `ACTING` 档保持现有 6100，tool 2000。
- 新增会话级账本：`ChatSession` 上加 `tokensUsed` 或内存 `Map<Long,Integer>`；接近 `totalCap` 时才收紧。
- `LlmPlanner` / `ToolCallPlanner` 按 planner 给的阶段选档（理解阶段 = 还没产出 tool 调用）。
- 测试：`TurnBudgetTest` 加 UNDERSTANDING 两例（不砍半、整轮丢弃）。
- 验收：长会话实测不出现「用户原话被 `…[预算截断]` 砍掉」。

### 3. dispatch 按工具名执行

- `ChatRuntime.dispatch`：`plan.toolName()!=null` 时走 `registry.find(name)` → `spec.invoke(args)`，
  结果交给现有 `narrate`；`ChatIntent` 继续保留给 P1/遥测。
- 写动作仍走 `needsWriteConfirm` 回执两步。
- 验收：一句话让模型选 `recon_list_diffs`（不给 intent），轨迹里出现该工具且回执正常。

### 4. 词表降级 + 修 narrate 改写守卫

- `CompositePlanner`：模型可用时不再要求「词表先命中」；词表仅 `fastPath`（≤12 字、高确定、非写）与熔断兜底。
- `ChatRuntime.narrate`：`IntentReview.question(...)` 产生的回复**跳过 narrate**（上一轮实测：守卫话术被模型改写）。
- 验收：反问句回复与 `IntentReview` 返回的字符串逐字一致。

## 实测验收（不接受单元测试代替）

1. 起 18097（真模型 `deepseek-flash`，文件库 `work/p2-demo`，两把 key 都是 `vv`）。
2. 三句话覆盖三种输出：`看下差异` → 工具；`这几张表什么关系` → 反问；`对完了` → 结束/总结。
3. 预算：连发 8 轮长消息，断言历史里没有截断标记吃掉用户原话；打印每轮 `tokensUsed`。
4. 负面：把 `recon_run` 交给模型直接调 → 必须停在回执，不执行。
5. 产出 `docs/report-p2-toolcall.md`（对照表：改前/改后 + 四组证据）。

## 文档同步清单

- `docs/tech-by-stage.md`：P2 段重写（入口判→决策式循环），配新时序图。
- `docs/deep-dive-p2.md`：§3 意图层、§5 循环、§7 预算三处改写。
- `docs/metrics-loop.md`：指标口径补「工具选择率」在决策式下的量法变化。
- `docs/plan-roadmap.md`：新增「八、P2 决策式改造」并登记本次四条。
- `CHANGELOG.md`：一条「P2 改为模型直选工具」。
- `docs/open-questions.md`：把「IntentReview 只做反问」一行的状态更新。
