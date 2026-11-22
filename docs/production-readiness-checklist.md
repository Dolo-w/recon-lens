# recon-lens 生产级 Agent 就绪清单（2026-11-22）

> 用途：把「离生产级还差多少」从主观百分比改为**逐条可验证的 checklist**。
> 每条三态：✅ 已具备（附证据）／⚠️ 半成品或有条件成立／❌ 缺失。
> 评分口径 = 功能覆盖(40%) × 故障路径(40%) × 证据强度(20%)，按条目加权，不按感觉。
> 结论先行：**P0 七项已于 2026-11-22 交付（6 项达标，第 7 项备份演练待真人执行）；P1 六项 = SaaS 多租户（估 2-3 周）。**
> 当前水位：✅ 55 / ⚠️ 12 / ❌ 13（共 81 条），新增 55 条测试（全量 1110 绿 0 skip）。

## 打分规则（可复核）

- **功能覆盖**：该能力是否存在（可 grep 验证）。
- **故障路径**：失败时的行为是否可接受——fail-closed 还是 fail-open、静默吞还是如实报错、有无恢复路径。
- **证据强度**：✅ 需要「测试 / 真实流量 / grep 可查的代码事实」三者之一；只有设计意图没有验证的，一律 ⚠️。
- 一条 ❌ 的功能覆盖扣满该项权重，⚠️ 扣一半；故障路径未验证的条目不得记 ✅。

---

## 1. Agent 内核（编排）—— ✅ 7 / ⚠️ 1 / ❌ 1 / ➖ 1

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 1.1 | 编排循环（目标→感知→起草→试验→落库） | ✅ | `agent/AgentOrchestrator.java` |
| 1.2 | 会话持久化（user/assistant/tool 三角色） | ✅ | `chat/ChatSessionRepository`，角色白名单+长度上限 |
| 1.3 | 流式输出 | ✅ | `web/ChatController` SSE |
| 1.4 | 崩溃恢复（中断的 run 可续/可结） | ✅ | `agent/AgentRunRecovery.java` |
| 1.5 | 预算熔断 | ✅ | `agent/AgentBudget.java`（按 run） |
| 1.6 | 步骤级审计字段（phase/action/digest/truncated） | ✅ | `store/AgentRunRepository`，`recon_agent_run` |
| 1.7 | 会话压缩（长上下文） | ✅ | ChatSessionService 压缩策略 |
| 1.8 | 幂等（重复触发同一目标） | ⚠️ | 规则侧有版本/去重，编排入口幂等键未统一 |
| 1.9 | **任务状态外置（多实例）** | ❌ | `ImportTaskRegistry` / `TrialTaskRegistry` 均为进程内 `ConcurrentHashMap`——重启即丢、多实例不可见 |
| 1.10 | 子 agent / 多 agent | ➖ | 评估后明确不做（见 architecture-explained.md） |

## 2. 工具层—— ✅ 5 / ⚠️ 2

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 2.1 | 工具注册表 + 按需加载（20 常驻/26 总） | ✅ | `tool/ReconToolRegistry` + `ToolNames` |
| 2.2 | 只读 SQL 闸门（表白名单+语句校验） | ✅ | `SchemaTableAllowList` / `SqlText` |
| 2.3 | 写动作审批闸门 | ✅ | `POST /api/rule-reviews/**`，未配 key 一律拒绝（fail-closed） |
| 2.4 | 结果截断检测 | ✅ | `SqlTruncationDetector`，截断如实标注 |
| 2.5 | MCP 双传输（stdio + streamable HTTP） | ✅ | `McpHttpConfiguration`，默认关闭需显式开启 |
| 2.6 | 工具级超时 | ⚠️ | 部分连接器有，工具层未统一 |
| 2.7 | 工具级幂等声明 | ⚠️ | 未建档（哪些工具可安全重放） |

