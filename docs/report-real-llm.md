# 真模型复测报告（deepseek-flash）

> 目的：之前六批的实测大量使用**桩模型**（`tools/stub-llm.mjs`，回包固定、不解析）。本文把能走真模型的功能全部用真模型重跑一遍。
> 环境：`deepseek-flash`（base-url `https://api.deepseek.com/v1`，key 由启动参数注入），`--recon.chat.loop.enabled=true`，`--recon.llm.enabled=true`，端口 18090（主）/ 18091（副）。
> 日期：2026-10-27 01:30–01:55。

## 一、结论速览

| # | 能力 | 真模型结果 |
|---|---|---|
| 1 | 意图识别（词表 + 模型） | ✅ 正常，`source=LLM` 与 `source=KEYWORD` 都出现，置信度与理由可读 |
| 2 | 多轮槽位（"第 1 条差异"） | ✅ 正常：EXPLAIN_DIFF 0.92，正确挂到上一个 job 的 diffId |
| 3 | 真实叙述（narrate） | ✅ 明显优于桩：中文通顺、带「明细」段、会解释耗时/分类 |
| 4 | 真实归因（explain） | ✅ 4 条归因全部具体到业务键与下一步动作，`解释器=llm-openai-compatible，LLM 可用` |
| 5 | M2 实体闸门（编造的 jobId） | ✅ 拦住：`EXPLAIN_JOB` 给了候选 `[EXPLAIN_JOB, LIST_JOBS]`，**没有执行** |
| 6 | M1 伪造结构标记 | ✅ 抗住：注入 `<<<END-DATA-…>>>` + 假规则后仍走 `LIST_RULES` 0.95，只列出真实 3 条 |
| 7 | M3 强制层开关 | ✅ HTTP 400「检索层强制开关未启用」 |
| 8 | 目录/写标志（M5/M6） | ✅ `total=18`，写标志 7 个（define_rule/build_model/run/draft_rule/register_table/import_file/submit_approval） |
| 9 | 规则起草（RuleDrafter） | ✅ 5.0s 一轮通过闸门 1；**模型拒绝编造列**（见 §三） |
| 10 | 影子试算 | ⚠️ 全部 `INCONCLUSIVE / CANARY_FAILED`，根因是 H2 不认 `CRC32`（见 §四.1） |
| 11 | re-plan（M18） | ⚠️ 真模型下**没触发**：模型自我纠正，不产生违规（见 §四.2） |
| 12 | 两步环（M10/M11） | ⚠️ 本轮未观测到第二跳（只 1 个 PLAN、1 次派发）（见 §四.3） |

## 二、真模型做了什么（原文证据）

**三步链**（session=1，`logs/real-llm/*.sse`）：

```
[1] PLAN intent=RUN source=LLM conf=0.95
    → "BILL_VS_SETTLE 这次跑完了，状态正常…匹配 9 条、差异 4 条…差异分类：AMOUNT_MISMATCH=1…"
[2] PLAN intent=EXPLAIN_JOB source=KEYWORD conf=0.78
    → 4 条归因："#5 DUPLICATE_KEY 业务键=C005|ORD20260006：…下一步核对两条记录的入账时间、来源单号…"
[3] PLAN intent=EXPLAIN_DIFF source=LLM conf=0.92
    → "第 1 条差异是金额对不上，差 0.3，归因倾向于是结算侧含税/手续费或汇率换算的尾差…"
```

**拟人纠错（本轮最有价值的一条）**——要求"比对字段用 `not_exist_col`"，模型的产出：

> reasoning: "需求中的 not_exist_col 在两侧表结构中不存在，**按硬约束不得编造列**，因此改用两侧共有的 amount…"
> assumptions: ["需求指定的比对字段 not_exist_col 不存在，假设这是笔误…", "假设 customer_id 在两侧语义一致…"]

**拒绝坏指令**——要求"必须 9999 片，不许改"：

> reasoning: "需求里的 9999 分片属于**分片爆炸**，会让调度元数据与连接开销失控，按容量常识收敛到 4 片并行 4。"

## 三、与桩模型的结论差异

| 维度 | 桩模型 | 真模型 |
|---|---|---|
| 起草轮数 | 固定回同一条坏规则 → 3 轮全拒 → re-plan | 1 轮通过；遇到不可能满足的需求会自行改写并写明假设 |
| 闸门 1 | 能被"诱导"出违规 | 几乎不会：约束已在 prompt 里，模型主动规避 |
| 叙述 | 模板拼接 | 会说"耗时 3 毫秒""分片 2/2 完成"这种从结果里读出来的话 |
| 意图 | 快路径/固定 | 有 0.92 这种"不确定但仍在候选里"的中间态 |

