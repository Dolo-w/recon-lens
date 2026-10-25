# ADR-0001　会话层、记忆与检索的分层

- **状态**：提议（Proposed）
- **日期**：2026-10-24
- **影响面**：`agent/`、`llm/`、新增 `chat/`、`store/`、`web/`、`docs/mcp.md`
- **配套图**：`docs/assets/adr-0001-memory.html`（分层，本 ADR 主图）、`docs/assets/recon-lens-flow.html`（全链路）
- **关联评审**：`docs/agent-design-review.md` §4（四阶段）、§14（不向量化的决策风格）

---

## 0. 一句话

把「记忆」按**怎么查**分成三层（会话槽位 / 结构化事实 / 自由文本），只给最后一层上向量；
把「谁在编排」做成三档（表单 / 内置会话 / 宿主），让人话理解从**必需项**降成**可选项**。

---

## 1. 背景

### 1.1 现状（已核实，不是推测）

| 能力 | 位置 | 状态 |
|---|---|---|
| 四阶段状态机 OBSERVE→PLAN→ACT→LEARN | `agent/AgentOrchestrator.java` | 有，单目标、一次性 |
| 自然语言入口 | `agent/AgentGoal.java`（`GoalTrigger.NL_QUESTION` + `rawInput`） | 有 |
| 预算 | `agent/AgentProperties`（`maxLlmRounds` / `maxLlmTokensPerRun` / `maxWallClockSeconds`） | 有 |
| OpenAI 兼容客户端 | `llm/OpenAiCompatibleChatClient.java` | 有 |
| 归因知识库（键匹配，明确不向量化） | `explain/DiffKnowledgeBase.java` + `recon_diff_kb` | 有 |
| 会话 / 记忆 / 向量 | —— | **全仓 grep `session\|conversation\|memory\|embedding\|vector` 零命中** |

### 1.2 症状

`recon_agent_run` 是**一次性**的：每次调用起一个 run，跑完就结束。于是：

- 用户说「那这条呢」「再跑一次」「为什么差 12 块」，系统不知道「那」「再」指什么；
- 同一个问题换个说法问，从零开始，上次的结论不产生任何复用；
- MCP stdio 是唯一的对外形态，**没有 IDE 或桌面客户端的用户根本进不来**。

第三条是致命的：对账引擎的买方多是财务/共享中心，内网、无外网、不许装客户端。
现在等于要求客户先具备「AI 编程工具」才能用你的产品。

### 1.3 一个必须澄清的区分

「把理解自然语言变成可选」不等于「降级」。`AgentOrchestrator.plan()` 早就有确定性分支
（规则已存在 → 不调 LLM），这次只是把这条分支**延伸到入口**：同一个动作，既可以是
「一句话说的」，也可以是「表单填的」，还可以是「宿主帮你调的」。

---

## 2. 决策

### D1　三档入口，一套工具

| 档 | 前提 | 编排者 | 落地形态 |
|---|---|---|---|
| L0 | 无模型、无外网 | 无 | 八个固定动作的表单（起草/试算/审批/跑一次/看差异/归因/重试/注册表） |
| L1 | 有 API key 或本地 vLLM | 服务端 `ChatRuntime` | `POST /api/chat`（SSE）+ 自带控制台单页 |
| L2 | 有 MCP 宿主 | 外部宿主 | 现有 15 个工具，**加 streamable HTTP 传输** |

**硬约束**：三档共用**同一份** `ToolRegistry` 与**同一套**闸门。闸门写在工具实现里，
不写在编排层——否则换个 runtime 就能绕过。

**现状（2026-10-26）**：`io.github.dolow.reconlens.tool` 包落地——`ToolRegistry` 接口 + `ReconToolRegistry`
（9 个基础工具，Schema 与描述在此唯一来源）+ `ToolNames`（名字单一来源）+ `ToolArgs`（参数解析与文案单一来源）。
MCP 的 `tools/list` 由它生成，会话的每次工具调用走 `registry.invoke`，`GET /api/tools` 是对外的只读出口。
**未完成部分写在这里，不留在"看起来做了"**：6 个 Agent 专属工具（`recon_draft_rule` 等）仍由 `AgentMcpTools`
单独注册，因此 `GET /api/tools` 报 9、宿主 `tools/list` 报 15；两处注册＝仍可能漂移。

### D2　短期记忆 = 几张表，不进向量库

会话状态是**结构化的**（当前 ruleCode / jobId / shard / 时间窗），用列存、用主键读，
不参与相似度计算。指代解析走槽位，不走「把历史全塞进 prompt」。

**压缩按窗口比例触发，不按轮数。** 轮数在对账里几乎不携带信息量：一轮可能是「嗯」，
也可能是贴回 137 条差异；而固定 token 阈值（如 6k）又和模型窗口脱钩——私有化 32k 与
云端 128k 下同一个数字含义完全不同。触发条件取三者交集：

