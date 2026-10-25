# 跨会话结论（B 方案）设计稿

> 目标读者：你。看完拍板再动手。
> 前置：A 方案（`〔当前有效〕` 会话内结论板）已落地，见 `claim-layer-report.md`。
> B 解决的是另一件事：**新会话**能不能用上**历史会话**里已经确认过的结论，以及多份结论打架时听谁的。

## 实施状态（2026-10-25，B1 已落地）

| 决定 | 落地情况 |
|---|---|
| v1 只看同操作人 | `recon_chat_claim` 增 `operator/scope/authority/rule_code/rule_version` 列，读时 `WHERE operator = ?` |
| 审批通过升级结论 | `RuleLifecycleService.approve` 升 ACTIVE 后调 `ChatClaimRepository.promote(...)`，写一条 `authority=APPROVED, session_id=0` 的行，值取自审批后的规则正文 |
| 冲突反问 | `ChatRuntime` 在执行类意图（RUN/BUILD_MODEL/DEFINE_RULE）前查同 scope 的两种说法；不同值就列出来问「第一个 / 第二个」，用户挑完记成 `CONFIRMED` |
| 复用表 | 不新建 `recon_shared_claim`，在 A 的 `recon_chat_claim` 上加列；A 是追加式，B 的「历史可查」天然满足 |

实测（确定性模式 + 真模型）：草稿确认后落 claim；两个会话分别说 `比 amount` / `比 status`，第三个会话要跑该规则时被反问，回「第二个」后记成已确认；把 `比 status` 的草稿审批生效后，同样的「跑一下」不再反问（已审批的排在前面，冲突消解）。

未做：`table:` / `global` 作用域目前没有生产者（只有 `rule:` 与 `draft:`）；跨会话结论只在 `rule:` 作用域注入提示词；`authority` 的第三级 `DRAFT` 尚未用于排序决策。

## 0. 一句话

按 `(操作人, 作用域, 语义键)` 建一张**追加式**结论表，读的时候按「权威等级 → 轮次」取当前值，
最多 3 行进提示词，标上来源会话与规则版本；**不做向量相似度去重**。

## 1. 要解决的问题（场景）

| # | 场景 | 现在会怎样 | B 之后应该怎样 |
|---|---|---|---|
| 1 | 昨天在会话 A 说「订单号要 18 位」，今天在会话 B 直接对账 | 完全不知道，模型按默认猜 | 提示词里有「订单号 18 位（昨天会话 A，未审批）」 |
| 2 | 会话 A 定了「按 order_no 比 amount」，会话 B 问「这规则按什么口径」 | 只能读规则正文（如果是草稿读不到） | 能答出「按 order_no 对齐、比 amount，来自会话 A 草稿 v1」 |
| 3 | 两个会话说法相反：A 说比 amount，B 说比 status | 随机 | 并列展示 + 标注各自来源 + 取最近；不静默合并 |
| 4 | 规则 v2 已生效取代 v1 | 可能把 v1 的旧口径当现行 | v1 结论降级为「历史」，只有问「以前怎么定的」才出 |

## 2. 关键判断（为什么不是权重/相似度）

1. **结论是短语义，不是长文本**：`bill_left` vs `bill_right` 字面相似但语义相反；用向量/相似度做去重或合并，
   迟早把「左表当右表」。所以按**结构化 key** 匹配，不做相似度聚合。
2. **权威性先于新鲜度，新鲜度先于相似度**：人工审批过的口径 > 用户在会话里确认过的 > 草稿里的；
   同级才比谁新。「权重」只是这条排序规则的另一个说法，不要单独引入浮点权重——没法解释。
3. **不猜**：抽不出结构化 key 的话，宁可不记（与 A 一致）。所以 B 只处理「规则/口径/表结构/全局约定」四类。
4. **追加式存储**：每次写入留一行，不覆盖历史——审计要查得到「谁在第几轮改的口」。

## 3. 数据模型

新表 `recon_shared_claim`（H2/MySQL 同构；追加写，不 UPDATE）：

```sql
CREATE TABLE recon_shared_claim (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator         VARCHAR(64)  NOT NULL,              -- 谁说的（隐私边界）
  scope            VARCHAR(96)  NOT NULL,              -- rule:ORDER_AMOUNT_RECON / table:bill_left / global
  claim_key        VARCHAR(96)  NOT NULL,              -- key / compare / amount_column / note
  claim_value      VARCHAR(255) NOT NULL,
  authority        VARCHAR(24)  NOT NULL,              -- HUMAN_APPROVED / OPERATOR_STATED / DRAFT_PENDING
  source_session_id BIGINT      NULL,
  source_seq       INT          NOT NULL,
  rule_code        VARCHAR(64)  NULL,
  rule_version     INT          NULL,
  dataset_version  VARCHAR(64)  NULL,
  created_at       TIMESTAMP    NOT NULL
);
CREATE INDEX idx_shared_claim_lookup ON recon_shared_claim (operator, scope, claim_key, created_at);
```

- 不加唯一键：同 key 的多次修改都留着，读时取当前值。
- `authority` 由写入方决定，不由模型决定（见 §5）。
- 量级估算：一次会话最多几十条，十年也就百万级，不需要分区。

## 4. 权威等级与冲突规则

