# 批 5 实测报告：观测（时延 / 缓存 / 评测基线）

> 日期：2026-10-26　对象：`target/recon-lens-0.1.0.jar`
> 环境：JDK 21.0.8（`D:\App\ideaIC-2025.3\jbr`）、H2 内存库、桩模型 `127.0.0.1:18999`
> 全量测试：**798 tests / 0 failures**

## 一句话结论

**轨迹表本来就有 `cost_ms`，缺的是「token 花在哪」和「评测怎么进 CI」。** 这一批把
三个数补齐到同一张表、同一份报告里：每一步的耗时（ms）、每一步的估算 token、每档配置的四项 SLO。

## 结论速览

| 项 | 最硬的一条实测证据 | 判定 |
|---|---|---|
| M15 时延与花费 | `GET /api/audit/runs/3` → `costMs=0`、`tokensEst=212`；CSV 表头含 `costMs,tokensEst` | 通过（见下「为什么是 0」） |
| M16 facts 缓存 | 同一会话连说两轮，`系统事实重建：key=3:1` **只出现 1 次**（改前是每轮一次） | 通过 |
| M17 评测进 CI | `docs/eval/p1.md`：4/4 达标；`p2-stub.md`：2/4 —— 同一套脚本量出「模型坏了会掉 50 个点」 | 通过（p2 真档留 CI） |

## 1. M15：一步多慢、一步多少钱

**改了什么**

- `schema.sql` 的 `recon_agent_step` 加 `tokens_est INT NULL`（只加不删）。
  迁移脚本 `docs/migrations/2026-10-26-agent-step-tokens.sql` 用 `information_schema` 做幂等守卫，
  可重复执行；文件尾写了回滚与「为什么是 NULL 而不是 DEFAULT 0」。
- **没有加 `latency_ms`**：表里早有 `cost_ms`（`Duration.ofNanos(...).toMillis()` 的墙钟毫秒），
  名字叫 cost 但记的是时间。路线图写「加 latency_ms 与 tokens_est」，实际是「复用 cost_ms + 新增 tokens_est」。
  多一列同义字段的代价是两张表迟早对不上，比改名贵。
- `AgentRunRepository.recordStep(...)` 多一个 `tokensEst`；落表的是**脱敏+截断之后的那份**，
  因为复盘时能看到的就是它，用原文估算会和实际内容对不上账。
- `AuditController`：JSON 加 `tokensEst`，CSV 在 `costMs` 后面加一列（审计导出的列序保持稳定，新列插在中间而不是追加到末尾）。
- 顺手修了两处 `costMs` 传 null 的旧调用（`PerceptionService`、`ChatSessionService`）：
  有列不填等于没这列。

**实测**

```
GET /api/audit/runs/3            （5 秒一轮的定时巡检，agent 开）
steps[0] = {seq:1, phase:"OBSERVE", action:"perception_scan", status:"OK", costMs:0, tokensEst:212}

GET /api/audit/runs/7            （会话交接）
steps[0] = {seq:1, phase:"ACT", action:"handover-session", status:"OK", costMs:0, tokensEst:25}

GET /api/audit/runs/3?format=csv
seq,phase,action,status,costMs,tokensEst,truncated,digest,input,payload
```

**为什么 `costMs` 是 0**：不是没写，是**真的不到 1 毫秒** —— H2 内存库 + 1 个任务，
`Duration.toMillis()` 截断就是 0。这恰恰是这一列可信的表现（同一份代码在 MySQL + 万级数据下就是几十上百）。
要看到非 0，得换真库或放大数据量；我没为了报告好看去伪造一个 `Math.max(1, ...)`。

## 2. M16：facts 不再每轮重建

**改了什么**

- `chat/FactsCache`：TTL 30 秒、`ConcurrentHashMap`、键是 `(规则计数:任务计数)`；
  `synchronized` 只护「同一键的重复构建」，不让所有轮次排队。
- 键为什么不用 `MAX(version)`：规则被**删**了以后 `MAX` 可能不变，但事实已经变了。
  计数（`COUNT(*)` + `SUM(version)`）变了就重建，简单且不会漏。