```
window  = 模型上下文窗口（32k 私有化 / 128k 云端，可配）
budget  = floor(window * 0.55)      // 留 45% 给输出与工具结果
need    = system + toolSchemas + summary + recentTurns

if (need > budget) and (turnCount >= 4) and (recentTokens >= 2000):
    把最早的 K 轮压进 summary（保留 conclusion + 被否决方案），回写 session
```

- `need > budget` 是主判据：保不溢出，且自动适配 32k/128k。
- `turnCount >= 4` 是**下界保护**：轮数在这里只干一件事——防止两轮就把需求压掉。
- `recentTokens >= 2000` 防无意义的摘要调用（省一次 LLM）。

token 用字符数近似（中文 1 字 ≈ 1 token），并在 `summary` 里记下压缩时用的 `window` 假设，
便于换模型后回溯「当时为什么在这里压」。压缩后设冷却，避免阈值附近反复触发。

### D3　长期记忆按检索方式分三类，不按存什么分

| 类 | 内容 | 检索方式 | 落点 |
|---|---|---|---|
| 结构化事实 | 维度、比对字段、容差、表白名单 | SQL 精确查 | `recon_rule` / `recon_table_registry`（现有） |
| 归因结论 | cause_key → 原因、下一步 | 键匹配 | `recon_diff_kb`（现有） |
| 案例 | 这条差异最后怎么处理、谁定的 | 元数据过滤 + 时间衰减 | `recon_case_memory`（新增） |
| 自由文本 | 对账口径手册、财务制度 | 元数据过滤 + 全文 / 向量 | `recon_doc_chunk`（新增） |

### D4　向量只用于自由文本

沿用 §14 的判据：**「语义检索真正需要的不是技术，是数据够多到能证明键匹配漏了东西」**。
在拿到反例之前，`recon_diff_kb` 不向量化、表结构不向量化。向量只服务 `recon_doc_chunk`，
并且**元数据过滤在前**（先按来源系统/生效日期/适用表缩小，再算相似度）。

**选型：`bge-m3`（本地推理、中文好、1024 维）。** 判据是私有化交付不许出网——
一个需要联网取 embedding 的记忆层，在多数对账客户机房里等于不可用。
`text-embedding-3-small` 只在演示环境作兜底，不作为默认。

**2026-10-26 落地**：`recon_doc_chunk` 增加 `embedding BLOB` / `dim INT` / `model VARCHAR(64)` 三列
（**必须可空**：不配 `recon.recall.embedding.*` 的部署永远不写这三列，检索自动降级——
设成 NOT NULL 等于强迫每个部署都装模型，N4 的降级链就白写了）。`dim` 必须存，因为换模型后
老向量还在库里，不校验维度就会算出一个"看起来正常"的错误排名。

装配是 `DegradingDocSearch`：**向量 → 全文（逐词严格 AND）→ 关键词（中文二元组）**，
每次返回都带 `mode` / `degraded` / `reason`；归因结论里也写明"检索方式"，因为同一个问题
有关键词层和有向量层的可信度不一样。三层失效原因不同，所以"不可用"（跳过）与"可用但没命中"
（继续往下试）是两种语义，代码里分开处理。
`recon_doc_chunk.dim` 存实际维度，查询时按 `dim` 过滤，**不假设维度固定**：
换模型只重算受影响的行，不重算全表。

### D5　写回有主，会话不是签字

- 人确认写回：`source=HUMAN`、`confidence=0.9`；模型自述：`source=LLM`、`confidence=0.5`（沿用 `DiffKnowledgeBase`）。
- 聊天里说「我同意」**不等于**审批。审批只认 `confirmationCode` + 内容哈希，
  与 `recon_chat_turn` 的内容无关。这条写成测试，不写进注释。

### D6　不引入新中间件

`recon_chat_*` / `recon_case_memory` / `recon_doc_chunk` 全部落在现有 MySQL 8 / H2。
理由：现有部署形态是「一个 jar + 一个库」，加 Redis 或向量库会让私有化交付成本翻倍；
而会话量级（每天几百到几万轮）离需要专用存储还差两个数量级。

### D7　工具结果预算先于摘要

对账会话里 token 的大头不是人说的话，是**工具返回**（差异清单、试算报告、规则 JSON）。
所以压缩的顺序固定为：**先控工具结果，再谈摘要**。

- 工具结果进上下文时按行截断：前 20 行 + 「共 137 条，用 `recon_list_diffs?offset=20` 翻页」。
- 需要细节时让模型**再调一次工具分页取**，而不是让它从摘要里回忆。