| 等级 | 来源 | 说明 |
|---|---|---|
| `HUMAN_APPROVED` | 规则版本经审批变 ACTIVE 时，从规则正文抽 `key/compare/dimension` 写入 | 最高；带 `rule_version` |
| `OPERATOR_STATED` | 用户在会话里确认落库的草稿参数、或明确回答的结构化事实 | 中；带 `source_session_id/seq` |
| `DRAFT_PENDING` | 草稿已落库但未审批 | 低；被审批覆盖后自动失效 |

读时排序：`authority` 高者优先；同级比 `source_seq`（跨会话用 `created_at` 兜底）。
**顶级两条值不同且同级** → 标 `CONFLICT`，渲染成：

```
〔历史结论〕
· ORDER_AMOUNT_RECON：按 order_no 比 amount（会话 12，草稿 v1）
· 注意：会话 15 说法不同——比 status（未审批，取最近：amount）
```

规则正文本身（YAML）永远以 `RuleCatalog` 为准，结论表只是「人话解释 + 未落库的约定」，
两者冲突时提示词里标注「规则正文优先」。

## 5. 写入路径

| 触发点 | 写什么 | 等级 |
|---|---|---|
| 草稿确认落库（`ReconMcpTools.draft` 返回） | `rule:<code>` 的 `key/compare/dimension` | `OPERATOR_STATED` |
| 规则审批通过（`RuleLifecycleService.approve`） | 同上，取自最终正文 | `HUMAN_APPROVED` |
| 用户回答结构化事实（未来：`答: bill_left.amount 是金额列`） | `table:bill_left` 的 `amount_column` | `OPERATOR_STATED` |
| 规则被归档/取代 | 不写新行；读时按 `rule_version` 判定旧结论为历史 | — |

幂等：同 `(operator, scope, claim_key, claim_value, authority)` 且 `source_seq` 相同则跳过（防重放）。

## 6. 检索与注入

**新会话每轮**（`ChatRuntime.composeContext` 内，与 A 的结论板并列）：

1. 从当前状态推作用域：`call.ruleCode` → `rule:<code>`；`slots.table` → `table:<table>`；起草目标表 → 两个 `table:`；
2. 取同操作人、同作用域的候选（每轮一次 SQL，索引命中，毫秒级）；
3. 过滤：与当前会话 A 结论板同 key 且同值 → 跳过（避免重复）；旧版本 → 标「历史」；
4. 排序取前 3 行，总字符 ≤ 300；
5. 渲染：

```
〔历史已确认（同一操作人；当前会话的说法优先）〕
· bill_left：金额列是 amount（会话 3，未审批）
· ORDER_AMOUNT_RECON：按 order_no 比 amount（会话 12，v1 草稿）
```

**与 A 的关系**：A 是当前会话的结论，永远压过 B；B 只补「当前会话没提到、但历史定过」的空白。
两者都进 `fitContext` 的同一段（B 也在 `〔当前有效〕` 之后，一起受「不裁剪」保护）。

## 7. 可见性与隐私

- v1 只看**同一操作人**的结论；跨操作人要等 `SHARED` 会话语义明确后再开。
- 会话 title/内容不跨操作人泄漏；结论行只带会话号和草稿版本，不带原文。
- 开关 `recon.chat.shared-claims.enabled`，默认 **false**（先观察，不默认影响提示词）。

## 8. 观察口与 API

- 扩展现有：`GET /api/chat/sessions/{id}/claims` 返回增加 `shared` 段与 `conflict` 标记。
- 新增调试口：`GET /api/chat/sessions/{id}/context-preview`（只读）返回本轮真正进提示词的
  `summary + current claims + shared claims` 全文，便于对账「模型到底看到了什么」。

## 9. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| B1 | 表 + 两条写入路径（草稿确认、审批通过）+ 读取 + 注入（默认关） | 同操作人新会话能看到历史结论；提示词 ≤3 行；全量测试绿 |
| B2 | 冲突标记与并列渲染 + `/claims` 扩展 + 页面显示 | 两说相反时并列且取最近，不静默合并 |
| B3 | 版本失效（规则版本/数据集版本）+ 「历史」降级 | 旧版结论不冒充现行 |
| B4 | 跨操作人（需 SHARED 语义）+ 命中/采纳统计 | 隐私测试：alice 结论不进 bob 提示词 |

## 10. 测试计划

1. `SharedClaimRepositoryTest`：追加、按权威排序、同级取最近、幂等。
2. `SharedClaimsTest`（渲染）：3 行上限、冲突并列、历史降级、与当前会话结论去重。
3. `ChatRuntimeTest`：新会话里问「上次那个口径」→ 提示词含历史结论；当前会话改口后 → 当前会话压过历史。
4. 隐私：alice 写的结论，bob 的 context-preview 不含。
5. 回归：摘要保持率脚本加 3 条跨会话探针（历史结论必须出现在摘要/提示词里）。

## 11. 需要你拍板的四个点

1. **跨操作人**：v1 只同人，可以吗？（跨人要先把「会话 SHARED」的语义定清楚）
2. **审批通过自动升级**：规则 ACTIVE 时自动把草稿结论升为 `HUMAN_APPROVED`，接受吗？
   （风险：它可能压过用户后来在聊天里改的口——建议：只在「规则版本更新」那一刻写，且带版本号，不反复升）
3. **冲突处理**：并列 + 取最近（默认），还是直接反问用户「上次说 A，这次按 B，确认吗？」
4. **存储**：新建 `recon_shared_claim`（提议），还是给 `recon_chat_claim` 加 `operator/scope` 复用一张表？

拍板后我按 B1 → B2 实现，先默认关着，用 context-preview 对着真实会话校准一两天再打开。
