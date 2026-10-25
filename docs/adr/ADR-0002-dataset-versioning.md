# ADR-0002　数据版本与数据集身份

- **状态**：提议（Proposed）
- **日期**：2026-10-24
- **影响面**：`agent/FileImporter`、`agent/TableRegistrar`、`store/`、`rule/`、`engine/`、`web/`、`docs/rule-dsl.md`
- **前置**：ADR-0001（会话层与记忆）——本 ADR 的「数据集」是它会话槽位里的一个字段
- **迁移**：`docs/migrations/2026-10-24-dataset-fingerprint.sql`

---

## 0. 一句话

**把「数据版本」提升成和「规则版本」同等的一等公民**：每次导入留下可识别的身份（逻辑名 + 版本 +
指纹），规则可以按逻辑名而不是物理表名取数，job 同时钉住规则快照**和**数据快照——于是「同样是这批数据，
为什么两次结果不一样」这个问题，永远答得出来。

---

## 1. 背景

### 1.1 现状（已核实，不是推测）

| 事实 | 位置 | 含义 |
|---|---|---|
| 导入绝不覆盖已有表 | `agent/FileImporter.java:174` | 第二批数据必然落到新表（`bill_left_v2`）；老表不动 |
| 规则绑定**物理表名** | `rules/bill-recon.yml` 的 `left.table: bill_left` | 换表 = 改规则 = 走完一遍审批链 |
| 注册记录只存列名快照 | `recon_registered_table(table_name, columns_snapshot, registered_by, registered_at)` | 没有行数、没有哈希、没有「这是谁的 v2」 |
| job 只钉规则快照 | `recon_job.rule_snapshot TEXT` | **没有对应的 `data_snapshot`** |
| 物化表覆盖式重建 | `model/ModelDdl.java:38`（`SHADOW_SUFFIX = "__new"`） | `<CODE>__MODEL` 每次重建，内容会被换掉 |
| 物化记录与 job 无关联 | `recon_model.row_count` / `build_ms` | 有行数，但答不了「这个 job 读的是哪一次物化」 |

一句话概括这个缺口：**规则是一等公民（有版本、有快照、有审批、有闸门），数据不是。**

### 1.2 症状

用户在第一次对账之后又给两份数据，系统只能把它当成**另一个世界**：

1. **每次刷新数据都要改规则、再签一次字。** 用户每周传一次「更详细」的数据，就得复制一版规则改 `table`、
   过静态校验、过影子试算、领一次性确认码、再 approve。第三次之后，那个签字会退化成「反正都要点一下」——
   **审批信号被稀释，这是安全问题，不是体验问题。**
2. **「上周少 50 笔，这周少 20 笔」答不出来。** job 里没有记录它读的是哪一版数据；物化表还能被就地重建。
   重跑结果一变，没有任何字段能解释它为什么变。
3. **两次对账的差异无法相减。** 「数据细化之后多出来的 137 条里，多少是数据带来的、多少是新问题」——
   今天只能人工打开两个 `recon_diff` 对。

### 1.3 「更详细」其实有三种，处理方式完全不同

| 形态 | 例子 | 性质 | 正确处理 |
|---|---|---|---|
| **粒度变细** | 原来按订单对，现在给到订单行 | **口径变了** | 必须新数据集 + 新规则 + 人工审批；两次结果**不可比** |
| **列变多** | 原来只有金额，现在有税额、手续费 | 同口径，可比字段变多 | 同数据集的**新版本**，规则可只加字段（新版本） |
| **范围/补全** | 原来 1–6 月，现在 1–12 月；补了缺失字段 | 同口径，**数据变了** | 同数据集的**新版本**，规则**不该改** |

用户自己往往分不清这三种。**系统的第一个职责是把它们分开**，第二个职责才是让后两种不用改规则。

### 1.4 一个必须先否掉的方向

「干脆让导入覆盖老表，省事」——**不行，而且不是技术原因**：对账的业务表是客户自己的（ERP、结算系统、
Excel 导出的中间表），**我们不是 ETL，也没有权利改写客户的生产表**。`FileImporter` 现在不覆盖是对的，
这条约束在本 ADR 里继续保留（N2）。

---

## 2. 决策

### D1　数据版本是一等公民：新增 `recon_dataset`

一张逻辑名 → 物理表的映射表，带版本、状态、指纹、继承链。它不是中间件，就是一张表（沿用 D6 的
「不引入新中间件」原则）。

关键字段：`logical_name` / `version` / `table_name` / `schema_hash` / `row_count` / `status` /
`supersedes_id` / `relation` / `source`。