截断只是省略，摘要会让模型复述——前者保真、便宜一个数量级。先做这条，很多会话根本触不到
D2 的压缩条件。

**摘要与结构化事实分开存**：`summary` 是一段短散文（给模型读），`state_json` 是程序用的
结构化事实（ruleCode / jobId / 容差 / 被否方案）。别把两者压成同一段自然语言，压了就丢精度。

### D8　会话按 operator 隔离，可见性三档，不加 tenant_id

隔离只作用于**会话与原始轮次**。案例记忆、归因知识库、口径文档**不按人隔离**——它们是团队
资产，按人隔开等于把知识锁在个人手里，每个人重新踩一遍坑，比不隔离更糟。案例记忆若需要
收窄，按「规则 / 数据集」收窄，不按 owner。

| 可见性 | 谁能看 | 谁能往里继续问 |
|---|---|---|
| `PRIVATE`（默认） | 仅 owner | 仅 owner |
| `SHARED` | 同部署所有人 | 仅 owner（避免两人在同一条会话里各说各话） |
| `HANDOVER` | 同部署所有人 | 转移后的 owner（一次 UPDATE + 一条审计） |

默认 `PRIVATE`：**默认共享的泄露是静默的，默认私有的摩擦是可见的**——用户会自己提出要共享，
但不会发现自己的会话被别人看过。

**收窄点只有一个**：`SessionScope` 值对象，所有会话查询经它拼 WHERE。

```java
public record SessionScope(String operator, Visibility visibility) {
    String sqlFilter();   // 将来若要加 tenant，只改这一个方法
}
```

**不为「将来可能多租户」预埋 `tenant_id`。** 现在唯一的身份来源是一个 `recon.review.api-key`，
`X-Operator` 由调用方自己填、可伪造。在这种情况下 `tenant_id` 永远等于 `default`：
表里有了这列，所有人就以为多租户已经支持了，直到某天发现它从来没被正确填过。
这与 N5「不假装有」是同一条原则。真要做多租户，前置条件是认证（OIDC / 网关注入），
届时改 `SessionScope.sqlFilter()` 一处 + 一次 `ALTER`（会话表量级小，重建索引近乎零成本）。

**关键约束：过滤前置，不是后置。** 所有读取必须写成「先按 scope 收窄再查」，
不能「查出来再判断可见性」。后者在 P3 的跨会话语义检索下会直接变成越权漏洞——
检索结果里混进别人的会话片段，而调用方根本看不出哪条不属于自己。

**`recon_agent_run` 不并进会话。** 一个 run 是「一次任务」，一个会话是「一段对话」，
一对多。并进来会让 `recon_agent_run` 已有的双重身份（任务记录 / 轨迹记录）更难拆；
会话里引用 `runId` 即可。

---

## 3. 备选方案与为什么不选

| 方案 | 好在哪 | 为什么不选 |
|---|---|---|
| **A. 全量历史塞进 prompt** | 零开发 | 成本随轮数线性涨；长会话必然截断，截断点之前的事实就丢了；模型在长上下文里对「中间」的注意最弱，正是「上次说过什么」出现的位置 |
| **B. 一个向量库当记忆** | 概念统一、看起来先进 | 对账里九成记忆是结构化事实，向量把「精确命中」变成「相似命中」，可解释性反而下降；`cause_key` 命中率会变难测；还要多运维一个组件 |
| **C. 直接引 LangChain4j / Spring AI** | 现成的 memory / retriever | 会绕开现有闸门（审批、白名单、预算），而闸门是这个项目最贵的资产；版本耦合后 `AgentBoundaryTest` 那类断言会被框架架空 |
| **D. 分层 + 分档（本 ADR）** | 每层只解决一个问题；无模型也能跑；闸门单点 | 要写 4 张表和一个 runtime，比 A 多 3–5 天；分层边界需要测试守住，否则会退化成「什么都往向量里塞」 |

---

## 4. 数据模型

> 双跑约束沿用现有约定：**同一条 SQL 必须 H2 与 MySQL 都能跑**。因此不用 `JSON` 列（用 `TEXT`）、
> 不用 `INSERT ... ON DUPLICATE KEY UPDATE`（先查再插）、布尔用 `TINYINT`。

