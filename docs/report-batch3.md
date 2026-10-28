# 批 3 实测验证报告：一次请求里的第二步

对象：`recon-lens`，批 3（M10 / M11）。
时间：2026-10-26 22:35–23:05（Asia/Shanghai）。
运行环境：JDK 21、`target/recon-lens-0.1.0.jar`、H2 内存库、桩模型 `tools/stub-llm.mjs`。

> 这是六批里唯一**改变用户体验**的一批，也是最危险的一批：
> 它改的是"一次请求里发生几次动作"。所以整批按"能一键回到今天"来设计——
> `recon.chat.loop.enabled` 默认 **false**，关掉时行为与改之前一致（下面有 A/B 实测为证）。

---

## 一、结论速览

| 项 | 内容 | 状态 | 最硬的一条证据 |
|---|---|---|---|
| M10 | 规划器新增「还差一步吗」契约 | ✅ | 第二次模型调用 2423 字符，prompt 里出现 `## 第二步` 与 `previous-result` 数据块 |
| M11 | 运行时执行第二步 + 合并叙述 | ✅ | 同一句话：2 次工具（`recon_list_jobs` → `recon_explain_job`）、1 次叙述、`lastIntent=EXPLAIN_JOB` |
| 默认关 | 关掉开关后一次请求只做一件事 | ✅ | 同句：1 次模型调用、桩脚本游标停在 1（第二条没被消费）、1 次工具 |

测试：`mvnw -o test` → **786 tests, 0 failures**（批 2 是 781，新增 5 条第二步闸门用例）。

---

## 二、和路线图不一样的地方（先讲偏差）

路线图里写的是"`PlanSequence(first, then)`，一次规划给出两步"。**实测证明那样不行**：
第二跳的参数往往来自第一跳的结果（"看看任务 1 的差异" → 拿到差异清单 → 才知道要归因哪一条）。
在规划时就把第二步定死，等于让模型猜一个还没出现的 id。

所以最终形态是**两次判断、一次请求**：

```
用户一句话
   └─ plan()            ← 第一次判断：用户说了什么
        └─ dispatch()   ← 执行，拿到真实结果
             └─ continuePlan()   ← 第二次判断：看着这个结果，还差一步吗
                  └─ dispatch()  ← 最多再执行一次
                       └─ narrate() ← 只叙述一次
```

`ChatPlan` 一个字段都没加，`ChatPlanner` 加的是**一个带默认实现的 `continuePlan`**：
词表规划器不实现它（它不会"看结果再决定"），所以 P0/P1 链路连这个方法都不会被调到。

---

## 三、M10：续跑的闸门比第一次规划更硬

第二次判断发生在同一次请求里，**没有"用户点头"的机会**。所以门槛反过来收紧：

| 闸门 | 第一次规划 | 第二步 |
|---|---|---|
| 写动作 | 允许（但要回执确认） | **直接丢弃** |
| 证据要求 | `CONSISTENT` / `UNVERIFIED` 都可进（UNVERIFIED 走澄清） | **必须 `CONSISTENT`** |
| 关键参数 | 缺了可以反问 | 缺了就当没这一步 |
| 重复 | — | 同动作同参数 → 丢弃 |
| 失败 | 计入熔断 | **不计熔断**（与"模型说 DONE"无法区分，不值得污染主判据） |

三条拒绝理由都钉了用例（`LlmPlannerTest`，21 条里有 5 条是本批新增）：

```
第二步：模型说 DONE 就是没有下一步
第二步：写动作一律不续跑——回执必须等用户点头
第二步：不许重复上一步，否则一次请求会做两遍同一件事
第二步：编造的任务号与缺参数都不执行
第二步：实体对得上、参数齐、不重复——这一个才放行
```

---

## 四、M11：运行时只认读动作，且只叙述一次

`ChatRuntime.followUp` 里写死三条（写在代码里，不写在提示词里——提示词是建议，代码是闸）：

1. 上一步是写动作 → 不续跑（回执必须等用户说"确认"，续跑等于绕过回执）；
2. 第二步就算模型硬给了 `RUN`/`RETRY`，**直接丢掉**；
3. 第二步抛异常 → 已拿到的结果照常返回，只是少做了一件事。