## 3. 安全治理—— ✅ 4 / ⚠️ 2 / ❌ 2

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 3.1 | 间接注入防护（nonce 物理分隔） | ✅ | `llm/PromptGuard`，含形状中和/截断标注 |
| 3.2 | 规则安全校验（防自伤/防越权） | ✅ | `RuleSafetyValidator` + `SafetyViolation` |
| 3.3 | 影子试验 + 金丝雀放量 | ✅ | `ShadowTrialService` / `CanaryInjector` / `TrialVerdictResolver` |
| 3.4 | 敏感数据不落记忆 | ✅ | 知识库只存形状不存值 |
| 3.5 | **API 写接口鉴权** | ✅ | `StartupSafety.checkApiKey`（prod 拒绝启动）+ `checkExposure`（显式绑非回环地址且无 key 时也拒绝启动）——堵住的正是"拷个 jar 到服务器直接 java -jar、没设 profile"这个真实事故场景；`SecurityPosture` 暴露 `recon.security.write_auth_enforced` gauge 给告警 |
| 3.6 | 密钥管理 | ⚠️ | 仅环境变量，无 vault/轮换流程 |
| 3.7 | **RBAC / 多用户** | ❌ | 无角色模型（`SessionScope` 只是会话隔离的雏形） |
| 3.8 | **限流** | ❌ | 全项目无限流；LLM 调用可被对话打爆 |

## 4. 记忆与知识层—— ✅ 6 / ⚠️ 1 / ❌ 3

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 4.1 | 规则库版本化 + 快照回放 | ✅ | `rule_version + rule_snapshot` |
| 4.2 | 归因知识库（形状签名） | ✅ | 只存形状不存值，多候选不覆盖 |
| 4.3 | 业界知识库 + 置信度生命周期 | ✅ | `IndustryKbService`，真实验证命中率 95% |
| 4.4 | 三路写入（蒸馏/摄取/YAML）+ 机器准入 | ✅ | `KnowledgeHarvestService` / `IndustryKbSeeder` |
| 4.5 | 故障降级无害 | ✅ | select catch→空，不挡归因主链路 |
| 4.6 | 路由语料（fallback 落库+挖掘） | ✅ | `FallbackMiningService` |
| 4.7 | 知识条目版本史 / 审计 | ⚠️ | 有 `source` 字段，无变更流水与回滚 |
| 4.8 | **quote 机械校验** | ❌ | 条目无原文摘录，"内容真来自 source"不可机械验证 |
| 4.9 | **语义近重检测** | ❌ | admit 只做 key 精确去重；实测单篇出 10+ 近义条目 |
| 4.10 | **采集管道健康指标** | ❌ | 解析失败无计数，静默返回空（今天 0/0/0 空转即此） |

## 5. 模型层—— ✅ 4 / ⚠️ 1 / ❌ 4

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 5.1 | OpenAI 兼容客户端 | ✅ | `OpenAiCompatibleChatClient` |
| 5.2 | 超时可配 | ✅ | `recon.llm.timeout-*` |
| 5.3 | usage 抓取（真实 token） | ✅ | `usageOf()`，`recon_agent_run.llm_tokens` |
| 5.4 | 双模型分工（路由快/归因慢） | ✅ | `recon.chat-llm` 与 `recon.llm` 分离 |
| 5.5 | 单步预算 | ⚠️ | AgentBudget 按 run；按日已有告警（`daily_cost` 规则），但**没有按客户/按日硬熔断**——超预算只是通知，不会自动停 |
| 5.6 | **重试 + 退避** | ✅ | `OpenAiCompatibleChatClient`：可配 `max-attempts`（默认 3），指数退避 + ±25% 抖动；可重试（网络/超时/429/5xx）与不可重试（401/400/空正文）分流。`LlmRetryAndFallbackTest` 9 条用真 HTTP 服务验证 |
| 5.7 | **多 provider fallback** | ✅ | `recon.llm.fallback.*`：主 provider 整体不可用时接管，key/model/单价可独立或继承。实测"主 500×2 → 备用接管成功" |
| 5.8 | **成本归因（单价×token）** | ✅ | `recon_llm_call` 表 + `LlmCallRepository`；单价**落库时快照**（改价不篡改历史账）；整数 micros 无浮点误差；`/api/costs/daily|summary|recent`；归因/路由/起草/蒸馏四处已接 `LlmPurpose` |
| 5.9 | prompt cache 管理 | ❌ | 固定块顺序有约定，无命中率度量 |