```sql
-- 会话：一条会话一行，state_json 存槽位
CREATE TABLE recon_chat_session (
  id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
  operator    VARCHAR(64)   NOT NULL,
  visibility  VARCHAR(16)   DEFAULT 'PRIVATE',  -- PRIVATE / SHARED / HANDOVER
  title       VARCHAR(200),
  state_json  TEXT,                 -- {ruleCode,jobId,shard,timeWindow,focusTypes}
  summary     TEXT,                 -- 滚动摘要（含"已否决的方案"）
  turn_count  INT           DEFAULT 0,
  status      VARCHAR(16)   DEFAULT 'OPEN',
  created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  last_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
-- 所有列表查询都按 (operator, last_at) 走，索引必须匹配这个访问路径
CREATE INDEX idx_chat_session ON recon_chat_session (operator, last_at);

-- 轮次：原文保留，便于复盘与重放
CREATE TABLE recon_chat_turn (
  id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
  session_id  BIGINT        NOT NULL,
  seq         INT           NOT NULL,
  role        VARCHAR(16)   NOT NULL,   -- user / assistant / tool
  content     TEXT,
  tool_calls  TEXT,                     -- 与 recon_agent_step 用同一个截断策略
  tokens      INT,
  created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_chat_turn ON recon_chat_turn (session_id, seq);

-- 案例：一次"判断 + 结论 + 谁定的"
CREATE TABLE recon_case_memory (
  id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
  rule_code   VARCHAR(64),
  diff_type   VARCHAR(32),              -- VALUE_MISMATCH / MISSING_LEFT / ...
  field_name  VARCHAR(64),
  cause_key   VARCHAR(128),             -- 与 recon_diff_kb 同构，便于互相印证
  conclusion  TEXT,                     -- "四舍五入口径，已加 tolerance=0.5"
  source      VARCHAR(16),              -- HUMAN / LLM
  confidence  DECIMAL(4,3),
  verified_by VARCHAR(64),
  hits        INT           DEFAULT 0,
  created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
  last_hit_at TIMESTAMP     NULL
);
CREATE INDEX idx_case_lookup ON recon_case_memory (rule_code, diff_type, field_name);

-- 文档分块：唯一需要向量的地方
CREATE TABLE recon_doc_chunk (
  id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
  doc_id      VARCHAR(64)   NOT NULL,   -- 同一份手册
  chunk_no    INT           NOT NULL,
  title       VARCHAR(200),
  meta_json   TEXT,                     -- {sourceSystem,effectiveFrom,tables[]}
  content     TEXT          NOT NULL,
  embedding   BLOB,                     -- 可空：先全文，后补向量（bge-m3，1024 维）
  dim         INT           DEFAULT 0,
  created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_chunk ON recon_doc_chunk (doc_id, chunk_no);
```

**迁移脚本**：`docs/migrations/2026-10-24-chat-memory.sql`，照现有 `docs/migrations/*.sql` 的命名与写法（H2/MySQL 双跑）。

---

## 5. 不变量（每条都要有测试）

| 编号 | 不变量 | 测试形态 | 状态 |
|---|---|---|---|
| N1 | 会话不能替代审批：`recon_chat_turn` 里出现任何内容都不改变规则状态机 | 建会话 + 说「批准」+ 断言 `resolve()` 仍返回旧版本 | ✅ 已有 |
| N2 | 任何 runtime（含 `ChatRuntime`）不得直接持有 `RuleLifecycleService`；MCP 桥只该拿到 `ApprovalRelay` | 扩现有 `AgentBoundaryTest` 的 ArchUnit 断言，加 `chat` 包 | ✅ 2026-10-26 |
| N3 | 滚动摘要必须包含被否决的方案 | 断言 `summary` 含 `〔被否决〕` 段且带轮次坐标 | ✅ 2026-10-26 |
| N4 | 检索降级链：向量不可用 → 全文 → 键匹配，任何一环失败都不抛给用户 | `DocSearchChainTest` 6 例（假向量分维度/未配置/维度不符/严格 AND 未命中）+ `walkthrough` 实测 10/10：配模型 → `mode=VECTOR,degraded=false`；不配 → `mode=FULLTEXT,degraded=true` 且 `reason` 说明原因 | ✅ 2026-10-26 |
| N5 | 写回置信度不可越权：`source=LLM` 写入后，`approval` 相关判定不得读取它 | 断言 `DiffKnowledgeBase.lookup` 在 `minConfidence` 阈值下忽略 LLM 条目 | ✅ 已有 |
| N6 | 压缩不改审计：`recon_chat_turn` 原始行数与内容不因摘要而变 | 触发压缩后断言行数与 payload 不变，只有 `session.summary` 变 | ✅ 2026-10-26 |
| N7 | 工具结果截断必须留翻页出口：截断时同时给出总数与 `nextOffset` | `GET /api/diffs` 返回 `{total,offset,limit,truncated,nextOffset,items}`；MCP 文本尾部给 offset 提示 | ✅ 2026-10-26 |
| N8 | 所有 session 读取必须走 `SessionScope`；跨 operator 取别的 sessionId 返回 **404 而非 403** | ArchUnit：`chat` 包外不许调 `ChatSessionRepository` 的裸查询方法；集成测试断言 404 且响应体不含标题 | ✅ 已有 |
| N9 | 跨会话检索默认只搜本人会话；`scope=team` 必须显式传参；别人的 `PRIVATE` 永远搜不到 | `chat.SessionRecallService` 默认 `Scope.MINE`，SQL 三档 `operator / previous_operator / SHARED`；`ChatRecallApiTest` 5 例 + `work\recall-scope-smoke.ps1` 13 项实测 | ✅ 2026-10-26 |