顺手修掉一个只有实测才会暴露的记账错误：槽位里的 `lastIntent` 原本会记成**第一跳**的动作。
开了第二步以后，下一轮模型会以为上一步是 `LIST_JOBS`，而实际最后执行的是 `EXPLAIN_JOB`。
现在用 `AtomicReference<ChatIntent>` 把"真正最后执行的那个"带到槽位里（实测 `event:slot` 已经是
`{"lastIntent":"EXPLAIN_JOB","jobId":1}`）。

---

## 五、A/B 实测（同一句话，只差一个开关）

消息：**「帮我看看最近都跑过哪些对账任务」**
桩脚本（两段式）：

```json
[
 {"action":"LIST_JOBS","args":{},"confidence":0.9,"reason":"用户要看最近的任务"},
 {"action":"EXPLAIN_JOB","args":{"jobId":1},"confidence":0.85,"reason":"顺手把最新那单整单归因也做了"}
]
```

### A. 开关打开（`--recon.chat.loop.enabled=true`）

```
event:plan   {"intent":"LIST_JOBS",...,"source":"LLM"}
event:tool   {"name":"recon_list_jobs","summary":"读任务历史，按规则汇总跑了多少次"}
event:plan   {"intent":"EXPLAIN_JOB",...,"source":"LLM","reason":"顺手把最新那单整单归因也做了"}
event:tool   {"name":"recon_explain_job","summary":"任务 1 的整单归因"}
event:delta  …两段结果拼在一起…
event:slot   {"lastIntent":"EXPLAIN_JOB","jobId":1,"ruleCode":"","diffId":""}
```

模型调用两次：**1926 字符**（第一次）→ **2423 字符**（第二次，含 `## 第二步` 与
`<<<DATA-… previous-result>>>` 块）。控制台上看得到"这一步是接着做的"，不是假装一句话只发生了一件事。

### B. 开关关闭（默认）

```
event:plan   {"intent":"LIST_JOBS",...,"source":"LLM"}
event:tool   {"name":"recon_list_jobs",...}
event:delta  …只有第一段…
```

模型调用**1 次**，桩脚本游标停在 **1**（第二条脚本从头到尾没被消费），工具**1 次**。
这就是"关掉开关 = 回到今天"的直接证据，而不是一句承诺。

证据文件：`logs/after-m11-loop-on.sse`、`logs/after-m11-loop-off.sse`、`logs/after-m11-step2-prompt.txt`。

---

## 六、本批发现（未修，记账）

1. **第一步的返回可能已经包含了第二步的答案**。第一次试跑用的是「看看任务 1 的差异」→
   `LIST_DIFFS` 的文本本身就把每条差异的模板归因带出来了，于是第二步（`EXPLAIN_DIFF`）是纯重复。
   桩模型不会判断冗余，真实模型看到结果大概率会回 `DONE` —— 这说明第二步的价值完全取决于
   "第一跳返回什么"，也是为什么它只在模型源、且证据必须 `CONSISTENT` 时才会发生。
2. **`diffId` 不在系统事实里**。`ContextFacts` 只有规则/任务/表（`ruleCodes/jobIds/tables`），
   所以 `EXPLAIN_DIFF(diffId=999)` 这类续跑的实体**无法核实**，目前只做"参数存在"检查。
   这是事实卡片范围的问题，留给批 4（正确性）记账。
3. 工具层的桩能力补齐：`stub-llm.mjs` 现在支持回复文件写成**数组**（按调用次序取、有 `/reset`
   接口归零游标），探活 GET **不消耗游标**——否则一次探活就把多步脚本的第一条吃掉了。

---

## 七、复现

```powershell
# 桩：两段式脚本 + 归零
@'
[{"action":"LIST_JOBS","args":{},"confidence":0.9},
 {"action":"EXPLAIN_JOB","args":{"jobId":1},"confidence":0.85}]
'@ | Set-Content logs\stub-reply.txt -Encoding utf8
Invoke-RestMethod http://127.0.0.1:18999/reset

# A：开
java -jar target\recon-lens-0.1.0.jar --server.port=18082 --recon.agent.enabled=true `
  --recon.chat.loop.enabled=true --recon.chat.llm.base-url=http://127.0.0.1:18999 `
  --recon.chat.llm.api-key=stub-key --recon.chat.llm.model=stub

# B：关（默认），重复同一句，对比 SSE 与 stub-llm.jsonl 的条数
```