## 6. 意图路由与交互—— ✅ 7 / ⚠️ 1

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 6.1 | 快慢双路径（词表 + LLM） | ✅ | `ChatIntentRouter` / `LlmPlanner` |
| 6.2 | 槽位状态机（一次一问/过期/补答不重判） | ✅ | `SlotProtocol` |
| 6.3 | 意图缓存（含失效策略） | ✅ | `CompositePlanner`，写动作 invalidate |
| 6.4 | 澄清与挂起 | ✅ | 15 分钟过期，起草豁免 |
| 6.5 | 复合意图（followUps） | ✅ | `ChatPlan.followUps` |
| 6.6 | 路由指标（通道/意图/澄清/fallback） | ✅ | `RoutingMetrics`（进程级+落库） |
| 6.7 | golden 回归（26 条，防空集） | ✅ | `IntentGoldenTest` |
| 6.8 | 多语言 | ⚠️ | 中文为主，英文语料/口音未验证 |

## 7. 评测闭环—— ✅ 3 / ❌ 4

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 7.1 | 单测全绿 1061 | ✅ | `mvnw test`，0 skip |
| 7.2 | 意图 golden 集 | ✅ | 26 条 + fallback 挖掘补充 |
| 7.3 | 知识蒸馏 golden | ✅ | `harvest-test/golden-expected.md`，11 条+评分表 |
| 7.4 | **任务级端到端 eval（完成率）** | ✅ | `eval/rules/`（6 条规则）+ `eval/tasks.yml`（**31 条用例**）+ `EvalSuiteRunner` + `EvalSuiteContractTest`：真引擎跑完整链路（规则解析→SQL 生成→归并→比较→落库→分类），当前 **31/31** |
| 7.5 | **归因质量 eval（explain 正确率）** | ❌ | 无样本集，LLM 说得好不好无度量 |
| 7.6 | **发版回归门槛** | ✅ | `EvalSuiteContractTest` 随 `mvn test` 执行：改规则解析/比较器/容差口径，破坏业务语义必红。另断言"用例数 ≥30"，防回归集悄悄缩水成空集 |
| 7.7 | **LLM 行为回归（A/B 对比）** | ❌ | prompt 改动无前后对比机制 |

## 8. 可观测性—— ✅ 3 / ❌ 4

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 8.1 | 结构化日志 | ✅ | `logback-spring.xml` |
| 8.2 | 业务报表（财务/ROI） | ✅ | `FinanceReportService` / `KnowledgeRoiService` |
| 8.3 | 路由指标自建 | ✅ | `RoutingMetrics` |
| 8.4 | **metrics（micrometer）** | ✅ | `spring-boot-starter-actuator` + `micrometer-registry-prometheus`；`LlmMetrics` 出 `recon.llm.calls/latency/tokens`（tag：purpose/model/provider/outcome）；`SecurityPosture` 出三个安全 gauge。`ActuatorHealthTest` 5 条验证 Prometheus 端点实抓 |
| 8.5 | **tracing（OTel）** | ❌ | 一次请求跨 5 层无 trace id |
| 8.6 | **健康检查端点** | ✅ | `/actuator/health` + liveness/readiness 分开（滚动重启不误杀收尾实例）；`show-details=when_authorized` 默认不泄漏内部组件 |
| 8.7 | **告警** | ✅ | `AlertService` 三条规则（LLM 失败率 / 写接口裸奔 / 单日成本）+ `recon_alert` 表 + webhook 推送 + `/api/alerts`。去重（同 key 只留一条 OPEN）、可恢复、样本不足不误判，均有测试 |

