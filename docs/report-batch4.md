# 批 4 实测报告：正确性（M12 检索分数 / M13 槽位并发 / M14 分片上界）

> 时间：2026-10-26 · 基线：批 3 之后（786 tests）· 结果：**794 tests, 0 failures**，三项全部按"实测数字"验收。
> 跑法见文末「复现」。下文所有数字都来自本轮真跑的接口返回，不是推算。

## 结论速览

| 项 | 最硬的一条证据 | 判定 |
|---|---|---|
| M12 检索分数归一化 | 同一句 `容差 分片`：融合 95/10/10，`sources=KEYWORD,FULLTEXT`；拆开看是关键词 35 + 全文 50 + 标题 10，**手工可核对** | ✅ |
| M13 槽位并发 | 4 轮 × 2 条并发写同一会话：`turnCount` +2/轮、`stateRev` +2/轮，**没有丢轮次也没有卡住的 rev** | ✅ |
| M14 分片上界 | 规则写 `shard.count: 300` → 闸门一给出 `SHARD_COUNT`，消息里带「300」「256」；`256` 通过、可配成 `8` 后 `9` 被拒 | ✅ |

---

## M12 · 三层分数不再"谁层大谁赢"

### 改了什么

`recall/DegradingDocSearch`：

1. **每层先 min-max 归一到 0~1，再乘层权重**（向量 ×1.0 / 关键词 ×0.35 / 全文 ×0.5），最后放大 100 倍取整。
   归一化的 **share** 同时决定"这条结果主要靠哪层"（`bestLayer`）。
2. **`Outcome` 加 `sources`**：最近入选的那几条结果**被哪些通道给出来过**。
   原来只有 `mode`，而 `mode` 是"排序赢家"这一件事——一条结果同时被关键词和全文命中时，
   `mode` 会把另一层悄悄吃掉，调用方据此判断"这次是不是语义检索"就会判错。
3. `mode` 语义收紧为「首条结果的赢家层」，`degraded = sources 不含 VECTOR`。
4. `titleBonus` 从「每个命中 +5、封顶 20」改成「+10、封顶 30」：归一化之后它是同量纲的 tie-break，
   不能比一层字面命中的分量还轻。
5. `web/RecallController` 把 `sources` 放进 `/api/docs/search` 的响应（原来只回 `mode/degraded/reason`）。

### 为什么权重从 0.7/0.3 改成 1.0/0.35

归一化之后每层的上限都是 1.0。若仍用 0.7/0.3/0.5，关键词 + 全文同时命中合计 0.8 > 0.7，
"字面双中"会压过"语义满分"，与「向量为主」相反。改成 1.0/0.35/0.5 后：
字面双中最高 85 < 语义满分 100。**85 与 100 的差就是这条设计约束的量化表达。**

### 实测（同一句 `q=容差 分片`，三篇口径文档，向量未配）

```
生产路径（自动融合）
mode=FULLTEXT degraded=True forced=False sources=KEYWORD,FULLTEXT
    score=95  汇率与容差换算
    score=10  对账容差与差异口径
    score=10  分片重跑手册

mode=keyword（强制只看关键词层）
    score=19  汇率与容差换算
    score=9   对账容差与差异口径
    score=9   分片重跑手册

mode=fulltext（强制只看全文层）
    score=99  汇率与容差换算
```

融合分可手工还原：`0.35×100（关键词层内最高）+ 0.5×100（全文层内最高）+ 10（标题命中）= 95`。
另外两篇在关键词层内 share=0（层内最低），所以只剩标题的 10 分——这正是"层内相对高低"被保住的证据：
旧实现下它们会一起落到同一个整数上。

### 残留（写清楚，不粉饰）

- **min-max 在小语料上很粗**：只有 3 篇时关键词层 19/9/9 被压成 35/0/0，
  中间那篇实际命中过却拿 0 分，靠标题加分 10 才没沉底。语料变大后这个偏差会摊薄，
  但"层内只有 3 个样本"这类场景值得在批 5 的评测集里单独测一次。
- `sources` 是"这批结果被哪些通道找到"，**不是**"每条结果分别靠哪层"；后者要看 `score` 的构成。
  如果以后 UI 要展示"这条是语义找到的"，得给每条 hit 单独加 `layer` 字段——本轮没做。

---

## M13 · 槽位写入不再静默丢更新

### 改了什么

- `ChatSlots#mergeWith(ChatSlots newer)`：**本轮写过的字段覆盖、其余取最新、`pending` 以本轮为准**。
- `ChatRuntime#saveSlotsWithRetry`：从「失败后拿旧 rev 再试一次」改成「最多 3 次，每次冲突都
  `sessions.require` 回读最新 `stateRev` 与 `stateJson`，用 `mergeWith` 合并后再写，最后一次仍失败才 WARN」。
  常量 `SLOT_SAVE_ATTEMPTS = 3`。