**「一个逻辑名只有一个 ACTIVE 版本」用可移植的唯一键守**：额外一列 `active_slot`，ACTIVE 时填
`logical_name`，其他状态填 `NULL`，对它加唯一索引。MySQL 8 与 H2 都支持「多行 NULL 不冲突」，
所以这是把部分唯一索引降级成普通唯一索引的标准做法（DDL 见 §4）。

### D2　导入必须声明关系，默认 NEW_DATASET

`recon_import_file` / `recon_register_table` 在 confirm 阶段必须带一个 `relation`：

| relation | 含义 | 后续行为 |
|---|---|---|
| `NEW_DATASET`（默认） | 这是一批新数据 | 新建逻辑名，`version=1` |
| `VERSION_OF:<logical_name>` | 同一数据集的新版本 | 走升版闸门 D6，成功后旧版置 `SUPERSEDED` |
| `NEW_SCOPE` | 同一批数据，但口径变了 | 新建逻辑名，并在 UI 标「与 X 不可比」 |

**默认 `NEW_DATASET` 是刻意的**：猜错方向的代价不对称——把「新版本」误判成「新数据集」，只是多一条记录；
把「换了口径」误判成「版本升级」，会让两次结果看起来可比，从而得出错误的业务结论。

### D3　指纹分两级：零扫描的 L1，按需的 L2

| 级别 | 内容 | 成本 | 能用它说什么 |
|---|---|---|---|
| **L1**（默认） | `schema_hash`（列名+类型排序后哈希）+ `row_count` | 零扫描（元数据 + 一次 COUNT） | 只能说「**结构与行数**一致」 |
| **L2**（按需） | 对业务键做 `SUM(CRC32(key))` 或 `COUNT(DISTINCT key)` | 一次全表扫 | 才可以说「**内容**看起来一致」 |

**措辞是硬约束**（N7）：只跑过 L1 时，UI 和 MCP 返回里**不许**出现「数据一致」，只能说「结构与行数一致」。
把「可能不一样」说成「一样」，比不说更糟——用户会拿着这句话去解释业务差异。

L2 只在两种时机会跑：用户报「结果不对」，或人工点「校验这次导入」。

### D4　job 同时钉规则快照和数据快照；重跑语义分两类

`recon_job` 新增 `data_snapshot TEXT`，与已有的 `rule_snapshot` 对称，内容形如：

```
left=SALES_BILL@v2(table=bill_left_v2,sha=a1b2c3,rows=2000000)
right=SETTLE_BILL@v1(table=bill_right,sha=d4e5f6,rows=1999970)
```

同时把重跑的语义写死，分成两条**不同的**操作：

| 操作 | 数据来源 | 用途 |
|---|---|---|
| **retry / resume**（补跑失败分片） | 读 `job.data_snapshot` 里钉的物理表 | 断点续跑，结果必须与首次一致 |
| **rerun**（同规则再跑一次） | 重新解析当前 ACTIVE 的数据集 | 数据刷新后的正常重跑 |

**这两条混在一起是现在最危险的地方之一**：`resume` 读到了新数据，等于把两次不同的对账结果缝在一起，
而 `shard_done` 会让它看起来像一个完整的 job。

### D5　规则可选写成 `dataset:`，解析结果写进 job 快照

`left` / `right` 三种写法并存：

```yaml
left:
  table: bill_left        # 写法 1：物理表直连（向后兼容，不受本 ADR 管理）
  # 或
  dataset: SALES_BILL     # 写法 2：逻辑名，解析到当前 ACTIVE 版本
  # 或
  sql: "SELECT ..."       # 写法 3：自定义口径，本 ADR 不管
```

解析发生在 **job 创建时**，而不是 SQL 拼装时；解析结果（物理表 + 版本 + 指纹）**立刻写进
`job.data_snapshot`**，之后整个 job 生命周期内不再解析。这条是把「间接层」的安全性问题关掉的关键：
**间接层只影响「下一次跑」，绝不影响「这一次跑」和「重跑这一次」。**

### D6　升版闸门：维度列变了不许升版

`VERSION_OF` 的 confirm 阶段做一道静态检查，与闸门 1 同层、同样不花试算成本：

- 取旧版本的 `columns_snapshot` 与新表实际列，算差集；
- 若**规则声明的 `dimension` 列**出现在差集里（新增或消失），**拒绝** `VERSION_OF`，返回
  `GRANULARITY_CHANGED`，并提示「这是换口径，请用 `NEW_SCOPE` + 新规则」。