## 9. 可运维与交付—— ✅ 5 / ⚠️ 1 / ❌ 3

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 9.1 | Docker 多阶段构建（Go worker+JRE） | ✅ | `Dockerfile` |
| 9.2 | compose 一键起（MySQL+健康检查） | ✅ | `docker-compose.yml`（演示明文凭证，注释已说明） |
| 9.3 | 迁移脚本（带日期+可回滚） | ✅ | `docs/migrations/` 3 份 |
| 9.4 | 配置分档（dev/mysql/prod） | ✅ | `application*.yml` + StartupSafety 生产档校验 |
| 9.5 | 规则级灰度 | ✅ | CanaryInjector（注意：是"规则"灰度，不是"部署"灰度） |
| 9.6 | 升级兼容（迁移执行顺序） | ⚠️ | 迁移靠手工按序执行，无版本表/自动执行 |
| 9.7 | **备份与恢复演练** | ⚠️ | `scripts/backup.sh`（single-transaction / 凭证走临时文件 / 备份后自动校验 / 保留策略）+ `scripts/restore.sh`（默认恢复新验证库、覆盖需 --yes）+ `docs/backup-restore-sop.md` 已交付，`BackupScriptTest` 6 条验证语法与保护性参数。**真实 MySQL 演练尚未执行**——按本清单纪律，未演过不记 ✅ |
| 9.8 | **多实例/水平扩展** | ❌ | 内存态注册表 + 进程级指标/缓存 → 只能单实例 |
| 9.9 | **SLO / 容量规划** | ❌ | 无延迟/吞吐目标值 |

## 10. 产品可用性—— ✅ 2 / ⚠️ 3 / ❌ 1

| # | 条目 | 状态 | 证据 / 缺口 |
|---|---|---|---|
| 10.1 | 可读报表（中文一页纸） | ✅ | `/api/reports/finance` |
| 10.2 | 对话入口（SSE 流式） | ✅ | `/chat` 控制器 |
| 10.3 | 对话起草知识/规则 | ⚠️ | DEFINE_RULE 草稿链路在，知识起草入口未做（遗留项） |
| 10.4 | 错误文案指向真问题 | ⚠️ | 今天修了一例（图片报"未配 key"）；未系统排查 |
| 10.5 | 多租户可见性 | ⚠️ | `SessionScope` 雏形，隔离不完整 |
| 10.6 | 多语言 UI | ❌ | 中文硬编码 |

---

## 汇总

| 维度 | ✅ | ⚠️ | ❌ | 判定 |
|---|---|---|---|---|
| 1 Agent 内核 | 7 | 1 | 1 | **过线**（多实例除外） |
| 2 工具层 | 5 | 2 | 0 | **过线** |
| 3 安全治理 | 5 | 1 | 2 | 近线（RBAC/限流） |
| 4 记忆知识 | 6 | 1 | 3 | 近线（三点都是小活） |
| 5 模型层 | 7 | 1 | 1 | **已过线** |
| 6 意图路由 | 7 | 1 | 0 | **过线** |
| 7 评测闭环 | 5 | 0 | 2 | 近线（缺归因质量 eval / A-B 对比） |
| 8 可观测性 | 6 | 0 | 1 | **已过线**（缺 tracing） |
| 9 可运维 | 5 | 2 | 2 | 近线（备份未演练 / 单实例） |
| 10 产品可用 | 2 | 3 | 1 | 近线 |

**总账：✅ 55 / ⚠️ 12 / ❌ 13 / ➖ 1（共 81 条）**

---

## P0：私有化单客户交付门槛 —— ✅ 已全部交付（2026-11-22）