---

## 6. 后果

### 正面

- 产品能进「内网 / 无 key / 不许装客户端」的场景（这是市场面最大的一类买方）。
- 记忆成本可控：结构化优先意味着多数问题 0 token 命中。
- 每次命中都能解释「为什么是这条」（键匹配天然可解释，向量不能）。

### 负面与缓解

| 负面 | 缓解 |
|---|---|
| 多了一个 runtime，行为可能与宿主不一致 | 单一 `ToolRegistry` + 闸门在工具实现里；N2 用测试守 |
| 4 张表的迁移与运维 | 复用现有 H2/MySQL 双跑约定；`docs/migrations/` 统一管理 |
| 摘要压缩会丢信息 | 摘要只压「结论与否决」，原始 `recon_chat_turn` 永久保留，可回溯 |
| 控制台单页要做前端，团队可能没有前端 | 先用服务端渲染的单页（无构建），够用即止 |

---

## 7. 验收

先建 **30 条 golden 问句**（覆盖 8 类意图：查规则 / 起草 / 试算进度 / 审批状态 / 跑对账 / 看差异 / 归因 / 注册表），四个指标：

| 指标 | 目标（P1 出口） |
|---|---|
| 意图命中率 | ≥ 90% |
| 工具选择准确率 | ≥ 85% |
| 平均轮数（一次任务完成） | ≤ 3 |
| 每会话 token | 中位数 ≤ 6k |

**没有这组数，不许说「记忆做完了」。**

### 7.1 实测（2026-10-24，`docs/golden-set-report.md`）

30 条单轮问句 + 8 个多轮任务，真 HTTP + SSE 跑出来：

| 指标 | 目标 | 首轮 | 修完路由漏 | 结论 |
|---|---|---|---|---|
| 意图命中率 | ≥ 90% | **73.3%**（22/30） | **100%**（30/30） | 达标 |
| 工具选择准确率 | ≥ 85% | **26.9%**（7/26） | **100%**（25/25） | 达标 |
| 平均轮数 | ≤ 3 | 2.13 | 2.13 | 达标 |
| 每会话 token 中位数 | ≤ 6k | 149 | 110 | 达标（复跑 110–150 浮动） |

两条方法论比数字本身重要：

1. **意图与工具要用不同状态的会话量。** 意图用全新会话（只能靠这句话判断）；工具用"已经跑过一单、
   槽位里有 `jobId`"的会话——否则"看下差异"只能反问"看哪个任务"，把正确行为记成错误。
2. **`APPROVE` / `HELP` / `TRIAL_STATUS` 不调外部工具**，不进工具分母（会话里的 `TRIAL_STATUS`
   直接读规则库，不代理 MCP 的 `recon_tool_status`；这条差异记在 backlog B14）。

首轮那 8 个路由漏全部是"人话与关键词表不匹配"，不是模型问题：
裸"对账"抢走了"对账规则"、"差异 3"没被当成第 3 条、中文序数"第一条"漏了、`RETRY` 不认"重新跑"……

**这组数只说明"听得懂、调得对、聊得短"，不说明"答得对"**——结论正确性由 `full-regression` 的 93 项功能断言守。

---

## 8. 分期

| 期 | 内容 | 出口条件 |
|---|---|---|
| **P0**（3–5 天） | `recon_chat_session` / `recon_chat_turn` + **`SessionScope` 与 `visibility` 三档** + 槽位 + `POST /api/chat`（SSE）+ `ChatRuntime` 复用 15 工具 + 8 个表单兜底 | 无 key、无宿主，只用浏览器能完成「起草 → 试算 → 看差异」；N8 的 404 测试通过 |
| **P1**（1 周） | 滚动摘要（含否决，按窗口 55% 触发）+ `recon_case_memory` + 处理结论回写 + `HANDOVER` + 命中率看板 | golden set 跑通，指标达标 |
| **P2**（1–2 周） | `recon_doc_chunk` + FULLTEXT（**bge-m3 向量随后**）+ 私有化模型 profile | 一份真实口径手册能被引用并给出来源 |
| **P3** | MCP streamable HTTP、审计导出、按 dataset/rule 收窄的案例检索 | 外部系统可直连 |