理由是 1.3 的第一种形态：维度一变，业务键的语义就变了，把两次结果放在一起比较是**错的**，
而错的比较比没有比较更危险。

其他列的变化只提示、不拒绝：多列 → 「可比字段 +3」，少列 → 「规则里若引用了这几列会失败」并列出。

### D7　跨 job 对比只在可比时给结论

新增 `recon_compare_jobs(jobA, jobB)`，输出「新增 / 消失 / 仍存在」三组差异。但**可比的判据必须写在结果里**：

- `rule_code` 不同 或 `rule_version` 不同 → `COMPARABLE=false, reason=RULE_CHANGED`
- 两侧 dataset 的 `relation` 含 `NEW_SCOPE` → `COMPARABLE=false, reason=SCOPE_CHANGED`
- 仅数据版本不同（`VERSION_OF`）→ `COMPARABLE=true`，并按差异是否涉及新增列分组：
  `newColumnRelated=true` 的那部分单独列出

这样一来，用户拿到的不只是「137 条新增」，而是「137 条新增里 92 条涉及新增的税额列」——这才是他想要的答案。

---

## 3. 备选方案与为什么不选

| 方案 | 好在哪 | 为什么不选 |
|---|---|---|
| **A. 覆盖式导入**（导入时替换老表） | 零概念，规则永远不用改 | 业务表是客户的，我们没有改写权；且历史结果立刻失去可复现性（§1.4） |
| **B. 只靠表名约定**（`bill_left_v2` / `_20261024`） | 零开发 | 约定不是契约：系统不知道 v1 和 v2 的关系，也就做不了 D6 的闸门，更算不出「两次差异相减」 |
| **C. 每次导入全量快照一份历史数据** | 可复现性最强 | 存储爆炸（一次导入 2×200 万行 ≈ 数百 MB）；且等于把客户的业务表复制进我们的库，数据主权问题 |
| **D. 逻辑名 + 版本 + 指纹 + 快照钉住（本 ADR）** | 只在元数据层加概念，不碰数据本体；间接层只影响下一次跑 | 多一张表、多一道闸门，且规则里多一种写法（三种写法并存有认知成本） |

**关于 D 的取舍一句话**：我们管的是**身份**，不是**内容**。数据始终留在客户的表里，我们只记住「这一版是什么」。

---

## 4. 数据模型

### 4.1 新表 `recon_dataset`

```sql
CREATE TABLE IF NOT EXISTS recon_dataset (
  id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
  logical_name   VARCHAR(128)  NOT NULL,          -- 业务名，如 SALES_BILL
  version        INT           NOT NULL,          -- 1,2,3...
  table_name     VARCHAR(128)  NOT NULL,          -- 物理表
  schema_hash    VARCHAR(64)   NOT NULL,          -- L1 指纹：列名+类型排序后 SHA-256
  row_count      BIGINT,                          -- L1 指纹：导入/注册时的行数
  content_checksum VARCHAR(64),                   -- L2 指纹，按需写入
  status         VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / SUPERSEDED / RETIRED
  active_slot    VARCHAR(128),                    -- ACTIVE 时 = logical_name，其余为 NULL（唯一键见下）
  supersedes_id  BIGINT,                          -- 指向前一版
  relation       VARCHAR(16)   NOT NULL,          -- NEW_DATASET / VERSION_OF / NEW_SCOPE
  source         VARCHAR(16)   NOT NULL,          -- IMPORT / REGISTER / MODEL
  created_by     VARCHAR(64),
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  note           VARCHAR(512)
);
```

索引（两种方言都要）：

```sql
CREATE UNIQUE INDEX uk_dataset_name_ver  ON recon_dataset (logical_name, version);
CREATE UNIQUE INDEX uk_dataset_active    ON recon_dataset (active_slot);   -- 多行 NULL 不冲突
CREATE INDEX        idx_dataset_table    ON recon_dataset (table_name);
```

> `active_slot` 是本 ADR 里唯一的「技巧」，值得留一行注释在 DDL 里：MySQL 8 与 H2 都没有可移植的
> 部分唯一索引，用「ACTIVE 填值 / 其他置 NULL + 普通唯一索引」实现「一个逻辑名只有一个 ACTIVE」。

### 4.2 两处增量列（均可为空，向后兼容）

```sql
ALTER TABLE recon_registered_table ADD COLUMN schema_hash VARCHAR(64);
ALTER TABLE recon_registered_table ADD COLUMN row_count   BIGINT;

ALTER TABLE recon_job              ADD COLUMN data_snapshot TEXT;
ALTER TABLE recon_job              ADD COLUMN dataset_version VARCHAR(256);  -- 冗余：便于列表页不解析快照
```

