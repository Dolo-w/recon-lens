# 厂商计费语义摘录（K4）

> 定位：把「厂商的结构性行为」沉淀在 profile 里，随 profile 版本化；把「这家客户这类差异怎么办」沉淀在知识库里。
> 两者生命周期不同、变更责任方不同 —— **结构性进代码（profile），经验性进数据（知识库）**，不混放。

## 摘录怎么进 profile

新增厂商 / 厂商 API 升级时，按下面 SOP 维护 `ColumnProfiles` 里对应 profile 的
`billingSemantics()` 与 `semanticsVersion()`：

1. **取证据**：从厂商源码（如 Lago `api/app/services/data_exports/csv/*.rb` 的 `base_headers`）
   或官方文档（如 Orb docs）核证导出表头与金额字段语义。
2. **写短句**：每条一句话，只写「不知道会踩坑」的事 —— 单位口径（分 vs 元）、冲抵语义
   （credit note 是冲销不是错误）、勾稽关系（几列加减 ≈ 总额）、作废/状态语义（voided 行还在导出里）。
   宁缺毋滥：写三条废话不如写一条踩坑实录。
3. **升版本**：`semanticsVersion()` 取 `厂商-api-年-月`（如 `lago-api-2026-11`）。
   厂商 API 变更 → 重核全部摘录 → 升版本。没有语义摘录的 profile 两条都保持默认（空）。
4. **出口**：摘录通过 `ColumnProfiles.semanticsDigest(profileId)` 拼成文本，当前两个出口：
   - `recon_lago_sync` 工具输出末尾自动附带（拉完账单就知道口径坑在哪）；
   - `recon_describe_table` 的语义层（K3 字段语义字典）。

## 当前摘录一览（与代码同步，代码为准）

### lago-invoice-fees（lago-api-2026-11）
- 金额列单位是分（源列以 `_cents` 结尾），profile 导入时已统一除以 100 换算成元 —— 两侧口径差 100 倍的差异先怀疑漏配换算。
- `fee_from_date` / `fee_to_date` 是这条计费项的服务归属期：月初月末切账差异、跨账期分摊差异先核对这两列，再怀疑金额本身。
- `fee_units` × `fee_precise_unit_amount` ≈ 小计（税费另列），三项对不上时优先怀疑阶梯/梯度计价而非数据损坏。

### lago-invoices（lago-api-2026-11）
- 金额勾稽：`fees_amount + taxes_amount − coupons_amount − credit_notes_amount − prepaid_credit_amount ≈ total_amount`；对不上时先核对优惠券/贷记单/预付抵扣三种冲抵是否都算进对账口径，再怀疑计费引擎。
- `credit_notes_amount_cents` 是贷记单冲销（冲抵历史发票的商业动作），不是数据错误 —— 出现大额负向差异先查 credit note，不要往计费正确性上引。
- `status` 列含 voided（作废）等状态，作废发票的金额行仍在导出里；做金额对账前先确认对账口径是否排除作废发票，不要假设导出里全是有效账。

### orb-invoices（orb-api-2026-11）
- Orb 金额是字符串十进制元（如 `"8.00"`），按 DECIMAL 直接入库，没有分换算 —— 与 Lago（分入库）对账时不要互相套用换算规则，×100/÷100 的差异先查口径混用。
- `subtotal` 是明细小计，`total` 是含折扣/返还后的总额，`amount_due` 是当前应付 —— 三者口径不同；跨厂商比对时统一取 total 对 total_amount，不要拿 amount_due 混比。
- `service_period_start/end` 是服务归属期（账期切分），`paid_at`/`voided_at` 是现金动作时间 —— voided_at 非空的发票已作废，金额对账口径应先排除；月初月末切账差异先核对归属期两列。

## 完整排查 SOP 放外部，不进仓库

厂商侧的**完整**排查手册（比如「Lago 发票金额对不上：credit note → prepaid credit → coupon → 税率 → 阶梯计价的五步排查树」）
属于交付/销售面的资产，放在外部资料库维护，仓库里只留引用和上面这份结构化摘录。
理由：完整 SOP 更新频率高、带客户案例、且不是跑代码必需 —— 进仓库会被 git 历史拖累，进外部库谁维护谁负责。
