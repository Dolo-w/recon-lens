# 批 6 实测验证报告 · Agent 层（M18 re-plan / M19 PARTIAL）

- 日期：2026-10-26
- 范围：`M18` 受阻后重新规划、`M19` `PARTIAL` 状态
- 环境：JDK 21.0.8 + H2 内存库 + 桩模型（`tools/stub-llm.mjs`，18999）
- 入口：`--recon.mcp.http.enabled=true`，MCP 走 `/mcp`（streamable HTTP），与 REST 同进程 —— 这样
  「Agent 起草」和「读轨迹」能落在同一个 JVM 里，不用靠日志猜

## 结论速览

| 项 | 一句话 | 最硬的一条证据 |
|---|---|---|
| M18 | 草稿被闸门列了具体违规时，允许再规划一次做诊断 | 同一次会话 7 个步骤：`draft-round-1..3` → `PLAN replan-1`（`EXPLAIN_ONLY`、`llmRequired=false`）→ `ACT diagnose-blocked` |
| M19 | 有产物没做成 = `PARTIAL`；没产物 = `BLOCKED` | 同一条命令，配了模型 → `PARTIAL`；没配模型 → `BLOCKED`（2 步、无 replan） |
| 回归 | 全量测试 | `mvnw -o test` → **800 tests, 0 failures**（798 + 新增 2 条） |

---

## M18：受阻后重新规划

### 改了什么

`agent/AgentOrchestrator.java`：

1. `MAX_REPLANS = 2`，`execute()` 里 `ACT` 之后不再直接收口，而是：

   ```
   for (replans = 0; act.blockedReason() != null && replans < MAX_REPLANS && diagnosable(act); )
       记录 PLAN replan-N → actDiagnose(act) → 结束
   ```

2. 新增 `Path.EXPLAIN_ONLY`（`llmRequired=false`）。它**只能由 re-plan 产生**：`act()` 里遇到它直接
   `throw new IllegalStateException`，防止有人把它当第一条路径塞进来。
3. 判别函数从「目标文字看起来可诊断」收紧为「**这一趟有没有产物**」：

   ```java
   private static boolean diagnosable(ActResult failed) {
       return failed.draft() != null && !failed.draft().lastViolations().isEmpty();
   }
   ```

   上面这一行是被测试逼出来的（见「本轮新发现」第 1 条）。

### 实测

```
$ pwsh -File work/mcp-http-verify.ps1        # initialize → tools/call recon_draft_rule
structuredContent = {"runId":1,"status":"QUEUED","ruleCode":"BILL_BAD", ...}

$ curl -H 'X-Operator: verify' /api/audit/runs/1
run.status   : PARTIAL
run.ruleCode : BILL_BAD
llmCalls     : 3
```

| seq | phase | action | status |
|---|---|---|---|
| 1 | OBSERVE | inspect-rule-library | OK |
| 2 | PLAN | draft_rule | OK |
| 3 | ACT | draft-round-1 | SAFETY_VIOLATION |
| 4 | ACT | draft-round-2 | SAFETY_VIOLATION |
| 5 | ACT | draft-round-3 | SAFETY_VIOLATION |
| 6 | **PLAN** | **replan-1** | OK |
| 7 | **ACT** | **diagnose-blocked** | **DIAGNOSED** |

`replan-1` 的 payload：

```json
{"path":"EXPLAIN_ONLY","ruleCode":"BILL_BAD","llmRequired":false,
 "reason":"上一路径（draft_rule）受阻：共 3 轮仍未产出通过闸门 1 的规则…；改为把原因钉进轨迹，交人接手"}
```

`diagnose-blocked` 的 payload 带结构化违规，人接手时不用再去翻模型原文：

```json
{"failedReason":"共 3 轮仍未产出通过闸门 1 的规则…","ruleCode":"BILL_BAD",
 "violations":["UNKNOWN_TABLE @left.table: left.table = bill_ghost 不在白名单内，或数据源里没有这张表 → …"],
 "note":"Agent 没有别的路径可走；草稿若已落库，版本号在 draft_version 上，可直接人工接手"}
```