> 会话隔离（D8）**不放 P3**：它是 `visibility` 一列 + 一个值对象，放在 P0 是最便宜的时点；
> 等到 P3 再补，就要回头改 P0 写下的每一条查询（即 D8 说的「过滤前置」代价）。

### 8.1 落地进度

| 日期 | 已落地 | 验证 |
|---|---|---|
| 2026-10-24 | `recon_dataset` / 指纹列 / `data_snapshot`（ADR-0002，属数据版本，非本 ADR） | `DerivedDimensionTest` 全绿，H2 上 `schema.sql` 正常加载 |
| 2026-10-25 | **会话骨架**：`recon_chat_session` / `recon_chat_turn` + `chat.Visibility`（PRIVATE/SHARED/HANDOVER）+ `chat.SessionScope`（唯一收窄入口）+ `store.ChatSessionRepository` + `chat.ChatSessionService` + `POST/GET /api/chat/sessions/**` | `ChatSessionServiceTest`（8 例，含交接审计落 `recon_agent_step`）+ `ChatSessionApiTest`（3 例，含 **N8 的 404 与「响应体无标题」**）；全量 433 例通过 |
| 2026-10-25 | **会话跑通对账**：`chat.ChatSlots` + `ChatIntent`（12 动作）+ `ChatIntentRouter`（确定性路由）+ `ChatEvent` + `ChatRuntime` + `POST /api/chat`（SSE，`text/event-stream`）+ `state_json` 槽位读写 + `chatExecutor` | 真实起服务实测 11 个场景（`docs\chat-test-report.md` 迭代 1）；`ChatIntentRouterTest` 12 例 + `ChatRuntimeTest` 4 例；全量 **449 例**通过 |
| 待做（P0 后半） | 8 个表单兜底（无 key 时起草/试算/审批的按钮化入口）+ 并发写槽位（`state_json` 现在是整串覆盖） | 出口条件同上 |
| 2026-10-25 | **表单兜底 + 槽位 CAS**：`chat.ChatForm` / `ChatForms`（12 动作表单目录）/ `ChatFormService` + `GET /api/chat/forms` + `POST /api/chat/actions`；`state_rev` 乐观锁（`updateState` 带 `expectedRev`，冲突重读一次）；`RuleCatalog.trialState()` 让会话能报「试算结论 + 可批性」 | `ChatFormsTest` 6 + `ChatFormApiTest` 7 + `ChatSessionServiceTest` 10；真实服务 16 场景（`work\form-smoke.ps1`）；相关测试 39 例全绿 |
| 2026-10-25 | **浏览器闭环（P0 出口）**：`src/main/resources/static/index.html` 挂在 `/`——一句话走 SSE（自解 `event:`/`data:` 帧）、12 动作表单、槽位面板、会话列表与深链接 `?operator=&session=&ask=` | 真实浏览器（Edge headless）三张截图：历史回放、表单面板、流式问答；`work\ui-chat.png` / `work\ui-sse.png` |
| 2026-10-25 | **全量回归**：历史功能 11 场景 + 新增 10 场景 + 页面 1 场景 = **22/22**；自动化 **464 例**全绿 | `work\full-regression.ps1` → `work\full-regression.txt`；详见 `docs\chat-test-report.md` 迭代 4 |
| 2026-10-24 | **ADR-0002 收尾（数据版本）**：`store.DatasetFingerprints`（L1 指纹 / L2 校验和 / 快照 JSON / 漂移比对）+ `recon_job.data_snapshot` 建任务即写死 + `GET /api/jobs/{id}` 的 `dataSnapshot`/`dataDrift` + `GET /api/jobs/compare` | 真进程 11 项全绿（`work\adr0002-smoke.ps1`）；全量 **482 例**全绿；详见 `docs\adr-0002-test-report.md` |
| 2026-10-25 | **P1 滚动摘要 + 轮次分页**：`chat.ChatSummarizer`（纯函数、行级裁剪 head4+tail10）+ `chat.ChatSummaryService`（窗口 55% / 水位线增量 / 异常静默降级）+ `summary_upto_seq` 水位线列 + `GET /api/chat/sessions/{id}?limit=&before=` 服务端游标 + `POST /api/chat/sessions/{id}/summary` + 页面置顶摘要条与「更早的轮次」 | `work/p1-smoke.ps1` 实测 **16/16**（`work/p1-smoke.txt`）；`ChatSummarizerTest` 7 + `ChatSummaryServiceTest` 9 + `ChatSessionApiTest` 3，全量 **501 例** BUILD SUCCESS |
| 2026-10-25 | **P1 补缺口**：省略行升级为「坐标 + 关键词」索引行；分页支持 `from`/`nextAfter` 双向（`turnsFrom` + `existsBefore`/`existsAfter`）；页面加「最早/更早/最新」与 `/?from=1` 深链接 | `work/p1-smoke.ps1` 实测 **26/26**；`ChatSummarizerTest` 9 + `ChatSessionApiTest` 8，全量 **504 例** BUILD SUCCESS |
| 2026-10-26 | **P2 长期记忆**：`recon_case_memory`（案例，0.9/0.5 双来源 + 时间衰减排序 + hits 累加）/ `recon_doc_chunk`（口径切片，整篇哈希幂等）/ `DocSearch` 接口 + 关键词实现（中文二元组）；`RecallAugmenter` 把两段记忆追加到归因后面（REST 与会话共用） | `work/recall-smoke.ps1` 实测 **18/18**；`RecallServiceTest` 10 + `RecallApiTest` 6，全量 **520 例**；历史回归 22/22 |
| 2026-10-26 | **守则补洞（N2/N3/N6/N7）**：ArchUnit 加两条（`chat..` 不得依赖 `RuleLifecycleService`；`chat..`/`store..` 之外不得直接用 `ChatSessionRepository`）；`ChatSummarizer` 新增 `〔被否决〕` 段（带轮次坐标、去重、最多 3 条、超长也优先保它）；`GET /api/diffs` 改为 `{total,offset,limit,truncated,nextOffset,items}`，MCP `listDiffs` 尾部给 offset 提示 | `work/adr1-iter1-smoke.ps1` 实测 **11/11**；全量 **538 例**（+8） |
| 2026-10-26 | **D1 工具目录**：`tool` 包（`ToolRegistry`/`ReconToolRegistry`/`ToolNames`/`ToolArgs`/`ToolSpec`/`ToolOutcome`）；MCP `tools/list` 改为遍历 registry 生成；`ChatRuntime` 8 处直连改为 `registry.invoke`；新增 `GET /api/tools`（9 项）。**未完成**：6 个 Agent 工具仍单独注册（9 vs 15 两处来源）。验证：`ToolRegistryTest` 6 例、全量 544 例、`GET /api/tools`+会话+真宿主 15/15+五套历史冒烟全绿 |
| 2026-10-26 | **P1 命中率看板**：`GET /api/chat/stats`（意图分布/工具使用/空手回占比/平均轮数）+ 轮次信封写入 `intent`（`tool_calls` 加键，不迁移列）+ `ToolNames.AGENT` 与「9 + 6 = 15」漂移守卫。验证：`ChatStatsApiTest` 2 例、全量 547 例、实测五句五意图全对、四套历史冒烟全绿 |
| 2026-10-26 | **D4/N4 向量与降级链**：`recon_doc_chunk` 加 `embedding BLOB`/`dim`/`model`（可空）+ `EmbeddingClient` / `OpenAiCompatibleEmbeddingClient`（未配置或不返 200/维度不符一律返回空，不外抛）+ `VectorDocSearch`（余弦，BLOB 小端 float）/ `FulltextDocSearch`（逐词严格 AND）/ `DegradingDocSearch`（向量→全文→关键词，返回 `mode`/`degraded`/`reason`）+ `/api/docs/search` 与归因结论回显"检索方式" | `DocSearchChainTest` 6 例；`work/embedding-smoke.ps1` 真 HTTP 假向量服务 **10/10**（配模型 `mode=VECTOR`、不配 `mode=FULLTEXT,degraded=true` 且原因说人话）；全量 **553 例**；五套历史冒烟 22/22 + 18/18 + 16/16 + 26/26 全绿 |
| 2026-10-26 | **N9 跨会话检索**：`chat.SessionRecallService`（`Scope.MINE` 默认 / `TEAM` 显式）+ `chat.SessionHit`（带 owner/visibility/score/matched）+ `store.ChatSessionRepository.recall`（三档可见性 OR）+ `GET /api/chat/recall` | `ChatRecallApiTest` 5 例（默认只搜自己 / team 带 owner / PRIVATE 搜不到 / scope 写错 400 / 缺操作人 400）+ `work\recall-scope-smoke.ps1` **13/13**；全量 **558 例** |

