# 五条修复的实测报告

> 日期：2026-10-27　提交：未提交（工作区）
> 起点：`docs/report-real-llm.md` 里"看起来对、实际不对"的五条
> 用户要求（m03118）："这几条都改一下，改完再测，功能不要丢"

## 结论速览

| # | 问题 | 修法 | 实测证据（真服务） | 状态 |
|---|---|---|---|---|
| 1 | H2 没有 `CRC32()`，生成的 `MOD(CRC32(...))` 分片规则试算 4/4 分片 FAILED | H2 上注册一个 `CRC32` 别名（`engine/H2Crc32` + `config/H2Compatibility`） | 同一类规则：任务 #2 → `status=SUCCESS`，4 个分片全 SUCCESS（1/1、2/3、4/3、3/3 行），差异汇总 `AMOUNT_MISMATCH=2 / DUPLICATE_KEY=2 / MISSING_LEFT=1 / MISSING_RIGHT=1`；日志 `INFO H2Compatibility - 已为 H2 注册兼容函数 CRC32()` | ✅ |
| 2 | `tokens_est` 记的是 payload 字符数（5018ms 的一轮记成 `2`） | `LlmChatClient.lastTokens()` 旁路 + `OpenAiCompatibleChatClient` 从 `usage.total_tokens` 读；`AgentBudget.spendLlm(prompt, reply, actual)` 优先真实值 | 真模型一轮：`draft-round-1 tokensEst=3158`、`round-2 tokensEst=3141`（与请求体量同量级，不再是 2） | ✅ |
| 3 | 试算 `INCONCLUSIVE` 时 `run.status` 仍是 `SUCCESS` | `AgentOrchestrator` 新增 `draftedButNotPassed` 分支：草稿落了库但 `TrialState.Trialed.verdict != PASS` → `PARTIAL` + 原因 | `run.status=PARTIAL`，`errorMsg="草稿已落库（v1），但试算没通过：INCONCLUSIVE / SAMPLE_PARTIAL / 主理由=SAMPLE_TOO_SMALL；产物留在规则库里，等人工判断 —— 这不等于这条规则可用"` | ✅ |
| 4 | re-plan 在真模型下不可达（三条触发路都被模型自我纠正挡掉） | `recon.agent.safety.force-draft-violation=true` 注入一条 `TEST_FORCED` 违规（默认 false，只用于验证链路） | 7 步：`OBSERVE → PLAN → draft-round-1..3(SAFETY_VIOLATION TEST_FORCED) → PLAN replan-1 → ACT diagnose-blocked(DIAGNOSED)`，`run.status=PARTIAL`，`replan-1` payload `path=EXPLAIN_ONLY` | ✅ |
| 5 | 审计接口要两个 header 没写进文档；两步环为何不走第二跳无日志 | `docs/mcp.md` 新增"从脚本调审计接口"小节；`ChatRuntime.followUp` 三个返回点各一条日志（空/被拒/接受） | 文档已落盘；日志分支见 `ChatRuntime` | ✅ |

## 逐条

### 1. CRC32：用别名，不改提示词

**为什么是别名而不是让提示词绕开**：`RuleDrafter` 的提示词第 81–83 行写死"分片写法只用这两种（都是 MySQL 方言）：`MOD(CRC32(<同一表达式>), {shards}) = {index}` / `rangeKey`"。改提示词等于为了演示库（H2）牺牲生产写法——生产大表要的正是"数字分片谓词 + 普通索引"。

**做法**：
- `engine/H2Crc32.crc32(String)`：UTF-8 + `java.util.zip.CRC32`，返回**无符号** 32 位（H2 里负数取模会和 MySQL 的 `UNSIGNED` 语义不一致）。
- `config/H2Compatibility`（`InitializingBean`）：检测到产品名含 H2 才执行 `CREATE ALIAS IF NOT EXISTS CRC32 FOR "io.github.dolow.reconlens.engine.H2Crc32.crc32"`，成功 INFO、失败只 WARN（不阻塞启动）；非 H2 直接跳过。