`data_snapshot` 允许为 `NULL`：老 job 就是没有。**UI 必须显示「未记录（ADR-0002 之前的任务）」，
而不是显示空或假装有**——这是 N5。

> **实现注意（P0 第一个坑）**：`store/RegisteredTableRepository.java:58` 的行映射是**按位置**取的
> （`rs.getString(1..3)` + `rs.getTimestamp(4)`），对应 `SELECT table_name, columns_snapshot,
> registered_by, registered_at`。加 `schema_hash` / `row_count` 时若改了 SELECT 列表，必须同步改索引，
> **或者干脆改成按列名取**。后者更该做：这个 mapper 已经被改过一次列顺序就不会再有第二次侥幸。

### 4.3 H2 / MySQL 双跑约束（沿用现有约定）

不用 JSON 列（`data_snapshot` 是 TEXT，格式自己解析）、不用 `ON DUPLICATE KEY`（用 UPDATE-then-INSERT，
与 `RegisteredTableRepository` 一致）、布尔一律 `TINYINT`。

### 4.4 与物化表的关系

`<CODE>__MODEL` 是一次物化的产物，**也是一份数据集**（`source=MODEL`，`logical_name=<CODE>__MODEL`）。
这样「这个 job 读的是哪一次物化」这个问题，与「读的是哪一版导入」用同一条链路回答，不搞第二套。

---

## 5. 不变量（每条都要有测试）

| 编号 | 不变量 | 测试形态 |
|---|---|---|
| N1 | **retry 不改数据来源**：`resume` 读 `job.data_snapshot` 里的物理表，绝不读 ACTIVE | 钉住 v1 → 导入 v2 升 ACTIVE → resume 老 job → 断言读到的是 v1 表 |
| N2 | **导入不覆盖**（沿用现状） | 建表后再次导入同名表 → 断言拒绝且原表行数不变 |
| N3 | **一个逻辑名只有一个 ACTIVE** | 并发两次升版 → 断言只有一条 `active_slot` 非空，另一条失败 |
| N4 | **job 答得出读了什么** | 任意 job 都能从 `data_snapshot` 反解出 table / schema_hash / rows |
| N5 | **不假装有**：老 job 的 `data_snapshot IS NULL` 时，摘要必须标「未记录」 | 断言 `recon_job_summary` 返回 `dataRecorded=false` |
| N6 | **不可比就报不可比**：跨 rule_version 或 `NEW_SCOPE` 时 compare 返回 `COMPARABLE=false` | 两条 job 构造三组关系各跑一次 |
| N7 | **措辞不越权**：只有 L1 指纹时，返回里不得出现「数据一致」 | 断言响应体不含该词，且 `contentVerified=false` |

---

## 6. 后果

### 正面

- 「为什么两次结果不一样」从**无法回答**变成**有据可查**（N4）。
- 数据刷新不再需要改规则（走 `dataset:`），审批信号回到它本来的含义：**签字只对规则内容负责**。
- 两次差异相减（D7）让「数据细化带来的噪音」和「新出现的问题」第一次能被分开。
- 全部改动在元数据层，**不碰数据本体**，私有化交付时不需要客户配合做任何数据搬家。

### 负面与缓解

| 负面 | 缓解 |
|---|---|
| 多一张表、多一套概念，认知成本上升 | 规则里 `table:` 写法保留（D5），**老用户不需要知道 dataset 存在**；只有需要「刷新不用改规则」的人才用 `dataset:` |
| 三种写法（`table` / `dataset` / `sql`）并存 | `rule-dsl.md` 里用一节讲清「什么时候用哪个」，并在校验期拒绝 `table` 与 `dataset` 同时出现 |
| 升版闸门（D6）可能误拒 | 只拦 `dimension` 列，其他列只提示；误拒的出口是明确的（改用 `NEW_SCOPE`） |
| L1 只能说「结构与行数一致」，用户可能不满意 | 该不满意是对的——把话说满才是问题；需要更强结论时手动跑 L2 |
| `active_slot` 是个技巧，容易被后人改坏 | DDL 里写注释 + N3 测试；一旦被改成普通索引，N3 立刻红 |

---

## 7. 验收

**P1 出口的唯一硬指标**：构造「同一批数据的两版」演练，第二次导入多 3 列、行数变化 20%，
然后跑两个 job，要求：

