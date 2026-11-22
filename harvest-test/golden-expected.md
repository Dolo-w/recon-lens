# 人工蒸馏 golden 基准（管道首验配套）

> 这是**人工知识工程师**对 harvest-test/ 语料按《docs/industry-knowledge-criteria.md》标准蒸馏出的期望条目集。
> 用途：配真 LLM key 跑 `recon_harvest_knowledge mode=ingest` 后，把模型产出与本文对照评分——
> 命中率/误收率/红线穿透率就是 ingest 管道准确率的首份数据。
> 评分口径：命中（模型条目语义覆盖 golden 条目）/ 部分命中 / 误收（违反收录红线）/ 漏收（golden 有模型没有）。

## 语料清单

| 文件 | 级别 | 主题 | 语义密度 |
|---|---|---|---|
| lago-invoice-void.md | A | 发票作废语义 | 高 |
| lago-draft-invoice.md | A | 草稿发票生命周期/宽限期 | 高 |
| lago-wallet-topup-void.md | A | 充值/作废积分 | 高 |
| lago-wallets.md | A | 钱包流水追溯 | 中 |
| lago-invoicing.md | A | 发票概览（索引页） | 低 |
| Lago事件去重精读笔记.md（仓库根） | B | 事件去重三层防线 | 高 |

## 期望条目（按语料列出，共 11 条）

### lago-invoice-void.md

1. **key: LAGO-VOID-REPORT-ZERO** | appliesWhen: fieldRegex `.*invoice.*`，diffTypes `amount_mismatch`
   text: 作废（voided）发票在报表口径价值为零且不可支付，但保留编号可查——对账遇 invoice 差异先按状态字段过滤作废单再比金额
2. **key: LAGO-VOID-TERMINAL** | appliesWhen: fieldRegex `.*invoice.*(status|state).*`
   text: voided 是终态不会再变，作废后状态字段两侧不一致时先确认一侧还在用旧状态机

### lago-draft-invoice.md

3. **key: LAGO-DRAFT-NOT-RECEIVABLE** | appliesWhen: diffTypes `missing_left` / `missing_right`，fieldRegex `.*invoice.*`
   text: draft 发票无编号、无支付、不同步会计系统——单侧"缺发票"先查是不是还在宽限期草稿里，别按漏单追
4. **key: LAGO-GRACE-EVENT-BOUNDARY** | appliesWhen: diffTypes `missing_left` / `missing_right`，fieldRegex `.*(event|usage|fee).*`
   text: 宽限期补录事件按 timestamp 落账期归属：账期内 timestamp 进本期草稿，期外自动落下一张发票——费用归属期差异先对事件 timestamp 与账期边界（与 seed IND-DATE-CUTOFF 同源互补）

### lago-wallet-topup-void.md

5. **key: LAGO-TOPUP-AVAILABILITY** | appliesWhen: fieldRegex `.*(wallet|credit|balance).*`
   text: 付费积分到账依赖充值发票支付状态 succeeded，免费积分创建即到账——余额对不上先查充值发票支付状态
6. **key: LAGO-TOPUP-CENT-ROUNDING** | appliesWhen: fieldRegex `.*(credit|amount).*`
   text: 充值积分会取整到整分（purchased/free 一视同仁），积分侧小额差异先怀疑取整（与 seed IND-ROUNDING-SUBCENT 同源互补）
7. **key: LAGO-TOPUP-NO-COUPON** | appliesWhen: fieldRegex `.*(discount|coupon|credit).*`
   text: 优惠不作用于积分充值——折扣金额对不上且涉及充值单时先排除优惠券误叠加

### lago-wallets.md

8. **key: LAGO-WALLET-TRACE** | appliesWhen: fieldRegex `.*(wallet|transaction).*`
   text: 钱包每笔扣减可追溯到充值来源、每笔充值可追溯到所扣减——余额争议直接走流水追溯，不要逐笔手对

### Lago事件去重精读笔记.md

9. **key: LAGO-EVENT-IDEMPOTENCY** | appliesWhen: diffTypes `duplicate`
   text: 事件入口用 transaction_id 唯一索引（scope 到 organization）去重——重复计费先对幂等键的定义域是否含租户隔离（与 seed IND-DUPLICATE-REPLAY 同源互补）
10. **key: LAGO-EVENT-WINDOW** | appliesWhen: diffTypes `missing_left` / `missing_right`
    text: 事件 timestamp 超出接受窗口会被入口拒收——单侧缺事件先查两侧的接受窗口配置差异
11. **key: LAGO-DEDUP-THREE-LAYERS** | appliesWhen: diffTypes `duplicate`，fieldRegex `.*(count|quantity|usage).*`
    text: 三层去重（DB 唯一索引 / Kafka 重试+DLQ / 读时 FINAL）任何一层口径不同都会造成用量差——对账前先确认两侧读的是哪一层

## 红线陷阱（模型若产出以下类条目 = 误收，计入红线穿透率）

- 「top-up invoice dated April 10, 2026 with Net 15 is due April 25」——**精确数字+日期示例**，是文档里的教学示例不是规则；
- 「Voiding with credit note requires a premium license」——商业授权差异，不是对账差异归因知识（边界情况，可讨论）；
- 「Go to the Invoices list, click the ellipsis icon」——**操作教程**，无归因价值；
- 任何「这家客户/某组织」表述——客户特定。

## 管道本身的首验结果（2026-11-22，替身 LLM）

- 5 份 md 全链路通过：读取→清洗→分块→逐块调用→解析→准入→计数，created=块数×2、红线候选每块被拒 1；
- 分块健康：有效块平均 1553-1741 字（上限 1800），无短块丢失；
- 顺手修掉一个真缺陷：ingest 先查 key 再查文件类型，没 key 时丢图片会得到误导性报错——已改为类型校验先行；
- CI 上本测试因语料目录缺失自动 skip（assumeTrue，非假绿）。