**实测**（18094，桩模型起草，试算跑真库）：

修前同一类规则：

```
试算完成 CRC32_PROBE_RECON v1: INCONCLUSIVE / ... / 主理由=CANARY_FAILED
理由 [CANARY_FAILED, SHARD_COVERAGE, SAMPLE_TOO_SMALL]
分片 4 个全 FAILED：Function "crc32" not found [90022-224]
```

修后：

```
任务 #2：status=SUCCESS  shardCount=4  shardDone=4  left=10  right=10  matched=9  diff=6
分片：0 SUCCESS 1/1；1 SUCCESS 2/3；2 SUCCESS 4/3；3 SUCCESS 3/3（attempts 全 1）
```

**注意 verdict 仍是 `INCONCLUSIVE`**：这次理由只有 `SAMPLE_TOO_SMALL / SAMPLE_PARTIAL`，**不再是 `CANARY_FAILED`**——说明分片真的跑通了，只是演示数据每侧 10 行，达不到判定样本量。这两件事必须分开看，否则会把"函数不存在"误判成"规则质量差"。

### 2. 真实 token

**做法**：`LlmChatClient` 接口加 `default int lastTokens() { return 0; }`（不放进返回值：否则所有调用点都要改签名，而绝大多数调用点并不关心）；`OpenAiCompatibleChatClient` 把 `usage.total_tokens`（缺则 `prompt+completion`）存进 `ThreadLocal`，调用方在 `complete()` 之后立刻取。`AgentBudget.spendLlm(prompt, reply, actualTokens)`：`actualTokens > 0` 用实测，否则保留"字符数估算"这一兜底。

**实测**（18092，deepseek-flash）：`draft-round-1 tokensEst=3158`、`draft-round-2 tokensEst=3141`；对照修前同一场景 `tokensEst=2`。

### 3. 状态语义

`AgentOrchestrator.execute()` 原来在 `act()` 成功后无条件 `RunStatus.SUCCESS`。新增分支（在 diagnosis/blocked 之后）：

```java
boolean draftedButNotPassed = act.trialState() instanceof TrialState.Trialed trialed
        && trialed.verdict() != null && trialed.verdict() != TrialVerdict.PASS;
if (draftedButNotPassed) {
    status = RunStatus.PARTIAL;
    message = "草稿已落库（v" + act.draftVersion() + "），但试算没通过：" + describeTrial(trialed)
            + "；产物留在规则库里，等人工判断 —— 这不等于这条规则可用";
}
```

**为什么是 PARTIAL 不是 FAILED**：草稿 v1 真的在库里、试算报告真的落盘了，人对它有可操作的东西；FAILED 意味着"什么都没留下"。

### 4. re-plan 可达

真模型下三条触发路都被它自己挡掉（非法列 → 它改写合法列；9999 分片 → 它自己收敛到 4；省略 missing 段 → 它补上）。所以加一个**只给验证用**的开关：`recon.agent.safety.force-draft-violation=true` 时，`RuleSafetyValidator` 在 `validate()` 开头追加一条 `TEST_FORCED` 违规并 WARN。默认 false，生产无影响。

**实测**（18093）：

```
1 OBSERVE  inspect-rule-library  OK                37 tok
2 PLAN     draft_rule            OK                52 tok
3 ACT      draft-round-1         SAFETY_VIOLATION  2594 tok
4 ACT      draft-round-2         SAFETY_VIOLATION  2685 tok
5 ACT      draft-round-3         SAFETY_VIOLATION  2685 tok
6 PLAN     replan-1              OK                140 tok
7 ACT      diagnose-blocked      DIAGNOSED         210 tok
run.status=PARTIAL
replan-1 payload: {"path":"EXPLAIN_ONLY","ruleCode":"FORCE_PROBE_RECON","reason":"…共 3 轮仍未产出通过闸门 1 的规则，转人工起草…","llmRequired":false}
```

### 5. 文档与日志