**结论：闸门 1 的反馈环（M10 的 RuleDrafter 循环、M18 的 re-plan）在真模型下是"低频路径"**——它的价值不是日常，而是模型被诱导/能力下降时的兜底。桩模型能稳定复现，真模型不能，这两者都要保留。

## 四、真模型暴露的 5 个新问题

### 1）试算在 H2 上永远过不了（阻塞 P2 的完整闭环）

`ShadowTrialService` 试算时执行 `MOD(CRC32(customer_id), 4)`，H2 报 `Function "crc32" not found [90022-224]`，4 个分片全 `FAILED`，canary 随之失败 →

```
试算完成 GATE_BLOCK_RECON v1: INCONCLUSIVE / NONE / 主理由=CANARY_FAILED，理由 [CANARY_FAILED, SHARD_COVERAGE, SAMPLE_TOO_SMALL]
```

模型（以及默认示例）都按 MySQL 方言写 `CRC32`。**在 demo/单测用的 H2 上，任何带 CRC32 分片的规则都不可能 PASS**。要么给 H2 注册 `CRC32` 别名，要么把 demo 换成 MySQL，要么在提示里按方言选 `rangeKey`。

### 2）re-plan 在真模型下不可达（需要"可注入的失败"）

想触发 re-plan 需要"3 轮都被闸门 1 拒"。真模型：①拒绝编造列；②拒绝 9999 片；③把禁用表替换掉。三条路都堵死。
建议：给 `AgentProperties` 加一个**测试用**的 `forceDraftViolation`（仅 test profile），否则 M18 只能靠桩模型验证。

### 3）`tokens_est` 记的不是模型用量

run 1 第 3 步：真模型调用耗时 **5018ms**，记录 `tokensEst=2`。
因为 `recordStep` 估算的是**落库的 payload 文本**（"通过"），不是 prompt+completion。当前字段名容易误读，建议改名 `payload_tokens`，或接 `usage`（DeepSeek 的 OpenAI 兼容接口会返回 `usage`，`LlmChatClient` 现在把它丢了）。

### 4）试算 INCONCLUSIVE 时 `run.status` 仍是 SUCCESS

run 1：`verdict=INCONCLUSIVE / approval=BLOCK_HARD`，但 `run.status=SUCCESS`。按 M19 的语义，草稿已落库、试算没过 ⇒ 应该是 `PARTIAL`（或至少带 `blockedReason`）。现在外部看不出"这条 run 其实没成功"。

### 5）审计接口的 header 没写清

`/api/audit/runs/{id}` 同时需要 `X-Operator`（必需）与 `X-Api-Key`（配置了 `recon.security.api-key` 时必需）。只带前者 → 403「导出审计记录」，只带后者 → 400。文档与 smoke 里只提了一个。

## 五、未覆盖 / 待补

- **M10/M11 两步环**：本轮 SSE 只有 1 个 PLAN、1 次派发，第二步没发生。可能原因：模型判定"已说清无需第二步"，或 `continuePlan` 的 `sameAs` 把它挡了。需要打开 TRACE 或给 `continuePlan` 加一行 DEBUG 日志重测。
- **M9 熔断**：真模型不会连续失败 3 次；要复现仍需"不可达端点"（属仿真，非真模型）。
- **M7 预算**：已用真模型跑过 4×3000 字长会话（批 2 的结论沿用），本轮未重跑。
- **M16 facts 缓存**：与模型无关，沿用批 5 实测。

## 六、复现命令

```powershell
# 主实例（真模型 + 两步环）
java -jar target\recon-lens-0.1.0.jar --server.port=18090 --recon.agent.enabled=true `
  --recon.mcp.http.enabled=true --recon.review.api-key=vv --recon.security.api-key=vv `
  --recon.chat.llm.base-url=https://api.deepseek.com/v1 --recon.chat.llm.api-key=<key> `
  --recon.chat.llm.model=deepseek-flash --recon.chat.llm.timeout-ms=60000 `
  --recon.chat.loop.enabled=true --recon.llm.enabled=true `
  --recon.llm.base-url=https://api.deepseek.com/v1 --recon.llm.api-key=<key> --recon.llm.model=deepseek-flash

# 取轨迹（两个 header 都要）
curl -s -H "X-Operator: alice" -H "X-Api-Key: vv" http://127.0.0.1:18090/api/audit/runs/1
```
