# 实测报告：闸门收口与启动自检（2026-10-27）

> 对应 `docs/open-questions.md` §四 的八项待办（M-a…M-h）。
> 方法：每一项都必须在**真服务**上跑出可见证据；只跑单测不算数。
> 环境：JDK 21 + `target/recon-lens-0.1.0.jar`，起在 `18088`（`--recon.agent.enabled=true --recon.review.api-key=k1`）。

## 结论速览

| # | 事项 | 状态 | 最硬的一条证据 |
|---|---|---|---|
| M-f | 生产档不许关审批 | ✅ | `prod` + `require-approval=false` → 启动失败，消息点名 `__MODEL` 会跳过试算与审批 |
| M-e | 生产档必须配 api-key | ✅ | `prod` 不配 key → 启动失败（不是"能起但写接口裸奔"） |
| M-b | 闸门 1 拒绝回结构化违规 | ✅ | `POST /trial` → **422**，`violations[0] = {code:UNKNOWN_TABLE, location:left.table, message, suggestion}` |
| M-a | 工具文本不再写"下一步" | ✅ | `/api/tools` 里 4 个工具 `write=true` + `suggestion` 独立成字段；工具正文不再以"下一步："开头 |
| M-d | mcp-smoke 按真实契约断言 | ✅ | 冒烟「结论：全部通过 ✅」，其中两条旧红断言已改成断言"落草稿/拒绝执行" |
| M-c | 评测 CI 不再静默跳过 p2 | ✅ | 没配模型的实例上跑出完整四指标（`docs/eval/ci-p2-nokey.md`，4/4 达标） |
| M-g | 文档规定"多轮走 /mcp" | ✅ | `docs/mcp.md` 新增 stdio vs streamable HTTP 对照 + 任务登记在进程内存的事实 |
| M-h | 两档实验（Q3） | ⚠️ 跑完但**没答出结论** | 真模型档与 P1 同分（100/100/2.13/141）；同时暴露 3 个新问题，见下节 |

单测：`mvnw -o test` → **809 tests, 0 failures**（本轮新增 `RuleTrialApiTest` 2 条、`StartupSafetyTest` 7 条）。

## 逐项

### M-f 生产档不许"关审批"（`config/StartupSafety`）

`recon.model.require-approval=false` 的效果不是"少跑一次试算"，而是 `recon_build_model` 把机器生成的
`__MODEL` 规则直接写成 ACTIVE。这是个**发布闸门**，不该是配置项。现在：

```
$ java -jar ... --spring.profiles.active=prod --recon.security.api-key=k --recon.model.require-approval=false
BeanCreationException: ... StartupSafety ... recon.model.require-approval=false：机器生成的 __MODEL 规则会直接
写成 ACTIVE，跳过试算与人工审批。这个开关只允许在开发档用（当前 profile=[prod]）；生产档下必须保持 true。
EXIT=1
```

开发档（`default`/`test`/`mcp`/`mysql`）保持原样，只 WARN —— 否则 `tools/mcp-smoke.mjs` 那类"造模型规则"的
本地流程会被一刀切死。**这条改动同时解释了**为什么冒烟脚本里那两条断言长期是红的：脚本是按"免审直通"写的。

### M-e 生产档必须配 api-key

```
$ java -jar ... --spring.profiles.active=prod
IllegalStateException: recon.security.api-key 未配置：生产 profile（[prod]）下写接口会变成匿名可调。
请设置 --recon.security.api-key=<密钥>，或把 recon.security.require-api-key 设为 false（只应在开发档这么做）
```

判定"生产"只看 profile 名里有没有 `prod`；`recon.security.require-api-key` 留成三态（null = 跟随环境、
true = 连开发档也上锁），这样联调环境想提前上锁不用改代码。

### M-b 闸门 1 的拒绝是结构化的

以前：`RuleGateRejectedException` 的违规被拼进一句中文，REST 只能 400 + 一句话。

现在：`422`，且 `violations[]` 带 `code / location / message / suggestion`。

```
POST /api/rule-reviews/BILL_GHOST/1/trial     (X-Api-Key: k1)
STATUS=422
{"error":"gate_rejected","message":"闸门 1 拒绝 BILL_GHOST v1，未进入试算：UNKNOWN_TABLE @left.table: ...",
 "ruleCode":"BILL_GHOST","ruleVersion":1,
 "violations":[{"code":"UNKNOWN_TABLE","location":"left.table",
   "message":"left.table = bill_ghost 不在白名单内，或数据源里没有这张表",
   "suggestion":"只能用 recon.agent.schema.tables 里列出的表；拼写与 schema 前缀都要对"}],
 "action":"按 violations[].location 改规则体；改完重新落草稿、再试算。草稿本身不会被这条闸门拒绝"}
```

为了让这条**有同步出口**，顺带补了 `POST /api/rule-reviews/{code}/{version}/trial`：以前试算只能从 MCP
定义规则或异步调度器触发，闸门拒绝只落在日志里，调用方看到的是"任务已排队"。agent 未启用时它回 **501**
并说明原因，而不是伪装成"试算中"。