- `docs/mcp.md` 末尾新增「附：从脚本调审计接口」：`X-Operator` 必需（缺则 400）、配了 `recon.security.api-key` 时 `X-Api-Key` 必需（缺则 403）、以及 `costMs / tokensEst` 的口径。
- `ChatRuntime.followUp` 在三个返回点打日志：`loop.notStarted`（模型没给第二步）/ `loop.rejected`（意图/参数没核住，带原因）/ `loop.accepted`（执行第二跳）。

## 实测中又发现并顺手修的一条

**每轮的 `costMs` 只有第一轮准**。`draft()` 一次调用内部跑完所有轮，而 `AgentOrchestrator` 用同一个 `t` 变量轮后重置——第二轮起计时器是在模型调用**之后**才归零的，于是记成 0。

修法：`RuleDraft.Round` 加第 6 分量 `costMs`，由 `RuleDrafter` 自己在每轮开头 `System.nanoTime()` 计时；`AgentOrchestrator.recordStep` 增加 `costMsOverride`，轮次记录优先用起草器报的值。

复验（同一个真模型实例，两轮都真正耗时）：

```
3 ACT draft-round-1 INVALID_RULE costMs=7973 tokensEst=3158
4 ACT draft-round-2 PASS         costMs=8097 tokensEst=3141
```

**第一轮为什么是 `INVALID_RULE`**：模型第一次给出的规则体没有显式声明 `missing` / `duplicate` 段（解析器要求显式声明单边缺失用哪个 `diffType`），第二轮补上了。这不是缺陷，是闸门 1 与"违规回灌"正常工作——但提示词可以更明确（已记入 `docs/open-questions.md` 候选）。

## 功能不丢的核对

| 能力 | 修前 | 修后 | 结论 |
|---|---|---|---|
| 闸门 1 拦截 | 违规一律拒 | 同上；`TEST_FORCED` 默认不注入 | 不变 |
| 状态机 | `DONE/BLOCKED/FAILED/PARTIAL` | 同上；新增"草稿已落库但试算未过 → PARTIAL" | 更细，不覆盖旧语义 |
| 预算 | 3 轮 / 40k token / 120s | 同上；token 现在更准（会更早触发上限，这是对的） | 更严不更松 |
| MCP 工具 | 18 个 | 18 个，名字/出入参不变 | 不变 |
| 试算/审批 | 影子试算 + 审批生效 | 不变 | 不变 |
| 测试 | 814 green | **814 green / 0 failures**（含新增 `LlmUsageParsingTest` 3 例） | 不变 |

## 复现

```powershell
# 桩模型（起草用，试算跑真库）
Start-Process node "tools/stub-llm.mjs"
# 桩实例
java -jar target\recon-lens-0.1.0.jar --server.port=18094 --recon.agent.enabled=true `
  --recon.mcp.http.enabled=true --recon.review.api-key=vv --recon.security.api-key=vv `
  --recon.llm.enabled=true --recon.llm.base-url=http://127.0.0.1:18999 --recon.llm.api-key=stub --recon.llm.model=stub
# 真模型实例（第 2、4 条的对照）
java -jar target\recon-lens-0.1.0.jar --server.port=18092 ... --recon.chat.llm.base-url=https://api.deepseek.com/v1 --recon.chat.llm.model=deepseek-flash
# 强制违规实例（第 4 条）
java -jar target\recon-lens-0.1.0.jar --server.port=18093 ... --recon.agent.safety.force-draft-violation=true
# 驱动一次起草（走 MCP streamable HTTP）
pwsh -NoProfile -File work/verify-5fix.ps1 -Port 18094 -Description '按客户号逐笔比对账单和结算金额' -RuleCode CRC32_PROBE_RECON
# 看轨迹（两个 header 都要）
$h=@{'X-Operator'='verify';'X-Api-Key'='vv'}; (Invoke-WebRequest http://127.0.0.1:18094/api/audit/runs/2 -Headers $h).Content
```