1. `recon_job_summary` 能分别报出两次读的是不同 `schema_hash` 与不同 `row_count`（N4）；
2. `resume` 第一个 job 的一个分片，结果与首次一致（N1）；
3. `recon_compare_jobs` 输出可比（`COMPARABLE=true`），且把差异按「是否涉及新增列」分组；
4. 故意改一个 `dimension` 列再升版，必须返回 `GRANULARITY_CHANGED`（D6）。

**辅助指标**（P2 之后）：20 个真实列变更样本上，升版闸门的误拒率 < 5%，漏拒率 = 0（漏拒的代价高于误拒）。

---

## 8. 分期

| 期 | 内容 | 出口条件 |
|---|---|---|
| **P0**（1–2 天） | 注册表加 `schema_hash` / `row_count`；confirm 时算列差集并提示 | 注册一张列变过的表，能看到「+3 / -1」 |
| **P1**（2–3 天） | `recon_job.data_snapshot` + retry/rerun 语义分离（D4）+ `recon_compare_jobs`（D7） | §7 的 1–3 条通过 |
| **P2**（1 周） | `recon_dataset` 表 + 导入声明 `relation` + 升版闸门（D6） | §7 第 4 条通过 |
| **P3**（1–2 周） | 规则 `dataset:` 间接层（D5）+ 物化表纳管（§4.4） | 数据刷新不改规则能跑通；`resume` 仍读旧表 |

### 8.1 落地进度（2026-10-24）

| 期 | 已完成 | 缺口 |
|---|---|---|
| **P0** | 注册表 `schema_hash` / `row_count` 在 confirm 时写入；`TableRegistrar.Preview` 带 `added`/`removed`/`rowsBefore`，`changeText()` 出人话（「首次注册」/「新增 2 列（a,b）」/「列与上次注册一致」，有消失列时提示「被规则引用会报未知列」） | 只在 MCP `recon_register_table` 路径生效，`recon_import_file` 走的是同一 `TableRegistrar`，无需额外改动 |
| **P1** | `DatasetFingerprints`（L1 零扫描指纹 + 现场 LIVE + L2 `contentChecksum`）；`recon_job.data_snapshot` 在 **建任务那一刻**写死；`GET /api/jobs/{id}` 返回 `dataSnapshot` + `dataDrift`；`GET /api/jobs/compare` 实现 D7 | retry/rerun 语义已按 D4 分开（retry 读旧快照、rerun 重解析），但还没写「rerun 时若数据已变则提示」 |
| **P2** | `recon_dataset` 表 + 迁移脚本已在 schema 里 | **Java 零引用**：`relation` 声明、升版闸门 `GRANULARITY_CHANGED`、`DatasetRepository` 都还没写 |
| **P3** | — | 未开始 |

一句话：**P0/P1 已从「建了表没写代码」变成真的在读写**；P2/P3 仍是纸面设计，别把 DDL 当成功能。

P0/P1 两条**不改变任何现有语义**，只是把已经存在的行数信息存下来、把重跑的两条路径分开；
P2 才引入新概念；P3 会动到 job 的解析时机，风险最高，所以放最后。

---

## 9. 待定（需要作者拍板）

1. **L2 校验和用什么算法**：`SUM(CRC32(key))` 依赖方言（H2 有没有 `CRC32` 要验），`COUNT(DISTINCT key)`
   处处可用但区分度低（换内容不改行数就查不出）。倾向：**先用 `COUNT(DISTINCT key)` + `MIN/MAX(key)` 三件套**，
   够用且全方言可用；真要强校验再上全表哈希。
2. **数据集要不要区分左右侧**：`SALES_BILL` 天然只在一侧用，但同一个逻辑名也可能两边都出现（内部对内部）。
   倾向：不加 `side` 字段，靠规则里的 `left.dataset` / `right.dataset` 决定，表只做「逻辑名 → 物理表」。
3. **`table:` 直连写法的兼容期**：是一直保留（成为「高级用法」），还是 P3 之后在文档里标废弃？倾向保留，
   因为「整表对账」是最常见的用法，强行套一个逻辑名只会让人多填一次表单。
4. **物化表是否真的纳管**（§4.4）：纳管的好处是口径统一，坏处是每次 `build_model` 都产生一个 dataset 版本，
   版本号会膨胀。倾向纳管但**只保留最近 N 版**（`RETIRED` 状态 + 定期清理策略，不在本 ADR 定）。

---

## 10. 一句话总结

**我们管身份，不管内容**：数据始终在客户表里，我们只记住「这一次读的是哪一版、长什么样、多少行」——
于是「为什么变了」永远有答案，而「签字」只对规则负责。
