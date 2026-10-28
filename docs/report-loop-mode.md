# 自由循环（`recon.chat.mode=loop`）试验报告

> 起因（m01596 口径）：**先不编排，也不约束模型必须落到我们的工具上**，把用户消息、记忆、
> recon 项目资料直接交给模型，让它自己判断下一步；先看效果，再逐步优化。
> 落地开关：`recon.chat.mode=legacy|loop`（默认 `legacy`，随时可回退）。

## 一、改了什么

| 位置 | 改动 |
| --- | --- |
| `chat/ChatMode.java` | 新增 `LEGACY` / `LOOP`；`loop`、`free`、`agent` 都映射到 LOOP |
| `chat/ChatHandbook.java` + `resources/chat/handbook.md` | 项目手册常驻上下文（约 1.3k token）：系统有什么、怎么做事、YAML 骨架、口语→列映射、三条硬约束 |
| `chat/plan/ChatPlanner.java` | 新增 `continueLoop(...)`（默认空） |
| `chat/plan/LlmPlanner.java` | 新增 `loopPrompt(...)` 与 `continueLoop(...)`：手册 + 工具清单 + 事实 + 摘要 + 对话历史 + 本轮 transcript，输出仍是三选一 JSON |
| `chat/ChatRuntime.java` | 新增 `loopTurn(...)`：做一步 → 真实结果进 transcript → 再问模型；写动作一律停在回执；跳数用完刹车；循环里跳过词表改写与起草收集器 |
| `chat/ChatConfiguration.java` | `recon.chat.mode=loop` 且模型已配置时，规划器直接是 `LlmPlanner`（不套 `CompositePlanner`） |

**没有去掉的三样东西**（它们不是编排，是底线）：

1. **循环本身**——否则"让模型判断"退化成答一句执行不了的话。
2. **写动作回执**——安全不变量。模型可以决定"该做"，但"现在做"要用户点头。
3. **预算与刹车**——多跳 × 每跳都是一次模型调用；`recon.chat.loop.max-hops`（默认 5，实测用 6）。

## 二、和 legacy 的同一段对话

同一份八句剧本，两个实例并行（同一台机、同一模型 `deepseek-flash`、各自的库）：

| 用户说 | legacy（18097） | loop（18098） |
| --- | --- | --- |
| 起草个规则：按订单、资源对账 | 收集器：「左侧用哪张表？」 | 模型自己 `describe_table` 两张表，然后问「资源」对应 `customer_id` 还是 `status` |
| bill_left | 收集器：「资源」在 bill_left 的列里没找到… | 模型：「bill_left 只是左表，这我记下了。但『资源』还没答…」 |
| 资源就用状态吧 | 「这句我没接上。我还在等：右侧表」 | `recon_draft_rule` → 回执 |
| 看一下最近一次任务的差异 | 正常（只读工具让路） | 正常：4 条差异，带金额/业务键 |
| 这几张表什么关系 | **被收集器劫持**：「这句我没接上。我还在等：右侧表」 | 模型解释两表关系与三条规则 |
| 用 BILL_VS_SETTLE 跑一次对账 | **被劫持**：「bill_vs_settle 跑一次」在 bill_left 的列里没找到 | 回执 → 确认后任务 #4 SUCCESS |
| 确认 | **被劫持**：「这句我没接上…」 | 执行 |
| 对完了 | **被劫持**：「这句我没接上…」 | 「好，这轮就到这儿」 |

legacy 那一列暴露的正是"编排"的代价：**起草收集器一旦挂起，就独占会话**，
用户想先问别的、想跑别的，全都被翻译成"我还缺一个表名"。loop 没有这个问题——
每句话都由模型判断该回答什么。

## 三、数字

| 指标 | legacy（session 41） | loop（session 5） |
| --- | --- | --- |
| 八句 turnTokens | 295 / 287 / 298 / 315 / 319 / 331 / 325 / 329 | 251 / 314 / 402 / 453 / 527 / 626 / 0 / 737 |
| 累计 tokensUsed | 2 499 | 3 310 |
| 跳数（每句） | 1 | 4 / 1 / 1 / 2 / 1 / 1 / 1 / 1 |
| 工具调用 | list_diffs、run（+收集器读表） | describe_table ×2、list_diffs、run |
| 真正完成任务 | 起草卡住，其余被劫持 | 差异、表关系、跑任务、收尾都完成 |

多跳那句（4 跳：`draft_rule` 被打回 → describe ×2 → 回一句人话）是本轮最贵的一跳，
也是最值的一跳：它换来的是"两张表都没有『资源』这一列，你指哪个"——而不是一句猜出来的骨架。

## 四、循环里做的几处判断

1. **中间步骤只回灌给模型，不直接给用户**。第一版把每一步文本拼起来给用户，
   实测第一屏是一整张 `bill_right` 的列定义；改成"模型说过话就给它的话，没说话才退回最后一步的原始输出"。
2. **薄起草先退给模型**。模型带着「按订单、资源对账」这种一句话就来点 `recon_draft_rule` 时，
   不给用户回执，而是告诉模型"这条没执行，缺什么先问"——否则用户只能顺着说"确认"，
   然后拿一句含糊描述去后台起草。判据是 `businessDescription` 短于 40 字（启发式，见 §六）。
3. **对话记忆是双向的**。loop 模式下历史取"用户+助手"（助手侧截 120 字），
   否则模型看不到自己上一轮问过什么、查过什么，会把同一张表重查、同一个问题重问。
4. **收尾补一句**。模型查完就收手、没给结论时，再问一次"用一两句话把结论告诉用户"，
   避免用户收到一整张表的原始输出（跳数已用尽时不再追加）。

## 五、还没解决的

1. **跨轮状态没地方存**。用户说"bill_left"之后，下一轮模型仍会追问"资源是哪一列"——
   它记得左表，但没记住"这一轮该收的是资源"。loop 模式目前不写 slots；如果要把"卡槽"
   还回去，得想清楚是"模型自述状态"还是"系统记账"。
2. **legacy 的收集器劫持**（§二）没有修——回退到 legacy 时仍然存在。
   切模式时也要清 pending，否则悬着的回执会被下一条消息当确认。
3. **薄起草阈值是启发式**（40 字）。更好的判据是"描述里有没有表名/列名"，
   但那就又要解析一遍自然语言，和"不编排"的目标冲突，先记着。
4. **回执话术会被总结层改写**。有一条回执被模型压缩成了「确认（回「确认」或「取消」）」。

## 六、复现

```powershell
# 两个实例并行：loop 用 work/p2-loop 库，legacy 用 work/p2-demo 库
.\work\p2-real.ps1 -Port 18098 -Mode loop -MaxHops 6
.\work\p2-real.ps1 -Port 18097 -Mode legacy

# 八句剧本（结果落 logs\loop-probe.json；本报告引用的两份快照是
# logs\loop-probe-loop.json 与 logs\loop-probe-legacy.json）
.\work\p2-loop-probe.ps1 -Port 18098
.\work\p2-loop-probe.ps1 -Port 18097

# legacy 的四条验收不受影响
.\work\p2-accept-toolcall.ps1
```

回归：`.\mvnw.cmd -o test` → **873 项全绿**（原 868 + `ChatRuntimeLoopTest` 4 + `ChatHandbookTest` 1）。