- `ChatRuntime` 改为 `factsCache.get(fresh.stateRev(), () -> ContextFacts.build(...))`；
  构造器与 `ChatConfiguration` 都补了新参数。

**实测**

```
POST /api/chat/sessions            → session=1
POST /api/chat  "看看有哪些对账规则"
POST /api/chat  "最近跑过哪些任务"     （同一会话，间隔 <1s）
grep "系统事实重建" app.log
→ 22:55:07.288 INFO FactsCache - 系统事实重建：key=3:1（规则/任务计数变了或缓存过期）   ← 只有 1 条
```

改前每轮一次 `ContextFacts.build`（两次查询：规则库 + 最近任务），现在一轮省两次查询；
30 秒后或计数变化才重建。单测 `FactsCacheTest` 用计数 builder 断言「连续两轮只构建一次」。

## 3. M17：评测从「本地脚本」变成「常设基线」

**改了什么**

- `work/golden-run.mjs` 末尾加报告写入：`docs/eval/<label>.md`（四指标 + 逐条结果）与 `.json`（机器可读）。
  指标直接对上架构文档的四条 SLO：意图 ≥90%、工具选择 ≥85%、平均轮数 ≤3、每会话 token ≤6000。
- 新增 `.github/workflows/eval.yml`：matrix 两档（`p1` 无模型 / `p2` 带模型），起服务 → 跑 golden set →
  `$GITHUB_STEP_SUMMARY` 贴报告 → artifact 存档；`p2` 没有 `DEEPSEEK_API_KEY` 时**跳过而不是变红**。

**实测（本地，同一套脚本打两个实例）**

| 档 | 服务 | 意图命中率 | 工具选择率 | 平均轮数 | token 中位数 | 达标 |
|---|---|---|---|---|---|---|
| `p1`（词表，无模型） | `:18086` | **100.0%**（30/30） | **100.0%**（25/25） | 2.13 | 136 | 4/4 |
| `p2-stub`（桩模型） | `:18084` | **50.0%** | **52.0%** | 2.13 | 135 | 2/4 |

第二行不是「p2 的真实水平」——桩模型对所有问题都回
`{"action":"LIST_RULES","confidence":0.9}`。它的价值是反证：**这套评测确实能抓住模型路径的退化**，
而不是只会输出一排绿色的对勾。

## 本轮新发现

1. **模型说 0.9 就执行，哪怕和词表判断完全不同。** `p2-stub` 掉的那 50 个点全是这个原因：
   `LIST_RULES` 是无实体读动作，`evidence` 为 `UNVERIFIED` 但 `hasEntity()` 为假，于是不澄清、直接执行。
   批 0 的 M2 只收紧了「带实体的读动作」，这条是它的另一面：**无实体时模型可以无声地覆盖词表的正确判断**。
2. `cost_ms` 在 H2 小数据下恒为 0，天然区分不出「快」和「没测」；批 6 若做 SLO 报表，建议同时记 `attempts` 或直接用微秒精度。
3. `p2` 档在 CI 里依赖 `secrets.DEEPSEEK_API_KEY`，**光仓库里没有这个 secret 就跑不出真 p2**——
   这一批只能证明机制通，不能证明 p2 的数值。

## 复现

```powershell
$env:JAVA_HOME="D:\App\ideaIC-2025.3\jbr"; $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
cd D:\workspace\deepseek\recon-lens
./mvnw.cmd -o package -DskipTests

# M15：起带 5 秒巡检的实例
java -jar target\recon-lens-0.1.0.jar --server.port=18084 --recon.agent.enabled=true --recon.agent.perception-interval-ms=5000
# 等 10 秒后
curl -H "X-Operator: alice" "http://127.0.0.1:18084/api/audit/runs/3"      # costMs / tokensEst

# M16：同一会话连说两轮，日志里只应有一条「系统事实重建」

# M17：P1 档（无模型）
java -jar target\recon-lens-0.1.0.jar --server.port=18086 --recon.agent.enabled=true
$env:BASE="http://127.0.0.1:18086"; $env:EVAL_LABEL="p1"; node work\golden-run.mjs
```