| # | 条目 | 状态 | 交付物 |
|---|---|---|---|
| 1 | **micrometer + actuator**：健康检查 + QPS/延迟/错误率/token 指标 | ✅ | `ActuatorHealthTest`（5 条）；`/actuator/health` + liveness/readiness + `/actuator/prometheus` 实抓通过 |
| 2 | **LLM 调用统一记录 + 成本归因** | ✅ | `recon_llm_call` + `/api/costs/daily`；四处调用点已接 purpose；`LlmCallRepositoryTest`（6 条）+ `LlmRetryAndFallbackTest`（成本断言） |
| 3 | **鉴权 fail-closed** | ✅ | prod 拒绝启动（原有）+ 显式非回环监听拒绝启动（新增）；`StartupSafetyTest` 14 条、`SecurityPostureTest` 5 条 |
| 4 | **LLM 重试 + 退避 + 备用 provider** | ✅ | `LlmRetryAndFallbackTest` 9 条（真 HTTP 故障注入：500/429/401/超时/fallback 接管） |
| 5 | **任务级 eval 集 + 回归脚本** | ✅ | 31 条用例 31/31 通过；`EvalSuiteContractTest` 随 `mvn test` 跑，成为发版门槛 |
| 6 | **告警最小集** | ✅ | 三条规则 + webhook + `/api/alerts`；`AlertServiceTest` 11 条（去重/恢复/样本不足/webhook 失败） |
| 7 | **备份 + 恢复演练** | ⚠️ | 脚本 + SOP 已交付并测试（`BackupScriptTest` 6 条）；**真实 MySQL 恢复演练待做** —— 这是唯一没打满分的一项，如实标 ⚠️ |

### 顺带修掉的真实缺口（不在原 P0 列表里）

- **chat 路径完全不记账**：会话路由用的是另一个自建客户端 `LlmChatClient`（不走 `LlmChatClient` 接口），
  它既没有指标也没有成本记录——而这是烧 token 最快的一条链路。已补 `purpose` / 记账 / 指标，
  并明确"会话路径默认不重试"（重试会让用户多等一个 timeout、并把熔断计数算歪）。
- **Prometheus 端点静默 404**：`@ConditionalOnEnabledMetricsExport` 判定 `management.defaults.metrics.export.enabled`
  为 false 时 registry 会退化成 `SimpleMeterRegistry`，`/actuator/prometheus` 直接 404 且启动无任何报错。
  已显式写死 true，并在测试里断言 registry 必须是 `PrometheusMeterRegistry`（防再次静默降级）。

### 测试口径

新增 55 条测试（1055 → **1110** 绿 0 skip）。所有"✅"都附了可执行的验证，不是"代码写了"。

## P1：SaaS 多租户（估 2-3 周）

8. RBAC + 租户隔离完整化（SessionScope → 租户上下文）
9. 限流（LLM 调用与写接口分级）
10. 审计流水（谁在什么时候改了什么）
11. 任务状态外置（删内存注册表 → DB 任务表）→ 多实例
12. 知识层三小件：quote 校验 / 语义近重 / 条目版本史
13. 部署级灰度（现在只有规则级）

## P2：打磨项

14. prompt cache 命中率度量、模型分级路由
15. 数据保留策略（会话/语料/审计的 TTL）
16. 多语言、产品文案统一

---

## 已知诚实边界

- 本清单的 ✅ 只认「测试/真实流量/grep 可查」，**未演过的能力不记 ✅**。
- 条目数 81 条（含 1 条明确不做），✅ 55 / ⚠️ 12 / ❌ 13。
- **仍未解决的硬缺口**（不是"没做"，是"做了也挡不住"）：
  - `9.7` 备份只在 dry-run 与语法层验证过，**没有在有 MySQL 的环境跑过一次真恢复**。
    这件事必须人做（见 `docs/backup-restore-sop.md` 第 3 节），不能由单测代替。
  - `8.5` 没有 tracing：一次请求跨规则解析→SQL→归并→比较→落库五层，出问题时只能靠日志时间戳拼时序。
  - `8.4` 只有 micrometer 指标，**没有采集端**（Prometheus/Grafana 是部署侧的事，本项目只负责暴露）。
  - `1.9` 任务注册表仍是进程内 Map，多实例部署依然不可行——这是 P1 里最靠前的一项。
  - `4.10` 采集管道健康指标仍缺：ingest 的解析失败/丢弃率没有计数，告警规则里也因此没有它。
- 与 2026-11-22 水位图的关系：水位图是主观综合分，本清单是它的论据底稿；两者冲突时以本清单为准。
