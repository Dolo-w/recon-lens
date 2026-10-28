# 批 2 实测验证报告：预算账本、token 口径、熔断下沉

对象：`recon-lens`，批 2（M7 / M8 / M9）。
时间：2026-10-26 22:20–22:29（Asia/Shanghai）。
运行环境：JDK 21（`D:\App\ideaIC-2025.3\jbr`）、`target/recon-lens-0.1.0.jar`、H2 内存库、18081 端口、
桩模型 `tools/stub-llm.mjs`（18999）。

> 本批的性质和批 0/1 不同：M1–M6 改的是"看得见的行为"，批 2 改的是**记账口径**。
> 所以证据分两层：**真实请求的 prompt 尺寸**（证明账本真的在管），
> **真实故障下的降级路径**（证明熔断真的接上了，而不是只换了个类名）。

---

## 一、结论速览

| 项 | 内容 | 状态 | 最硬的一条证据 |
|---|---|---|---|
| M7 | 会话 prompt 改为按桶记账，桶是唯一的尺寸权威 | ✅ | 4 轮长消息后 `history` 段 = **1513 token**（桶 1500），带 `…[预算截断]`；整条 prompt **2621 token** < 6100 |
| M8 | 字符口径统一为 token 口径 | ✅ | `RuleDrafter`/`LlmDiffExplainer`/`AgentOrchestrator` 全部改走 `TurnBudget`/`Tokens`；旧字符常量只留兼容 |
| M9 | 熔断从"只有路由层"下沉到三处调用点 | ✅ | 拔掉桩模型后：路由层 `熔断中，还剩 255 秒`；归因层日志 `差异归因 连续失败 3 次，熔断 300 秒`，第 4 次请求**连连接都不发起** |

测试：`mvnw -o test` → **781 tests, 0 failures**（批 1 是 775，新增 6 条：`TurnBudgetTest`）。

---

## 二、M7：会话预算账本

### 改了什么

- 新增 `chat/TurnBudget.java`：五个桶 `SYSTEM 800 / FACTS 1200 / HISTORY 1500 / SUMMARY 600 / TOOL 2000`，
  合计 `TOTAL 6100`；`CUT_ORDER = tool → summary → history → facts`（先砍最不值得留的）；
  `assemble(Map)` 单段超自己那桶就截自己，总量超了按顺序砍半。
- `LlmPlanner` 的 facts / history / 摘要三处一律先过 `TurnBudget.fit`；`fitContext` 的 1200 字符常量删除。
- `ChatSummaryService` 的触发预算 `min(window × 0.55, TurnBudget.TOTAL)`：避免 window 配大以后
  "触发压缩的阈值"和"真正发出去的预算"各说各话。
- 顺手修掉一个只有实测才会暴露的口径矛盾：历史每轮原本硬编 `inline(turn, 120)`，
  6 轮最多 720 字 ≈ 720 token，**而桶给了 1500 —— 桶永远够不着**。
  现在每轮额度 = `HISTORY / 轮数`，两个数字由同一个来源算出。

### 实测

真实请求（`POST /api/chat`，同一个 session 连发 4 条 3060 字的长消息），从桩模型日志里取出实际发出的 system prompt：

| 段 | token（估算口径：中文 1 字 = 1 token，英文 4 字符 = 1） | 桶 |
|---|---|---|
| `facts`（系统事实） | 220 | 1200 |
| `history`（最近用户原话） | **1513**（含 `…[预算截断]`） | 1500 |
| 整条 system prompt | **2621** | 6100 |

对照：改之前同一段历史是每轮 120 字封顶（≈720 token），桶形同虚设；
现在尺寸由桶决定，且超限一定带标记（`logs/after-m7-prompt-long.txt`）。

结论板（claims）不被裁这一点由单测兜底（`LlmPlannerTest`：历史被压时 `HEADER` 与结论原文仍在），
因为 live 侧造一条"长历史 + 有结论板"的会话要连走 RUN→确认→再闲聊，代价大于收益。

---

## 三、M8：token 口径

### 改了什么

- 新增 `llm/Tokens.java`（`estimate`：CJK 1 token/字，其余 4 字符 1 token），
  `ChatSummarizer.estimateTokens` 改为纯委托 —— 全仓只剩一个估算实现。
- `PromptGuard` 新增 `truncateTokens` / `dataBlockTokens`；旧字符接口保留（老调用方拍的是字符数，静默改语义会让 prompt 一夜之间变三倍）。
- 迁移调用点：`RuleDrafter` 四段（1200/1200/1200/600 token）、`LlmDiffExplainer` 的 `diff-list`（1200）、
  `AgentOrchestrator.MAX_STEP_PAYLOAD`（6000 **字符** → 2000 **token**）。

### 实测

编译 + 781 条测试全绿；`AgentOrchestrator` 的 `truncated` 判定改为按 token 比较
（`Tokens.estimate(planJson) > MAX_STEP_PAYLOAD`），轨迹里不再出现"6000 字符其实约 6000 token"的错标。