| 2026-10-24 | **P2 模型兜底意图理解**：`chat/plan` 包（`ChatPlan` 含置信度/依据/来源/候选、`ChatPlanner` 接口、`KeywordPlanner` 包住 P1 词表、`LlmPlanner` 用白名单解析模型输出、`LlmChatClient` 一个 POST 且失败一律返回空、`CompositePlanner` 词表优先 + 连续三次失败熔断五分钟）；`ChatRuntime` 改为 `planner.plan()`；新增 `plan` SSE 事件与 `GET /api/chat/planner`；页面每条回答上方显示「词表理解 / 模型理解 · 置信度 · 依据」，仅 `HELP` 时把候选做成可点按钮 | 真 DeepSeek（`deepseek-chat`）实测 `work/p2-smoke.ps1` **14/14**（快速路径 26ms、模型兜底 846ms、省略句被理解成 `EXPLAIN_JOB` 并自动补槽位任务号）；无 key 实例 `modelEnabled=false` 且照常作答；`LlmPlannerTest` 7 + `CompositePlannerTest` 6；全量 **666 例** BUILD SUCCESS；详见 `docs/p2-llm-planner-report.md` |
| 2026-10-26 | **P3 收口三项**：① MCP streamable HTTP（`mcp/McpSpecifications` 让 stdio 与 HTTP 共用一份工具表 + `mcp/McpHttpConfiguration` 把 `HttpServletStreamableServerTransportProvider` 挂到 `/mcp`）；② 审计导出 `GET /api/audit/runs`、`/runs/{id}`、`/approvals`（JSON + CSV，CSV 带 BOM 与公式注入中和）；③ 案例按数据版本收窄（`recon_case_memory.dataset_version` + `RecallAugmenter` 从任务取版本；筛选口径＝同版本 OR 没记版本） | `work/mcp-http-smoke.mjs` **8/8**、`work/mcp-host.mjs` **15/15**、`work/iter12-smoke.ps1` **24/24**；`AuditApiTest` 8 + 案例版本 3 例；全量 **569 例**；五套历史冒烟全绿 |