### 实测：4 轮 × 2 条并发，打同一个会话

```
round 1 : rev=3  turns=6
round 2 : rev=5  turns=10
round 3 : rev=7  turns=14
round 4 : rev=9  turns=18
```

每轮两条并行请求都落库：`turnCount` 每轮 +2（两轮对话），`stateRev` 每轮 +2（每次写入都成功），
没有 500、没有卡在同一个 rev 上。这正是修复目标——修复前第二次撞车会 WARN 并**丢掉这一轮槽位**，
但轮次照样 +2，从日志上看不出来。

### 字段级不丢，由测试钉死

`ConcurrentSlotWriteTest`（2 例，H2 + 真 `schema.sql`）：

1. 同一个 rev 的两次写入**只能成功一次**（第二次返回 false，rev +1）；
2. 冲突后 `mergeWith` 合并：对方刚写的 `jobId=7` 与我这一轮的 `ruleCode=R1` **同时存在**。

---

## M14 · 分片数上界进闸门一

### 改了什么

- `SafetyViolation.ViolationCode.SHARD_COUNT` 新增；
- `RuleSafetyValidator#checkShard` 开头先判 `shard.count > maxShardCount`；
- `AgentProperties.Safety.maxShardCount` 默认 **256**（可配，1~4096，越界抛 `IllegalArgumentException`）；
- `AgentConfiguration` 把配置传进 `RuleSafetyValidator`（三参/四参构造器仍委派到五参，老调用点不动）。

为什么这条重要：分片是**重跑单位**不是性能旋钮，且每片会 `DELETE` 本分片旧差异——
片数写崩了，重跑一个分片比跑全量还贵。

### 实测

`ShardCountLimitTest`（3 例）：

| 用例 | 结果 |
|---|---|
| `count: 300`（默认上限 256） | `SHARD_COUNT` 报错，`location=shard.count`，消息含 `300` 与 `256` |
| `count: 256` | 不报（边界值算通过） |
| 上限配成 `8`：`count: 8` / `count: 9` | 通过 / 被拒 |

**live 边界的实话**：走 stdio MCP 调 `recon_draft_rule` 时，起草任务被接受并返回
`{"runId":1,"status":"QUEUED"...}`，但 stdio 客户端每次调用都会新起一个进程（内存 H2），
进程一退任务就没了，**拿不到这轮的 violations**。所以 M14 的 live 证据只能是"闸门在这条链路上"，
真正的端到端要等批 5 把 draft 暴露到 REST 或 HTTP MCP 之后才能截到。

---

## 本轮新发现（不在原缺陷清单里）

1. **闸门一的覆盖面比文档里写的窄。** `RuleSafetyValidator` 的调用点只有两处：
   `RuleDrafter`（agent 起草）与 `ShadowTrialService`（影子试算）。也就是说
   `recon_define_rule` 与聊天里的 `DEFINE_RULE` **不经过安全校验器**——
   `shard.count` 上限、容差放宽、写关键字、分片键一致性这些检查，对"直接定义规则"这条路是空的。
   这要么是把 define 也接上校验器，要么是文档明确写"define 是闸门 0，只做结构校验"。→ 记入批 5/6 待定。
2. `mode` 与 `sources` 的分工原来没有暴露（API 只回 `mode`），运维看到 `degraded=true` 却看不到"到底谁给了结果"。
3. `DocChunkRepository` 在 `DegradingDocSearch` 构造里是被注入但只用于 `searchForced` 的分支——本轮没动，属于既有设计。

---

## 复现

```powershell
$env:JAVA_HOME="D:\App\ideaIC-2025.3\jbr"; $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
cd D:\workspace\deepseek\recon-lens
.\mvnw.cmd -o -q test "-Dtest=DocSearchMergeTest,ConcurrentSlotWriteTest,ShardCountLimitTest"
.\mvnw.cmd -o test                      # 794 tests
.\mvnw.cmd -o package -DskipTests
java -jar target\recon-lens-0.1.0.jar --server.port=18084 --recon.agent.enabled=true --recon.recall.force-mode-enabled=true
# 灌三篇文档后：
#   GET /api/docs/search?q=容差%20分片            → 融合分 + sources
#   GET /api/docs/search?q=容差%20分片&mode=keyword
#   GET /api/docs/search?q=容差%20分片&mode=fulltext
```

`--recon.recall.force-mode-enabled=true` 只为评测打开；生产默认 false，传 `mode` 会直接 400（批 0 的 M3）。