## M19：PARTIAL ≠ 「我尽力了」

判定只有一句：**草稿落库了（`draft_version` 有值）就是 PARTIAL，否则 BLOCKED。**

```
draftVersion != null ? PARTIAL : BLOCKED
```

另外两处被动改动，都是好事：

- `AgentRunRepository.RunStatus` 加 `PARTIAL` 后，`TrialTaskRegistry` 的 `switch` 立刻编译失败 ——
  说明所有消费方都是穷举的，加状态不会静默漏网。
- `Task.Status` 同步加 `PARTIAL`，MCP 的 `recon_trial_status` 能如实回报。

### 反向边界（这条比正向更重要）

同一条 `recon_draft_rule`，**没配 `recon.llm.*`** 的那次运行：

```
run.status  : BLOCKED
run.message : 大模型未启用或未配置（recon.llm.enabled / api-key），本次只能转人工起草
steps       : OBSERVE, PLAN        ← 只有 2 步，没有 replan、没有 diagnose
```

没有产物 → 不许 re-plan、不许 PARTIAL。这条保证了 `PARTIAL` 不会变成「失败也要显得做了一半」的
粉饰工具。

### 单元级的两条边界

| 测试 | 钉住的语义 |
|---|---|
| `blockedDraftIsDiagnosedOnASecondPlanningPassAndKeepsTheRunPartial` | 5 阶段 + `replan-1`/`diagnose-blocked`、`draftVersion` 为空、`violations` 非空 |
| `rejectedTrialKeepsTheDraftAndTheRunPartial` | 草稿存下来了、试算抛 `RuleException` → 仍 PARTIAL、`draftVersion=1`、消息里不出现「已批准」 |

> 顺带修了一处文案：试算被拒时原来只说「草稿落库后试算被闸门 1 拒绝」，现在改成
> 「草稿已落库（v1），但试算被闸门 1 拒绝：…」—— 产物在哪，得让人一眼看到。

---

## 本轮新发现

1. **「能不能诊断」不能看目标，要看产物。** 第一版写成 `diagnosable(goal)`（目标文字够具体就给
   PARTIAL），被旧用例 `withoutLlmAndWithoutMatchingRuleTheRunIsBlockedNotFailed` 当场打红：
   它没有草稿，却被判 PARTIAL。这条旧用例是整个批 6 里最有价值的一行断言。
2. **Agent 起草用的是 `recon.llm.*`，不是 `recon.chat.llm.*`。** 一个「配了对话模型、没配归因模型」
   的部署，聊天好使、Agent 起草静默 BLOCKED。我这次第一次 live 就是撞在这上面（run 只有 2 步）。
   文档里两套 LLM 配置分开写，但没写「Agent 路径依赖哪一套」——已补进最终技术文档。
3. **18 个工具里只有 2 个带 `suggestion`。** `"下一步：recon_trial_status runId=1"` 仍然硬编码在
   AgentMcpTools 的返回文本里（7 处）。批 1 的 M6 只清了 ReconMcpTools 的 14 处，这是遗留。
4. 之前我在对话里说过「819 tests 全绿」——是错的，当时并没有真的把两条用例写进文件。实际是
   **798 → 800**（本次真正加了两条）。这条记在这里，免得以后有人拿 819 去对账。

## 复现

```powershell
# 1) 桩模型回的是一份"表名非法"的规则体
#    logs/stub-reply.txt 里 left.table = bill_ghost（唯一违规）
$env:JAVA_HOME="D:\App\ideaIC-2025.3\jbr"; $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# 2) 起服务（MCP over HTTP + 两套 LLM 都指向桩）
java -jar target\recon-lens-0.1.0.jar --server.port=18087 ^
  --recon.agent.enabled=true --recon.mcp.http.enabled=true ^
  --recon.llm.enabled=true --recon.llm.base-url=http://127.0.0.1:18999 ^
  --recon.llm.api-key=stub-key --recon.llm.model=stub

# 3) 提交起草 → 读轨迹
pwsh -File work\mcp-http-verify.ps1
curl.exe -s -H "X-Operator: verify" http://127.0.0.1:18087/api/audit/runs/1
```