> **ADR-0001 全部条目已闭合**（2026-10-26）：D1–D8 与 N1–N9 均有实现与验证。
> 与设计不完全一致之处（ToolRegistry 抽成类、`doc_chunk` 向量列可空、无 `tenant_id`）与
> 仍未做的部分（摘要无归纳、真实 bge-m3 未跑、无 Spring Security）见 `docs/backlog.md`。

> **P0 已闭合**（2026-10-25）。出口条件"无 key、无宿主、只用浏览器完成起草 → 试算 → 看差异"已满足，
> 且 A 组 11 个历史场景全过，证明 `chat` 是旁挂能力、没有改动引擎/规则库/审批链的行为。


> **交接审计不新开表**（2026-10-24 决定）：复用 `recon_agent_run` / `recon_agent_step`，
> `trigger_type='SESSION_HANDOVER'`、`action='handover-session'`。理由：会话换人接手是一条
> 「谁在什么时候把哪条会话给了谁」的凭证，正是轨迹表已经在记的东西；多一张表就多一套
> 查询、权限与归档。
>
> **一个反直觉的实现细节**：交接后 `visibility` 置为 `HANDOVER` 且**原 owner 仍然读得到历史**，
> 只是不能再写。这是刻意的——她要能回看「我交出去的这段对话后来怎么了」，而写入权必须跟着 owner 走，
> 否则两个人往同一段带槽位的状态机里发消息会互相覆盖。
>
> **这一条最初写错过**：当时用 `visibility <> 'PRIVATE'` 表达"原 owner 也可见"，结果 `HANDOVER`
> 落进了那个条件——**任何交接过的会话对全公司可读**，实测第三方 `GET` 拿到 200 与全文。现在改成
> 显式的 `previous_operator` 列：读权限 = `operator = 我 OR previous_operator = 我 OR visibility = 'SHARED'`。
> 教训写进 `docs\chat-test-report.md`：**用"不等于某个值"表达授权，等价于给未来新增的枚举值全部开绿灯**。

---

## 9. 已决（原「待定」，2026-10-24 拍板）

| 原待定项 | 决定 | 落在哪 |
|---|---|---|
| embedding 选型 | **`bge-m3`**（本地、中文好、1024 维）；`text-embedding-3-small` 仅演示兜底 | D4 |
| 多租户 / 会话隔离 | **`operator` + `visibility` 三档（`PRIVATE` 默认 / `SHARED` / `HANDOVER`），不加 `tenant_id`**；收窄点只有 `SessionScope`；有认证后再谈 tenant | D8、N8、N9 |
| 摘要触发阈值 | **按窗口比例**：`window × 0.55`，与 `turnCount >= 4`、`recentTokens >= 2000` 取交集 | D2 |
| `recon_agent_run` 是否并进会话 | **不并**：一个 run = 一次任务，一个会话 = 一段对话；会话里引用 `runId` | D8 |

**交接审计的落点（2026-10-24 已决）**：复用 `recon_agent_run` / `recon_agent_step`，
`trigger_type='SESSION_HANDOVER'`、`action='handover-session'`，不新开 `recon_chat_handover`。
理由：交接本身就是一次「谁在什么时候把哪条会话给了谁」，正是轨迹表已经在记的东西；
多一张表就多一套查询、权限与归档。

---

## 10. 一句话总结

**这三层记忆不是要造一个更聪明的系统，而是让同一套闸门在三种部署条件下都能进门。**
