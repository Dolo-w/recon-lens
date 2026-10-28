# 批 1 实测报告：工具目录（M5 + M6）

> 同批 0 的标准：起真实服务、发真实请求、贴原始回显。凡是能从外面看到的数字，都是 `curl` 出来的，不是单测断言的。

## 结论速览

| 项 | 问题 | 实测结论 |
|---|---|---|
| M5 | 控制面报 12 个工具，MCP 宿主报 18 个 | **已修**：两边都报 18，且重名会在启动期炸 |
| M6 | 「哪些工具会改状态」记在枚举里；工具结果夹带「下一步：recon_xxx」 | **已修**：`ToolSpec.write()` 查唯一清单；建议变结构化字段，正文干净 |

回归：`mvnw -o test` → **775 tests / 0 failures**（批 0 时 771，新增 4 条），`package` BUILD SUCCESS。

---

## M5 工具目录合一

**改前为什么对不上**

三处代码各自拼清单：

| 位置 | 数 | 说明 |
|---|---|---|
| `web/ToolController` → `/api/tools` | 12 | 只读 `ReconToolRegistry`（基础工具） |
| `mcp/McpServerRunner` → 宿主 `tools/list` | 18 | `McpSpecifications.build(json, registry, agentTools)` 现场把两份合起来 |
| `mcp/AgentMcpTools` | 6 | 自己造 `SyncToolSpecification`，没经过 `ToolRegistry` |

而且 `ToolNames.AGENT` 这份"权威清单"本身也是错的：里面有 `recon_list_approvals`（那是个 REST 接口、根本不是 MCP 工具），真正注册了的 `recon_import_status` 反而没有常量。

**改后：一个合并点**

```
tool/ToolCatalog (@Primary, ToolRegistry)
  ├── ReconToolRegistry.specs()      12 个基础工具
  └── List<AgentMcpTools>.specs()     6 个 agent 工具（agent 关着就是 0 个）
  └── 重名 → IllegalStateException（启动失败，而不是后注册的静默覆盖）
```

`McpSpecifications` 退回它该干的活：把 `ToolSpec` 翻译成 MCP 协议对象（含 `isError` 包装）。`McpServerRunner`、`McpHttpConfiguration` 不再注入 `List<AgentMcpTools>`。

**实测**

改前（`git stash` 前的记录，见批 0 现场的 `/api/tools`）：
```
GET /api/tools        → total 12
宿主 tools/list       → 18 个（含 recon_import_status）
```

改后：

```
GET /api/tools        → {"total":18,"items":[…]}          ← 控制面
MCP stdio（agent 开） → 已就绪（stdio）：18 个工具
                        [… recon_import_status, recon_submit_approval]
MCP stdio（agent 关） → 12 个                              ← 预期：AgentMcpTools 挂在 recon.agent.enabled 上
```

守护测试 `ToolCatalogParityTest`：bean 里的名字 == `McpSpecifications.build` 吐出来的协议对象，逐一对齐。

## M6 写动作标注 + 建议字段

**改前**

- `ChatRuntime#needsWriteConfirm` 读 `ChatIntent#isWrite()`；加一个新写工具（后来的 `recon_import_file`）忘了改枚举，会话就会把该先回执的动作直接执行——没有编译期信号。
- `ReconMcpTools.run` 的返回文本（MCP 与会话共用）自带尾巴：

```java
String text = "规则 " + rule + "\n" + jobSummary(job.id())
        + "\n下一步：recon_list_diffs 看差异明细，recon_explain_job 生成归因。";
```

于是模型会把"下一步"当事实复述，`ChatSpeech#stripAgentTail` 还得专门擦一遍。

**改后**

- `ToolNames.WRITES`：一份清单（`DEFINE_RULE / BUILD_MODEL / RUN / DRAFT_RULE / REGISTER_TABLE / IMPORT_FILE / SUBMIT_APPROVAL`），`ToolSpec.write()` 查它。
- `ToolNames.SUGGESTIONS`：`RUN → recon_list_diffs…`，`JOB_SUMMARY → recon_explain_job…`；结果正文不再拼。
- `/api/tools` 每项多两个字段。

**实测**

```
$ curl /api/tools | jq '.items[] | select(.write==true) | .name'
recon_run            write=True   suggestion=recon_list_diffs 看差异明细，recon_explain_job 生成归因
recon_import_file    write=True   suggestion=
recon_list_rules     write=False  suggestion=
recon_job_summary    write=False  suggestion=recon_explain_job 生成归因，recon_list_diffs 翻差异明细
```

会话端跑一次真实对账（`logs/after-m6-run2.sse`，桩模型被强制指定 `RUN`）：

```
event:plan   {"intent":"RUN","args":{"ruleCode":"BILL_VS_SETTLE"},"source":"LLM"}
event:tool   recon_run
event:delta  分片 2/2 完成｜扫描 左=10 右=10｜匹配 9｜差异 4
event:delta  差异分类：AMOUNT_MISMATCH=1，DUPLICATE_KEY=1，MISSING_LEFT=1，MISSING_RIGHT=1
event:delta  耗时 6 ms          ← 到这里就结束了，没有"下一步：recon_list_diffs"
event:slot   {"jobId":2,"ruleCode":"BILL_VS_SETTLE","lastIntent":"RUN"}
```

`grep -c 下一步 logs/after-m6-run2.sse` = **0**。

---

## 顺手挖出的旧账（不计入本批）

`tools/mcp-smoke.mjs` 有 2 条断言**一直会挂**，与本批改动无关：

```
❌ 读模型表跑出来的差异分类与原始规则一致
❌ 读模型表时两侧行数不变（左=20 右=21）
→ RuleException: 未找到规则 BILL_VS_SETTLE__MODEL
```

`recon_build_model` 会注册 `<code>__MODEL`，但 `model/ModelBuilder.java:116` 在 MCP 通路上刻意落成 **DRAFT**（注释原文："MCP 通路不允许直通生效"），而 `recon_run` 只认 ACTIVE。脚本的假设停在"建完就能跑"的旧版本——属于脚本/产品边界漂移，要么脚本先走审批，要么断言改成"草稿且可批"。修脚本不在 18 项里，记在这里免得下次又当成回归。

## 这批没做的事

1. **没有让 `needsWriteConfirm` 真的去查 `spec.write()`**。查它需要"意图 → 工具名"的映射，而这份映射现在藏在 `ChatRuntime#dispatch` 的 switch 里，硬抽出来等于再造一份清单。本批的做法是保留 `intent.isWrite()`、用 `ToolWriteParityTest` 断言两边一致——cost 最低，漂移时测试会红。
2. `suggestion` 只有 2 个工具给了，其余 16 个是 `null`。补全没意义：大部分工具跑完确实没有"下一步"。
3. `/api/tools` 新增了 `write` / `suggestion` 两个字段，属于**向后兼容的加字段**，没有改版本号。

## 复现

```powershell
$env:JAVA_HOME="D:\App\ideaIC-2025.3\jbr"; $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
cd D:\workspace\deepseek\recon-lens
.\mvnw.cmd -o test        # 775 通过
.\mvnw.cmd -q -o package -DskipTests
# 应用（18081，agent 开）与桩模型（18999）同批 0 的命令
node tools/mcp-client.mjs list -- --recon.agent.enabled=true   # 宿主侧看 18
node tools/mcp-client.mjs list                                 # 不启 agent 看 12
```