两个实现细节值得记：

1. 异常搬到了 `dsl` 包。它要穿过 `rule.RuleTrialRunner`（实现由 agent 条件装配）冒到 `web`，放在 `agent`
   包会让 `web → agent` 这条边成立 —— `AgentBoundaryTest` 当场抓到了（6 处违规）。搬过去后只保留"已渲染好
   的视图"，`SafetyViolation` 留在 agent 里由 `ShadowTrialService` 转换。
2. controller **自己**的 `@ExceptionHandler(RuleException.class)` 比 `@RestControllerAdvice` 优先，而闸门拒绝
   异常是它的子类 —— 不显式拦一道，422 会被降级成 400。本轮实测就踩了这个（先 400 后 422）。

### M-a "下一步"从正文挪到字段

`/api/tools`：

```
recon_run          write=True  suggestion=recon_list_diffs 看差异明细，recon_explain_job 生成归因
recon_define_rule  write=True  suggestion=recon_trial_status 看试算结论（草稿要过试算 + 人工审批才生效）
recon_job_summary  write=False suggestion=recon_explain_job 生成归因，recon_list_diffs 翻差异明细
recon_draft_rule   write=True  suggestion=recon_trial_status 轮询起草与试算进度；审批只能由人做
```

为什么非要挪：工具正文是**模型眼里的观测结果**，而"下一步：recon_xxx"是一句指令。混在一起，模型会把建议
当事实复述给用户（"系统说下一步要审批"）。结构化字段让宿主/控制台自己决定怎么展示。

### M-d 冒烟脚本改成断言真实契约

```
✅ recon_build_model 建表并注册了模型规则
✅ 模型规则落草稿而不是直接生效（正文里带状态）
✅ 未经试算与审批的模型规则拒绝执行：调用失败：RuleException: 规则 BILL_VS_SETTLE__MODEL 存在（v1，状态 DRAFT），
   但没有可以运行的生效版本：先跑试算，再走人工审批生效
结论：全部通过 ✅
```

第二、三条以前是红的：旧断言写的是"建完直接跑、差异分类一致"，等于替脚本把闸门拆了。

### M-c 评测 CI 的两档都留结论

`eval.yml` 的 `p2` 档不再跳过模型缺失：没配 `DEEPSEEK_API_KEY` 时照跑，按"模型不可用"记录。
本地等价验证（打的就是没配模型的实例）：

```
意图命中率：100.0%  (30/30)     工具选择率：100.0%  (25/25)
平均轮数：2.13                   每会话 token 中位数：136
→ docs/eval/ci-p2-nokey.md（4/4 达标）
```

这条的要害不是"数字好看"，而是**结论不会因为 CI 没配密钥而从基线里整条消失**。

### M-h 两档实验：暂不结论

要回答"P2 比 P1 到底多值多少"，必须有真实模型。桩模型只能验管线（prompt 是否被裁、工具是否被调用一次），
验不出"口语化输入的意图命中率提升"。缺 key 时**不下结论**，只把实验设计钉在 `open-questions.md` §五。

## 新发现（本轮跑出来的）

1. **controller 局部 `@ExceptionHandler(RuleException)` 会吃掉闸门拒绝的 422** —— 这条已经**全仓修完**：
   `RuleReviewController` / `RuleController` / `JobController` / `ModelController` 四处都补了
   `RuleGateRejectedException` 的专用 handler，统一走 `GateRejectionHandler.response(e)`（措辞只有一份）。
   另加 `GateRejectionWiringTest`：用 ArchUnit 扫 `web` 包，凡是处理了 `RuleException` 的 `@RestController`
   都必须同时处理它的子类，否则测试当场红 —— 这个坑不该靠人记住。
2. **`POST /api/rules` 的老定义口**仍挂着 `LegacyDefineDisabledException` 分支，与"草稿只能经 Agent/MCP 落库"
   的新叙事并存，文档里没有说清哪个是正路。
3. 冒烟里 `recon_run` 对未生效规则的报错已经变成"存在（v1，状态 DRAFT）但没有可运行的生效版本"——
   `RuleCatalog.notRunnable` 改对了，且**在真服务上被冒烟脚本自己用来做断言**。

## 复现命令

```powershell
$env:JAVA_HOME='D:\App\ideaIC-2025.3\jbr'; $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd -o test                                   # 809 tests / 0 failures
.\mvnw.cmd -o -q package -DskipTests
& "$env:JAVA_HOME\bin\java.exe" -jar target\recon-lens-0.1.0.jar --server.port=18088 `
    --recon.agent.enabled=true --recon.mcp.http.enabled=true --recon.review.api-key=k1
$env:BASE='http://127.0.0.1:18088'; $env:EVAL_LABEL='ci-p2-nokey'; node work\golden-run.mjs
node tools\mcp-smoke.mjs target\recon-lens-0.1.0.jar
```