### 残留

- `PromptGuard.DEFAULT_BLOCK_CHARS / DEFAULT_VALUE_CHARS` 与 `RuleDrafter` 的 800/3000 字符等旧口径还在，
  只是不再是我改动路径上的默认值。清理它们要逐个确认调用方的语义，留给批 5（观测）一起做。

---

## 四、M9：熔断下沉

### 改了什么

- 新增 `llm/LlmCircuitBreaker`：`allow() / onFailure() / onSuccess() / remainingMs() / state()`，
  默认 3 次失败、冷却 5 分钟；**每个调用点各记一笔**（路由、归因、起草），同类同语义。
  不做成"全区共享一个计数器"：归因失败不代表路由坏了，一个组件关闸就停掉别的组件是过度反应。
- `CompositePlanner` 改用该类（`FAILURES_BEFORE_OPEN` / `COOL_DOWN_MS` 保留，测试仍能钉住阈值）。
- `LlmDiffExplainer.explainBatch` 加闸：熔断期间直接返回空，由 `ExplainService` 落模板（"绝不留空"的承诺不变）。
- `RuleDrafter.draft` 加闸：熔断期间返回 `RuleDraft.rejected("…熔断 N 秒…")`，转人工而不是让 agent 白等。

### 实测（真实故障注入：直接杀掉桩模型进程）

**路由层**：连发 4 条对话，每条都只剩词表路径；`GET /api/chat/planner`：

```json
{"planner":"词表路由（确定性，0 成本） → 模型路由：OpenAI 兼容：http://127.0.0.1:18999 / stub（熔断中，还剩 255 秒）","modelEnabled":true}
```

**归因层**：新建任务 #4（4 条差异）后逐条 `POST /api/diffs/{id}/explain`。
应用日志（`LlmCircuitBreaker` 自己打的）：

```
22:27:23.943 WARN  OpenAiCompatibleChatClient - 调用大模型失败…: Connection refused
22:27:23.965 WARN  OpenAiCompatibleChatClient - 调用大模型失败…: Connection refused
22:27:23.965 WARN  LlmCircuitBreaker - 差异归因 连续失败 3 次，熔断 300 秒，期间走降级路径
```

第 3 次之后闸门关闭，第 4 条差异的请求**没有产生第 4 条连接失败日志** —— 说明它压根没去连模型，
而是直接走模板；与此同时 4 条差异的 `explanation` 都非空（模板兜底生效）。

**起草层**：同一条代码路径（`RuleDrafter` 注入同一个类），但 live 侧没有低成本触发方式：
起草只在 MCP 进程里跑，而 MCP 每次 `tools/mcp-client.mjs` 都起一个新 JVM，计数器不跨进程 ——
3 次失败开闸这件事在那里无从复现。这一条由 `TurnBudgetTest.breakerOpensAndRecovers` 钉语义。

---

## 五、本批发现（未修，记账）

1. **`explainer` 字段会说谎**：`ExplainService.llmName()` 只看"配置了没有"，不看"这次到底用的哪层"，
   于是模板兜底时 API 仍回 `"explainer":"llm-openai-compatible"`。启动日志里"大模型 0 条（已启用）"
   和这个字段是同一个毛病：**降级发生了，但对外说没发生**。留到批 5 一起修（观测类）。
2. `Attribution` 内部其实已经有 `Layer`（KNOWLEDGE/LLM/TEMPLATE），只是没往外暴露 —— 修起来很小。
3. 批 1 遗留的 `mcp-smoke.mjs` 两条断言与"MCP 不允许直通生效"的边界冲突，仍未处理（脚本问题，不是产品问题）。

---

## 六、复现

```powershell
# 1) 起桩模型
node tools/stub-llm.mjs                     # 18999

# 2) 起服务（批 2 需要归因也走模型，故 recon.llm.enabled=true）
$env:JAVA_HOME="D:\App\ideaIC-2025.3\jbr"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
java -jar target\recon-lens-0.1.0.jar --server.port=18081 --recon.agent.enabled=true `
  --recon.chat.llm.base-url=http://127.0.0.1:18999 --recon.chat.llm.api-key=stub-key `
  --recon.chat.llm.model=stub --recon.llm.enabled=true --recon.llm.base-url=http://127.0.0.1:18999 `
  --recon.llm.api-key=stub-key --recon.llm.model=stub

# 3) M7：连发长消息后看 prompt 分段
#    （脚本见本报告"实测"一节的思路：解析 logs/stub-llm.jsonl 最后一条的 messages[0].content）

# 4) M9：杀掉桩模型，再跑上面的对话与 /api/diffs/{id}/explain
Stop-Process -Id (Get-NetTCPConnection -LocalPort 18999).OwningProcess
```

证据落盘：`logs/after-m7-prompt-long.txt`（2621 token 的完整 prompt）、`logs/after-m7-prompt.txt`。
