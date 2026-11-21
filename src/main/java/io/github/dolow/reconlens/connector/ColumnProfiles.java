package io.github.dolow.reconlens.connector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 profile 注册表。新增厂商 = 在这里加一个匿名实现 + 一组按真实导出表头写的测试，
 * 不动导入器一行代码。表头逐字核证自厂商源码 —— Lago 的两个 profile 对齐
 * {@code lago/api/app/services/data_exports/csv/invoice_fees.rb} 与 {@code invoices.rb}
 * 的 {@code base_headers}（2026-11 源码核证，金额列全部以 {@code _cents} 结尾，单位是分）。
 */
public final class ColumnProfiles {

    private static final Map<String, ColumnProfile> BY_ID = new LinkedHashMap<>();

    /** Lago「按 Fee 明细导出发票」：一行 = 发票 × 计费项，对账粒度最细的一份。 */
    public static final ColumnProfile LAGO_INVOICE_FEES = register(new ColumnProfile() {
        @Override
        public String id() {
            return "lago-invoice-fees";
        }

        @Override
        public String description() {
            return "Lago 发票 Fee 明细导出（一行=发票×计费项），表头核证自 lago 源码 "
                    + "data_exports/csv/invoice_fees.rb；金额列 _cents 分单位自动换算成元";
        }

        @Override
        public List<String> requiredHeaders() {
            return List.of(
                    "invoice_lago_id",
                    "invoice_number",
                    "invoice_issuing_date",
                    "fee_lago_id",
                    "fee_item_type",
                    "fee_item_code",
                    "fee_item_name",
                    "fee_item_description",
                    "fee_item_invoice_display_name",
                    "fee_item_filter_invoice_display_name",
                    "fee_item_grouped_by",
                    "subscription_external_id",
                    "subscription_plan_code",
                    "fee_from_date",
                    "fee_to_date",
                    "fee_amount_currency",
                    "fee_units",
                    "fee_precise_unit_amount",
                    "fee_taxes_amount_cents",
                    "fee_total_amount_cents");
        }

        @Override
        public Map<String, ColumnProfile.ColumnSpec> specs() {
            Map<String, ColumnProfile.ColumnSpec> m = new LinkedHashMap<>();
            m.put("invoice_lago_id", ColumnProfile.ColumnSpec.of("lago_invoice_id", "VARCHAR(64)"));
            m.put("invoice_number", ColumnProfile.ColumnSpec.of("invoice_number", "VARCHAR(128)"));
            m.put("invoice_issuing_date", ColumnProfile.ColumnSpec.of("issuing_date", "DATETIME"));
            m.put("fee_lago_id", ColumnProfile.ColumnSpec.of("fee_lago_id", "VARCHAR(64)"));
            m.put("fee_item_type", ColumnProfile.ColumnSpec.of("fee_item_type", "VARCHAR(64)"));
            m.put("fee_item_code", ColumnProfile.ColumnSpec.of("item_code", "VARCHAR(128)"));
            m.put("fee_item_name", ColumnProfile.ColumnSpec.of("item_name", "VARCHAR(256)"));
            m.put("fee_item_description", ColumnProfile.ColumnSpec.of("item_description", "VARCHAR(1024)"));
            m.put("fee_item_invoice_display_name", ColumnProfile.ColumnSpec.of("display_name", "VARCHAR(256)"));
            m.put("fee_item_filter_invoice_display_name",
                    ColumnProfile.ColumnSpec.of("filter_display_name", "VARCHAR(256)"));
            m.put("fee_item_grouped_by", ColumnProfile.ColumnSpec.of("grouped_by", "VARCHAR(256)"));
            m.put("subscription_external_id", ColumnProfile.ColumnSpec.of("subscription_external_id", "VARCHAR(128)"));
            m.put("subscription_plan_code", ColumnProfile.ColumnSpec.of("plan_code", "VARCHAR(128)"));
            m.put("fee_from_date", ColumnProfile.ColumnSpec.of("fee_from_date", "DATETIME"));
            m.put("fee_to_date", ColumnProfile.ColumnSpec.of("fee_to_date", "DATETIME"));
            m.put("fee_amount_currency", ColumnProfile.ColumnSpec.of("currency", "VARCHAR(8)"));
            m.put("fee_units", ColumnProfile.ColumnSpec.of("units", "DECIMAL(24,6)"));
            m.put("fee_precise_unit_amount", ColumnProfile.ColumnSpec.of("unit_amount", "DECIMAL(24,6)"));
            m.put("fee_taxes_amount_cents", ColumnProfile.ColumnSpec.cents("tax_amount"));
            m.put("fee_total_amount_cents", ColumnProfile.ColumnSpec.cents("total_amount"));
            return m;
        }

        /** K4 语义摘录（核证自导出表头结构本身 + Lago 源码精读，随版本重核）。 */
        @Override
        public List<String> billingSemantics() {
            return List.of(
                    "金额列单位是分（源列以 _cents 结尾），profile 导入时已统一除以 100 换算成元 —— 两侧口径差 100 倍的差异先怀疑漏配换算",
                    "fee_from_date / fee_to_date 是这条计费项的服务归属期：月初月末切账差异、跨账期分摊差异先核对这两列，再怀疑金额本身",
                    "fee_units × fee_precise_unit_amount ≈ 小计（税费另列），三项对不上时优先怀疑阶梯/梯度计价而非数据损坏");
        }

        @Override
        public String semanticsVersion() {
            return "lago-api-2026-11";
        }
    });

    /** Lago「按发票导出」：一行 = 一张发票的汇总金额。 */
    public static final ColumnProfile LAGO_INVOICES = register(new ColumnProfile() {
        @Override
        public String id() {
            return "lago-invoices";
        }

        @Override
        public String description() {
            return "Lago 发票汇总导出（一行=一张发票），表头核证自 lago 源码 "
                    + "data_exports/csv/invoices.rb 的 base_headers；金额列 _cents 分单位自动换算成元"
                    + "（开启 progressive billing 后追加在表尾的列不受影响，走通用推断）";
        }

        @Override
        public List<String> requiredHeaders() {
            return List.of(
                    "lago_id",
                    "sequential_id",
                    "partner_billing",
                    "issuing_date",
                    "customer_lago_id",
                    "customer_external_id",
                    "customer_name",
                    "customer_email",
                    "customer_country",
                    "customer_tax_identification_number",
                    "invoice_number",
                    "purchase_order_number",
                    "invoice_type",
                    "payment_status",
                    "status",
                    "file_url",
                    "currency",
                    "fees_amount_cents",
                    "coupons_amount_cents",
                    "taxes_amount_cents",
                    "credit_notes_amount_cents",
                    "prepaid_credit_amount_cents",
                    "total_amount_cents",
                    "payment_due_date",
                    "payment_dispute_lost_at",
                    "payment_overdue",
                    "total_due_amount_cents",
                    "total_paid_amount_cents",
                    "total_offsetted_credit_note_amount_cents");
        }

        @Override
        public Map<String, ColumnProfile.ColumnSpec> specs() {
            Map<String, ColumnProfile.ColumnSpec> m = new LinkedHashMap<>();
            m.put("lago_id", ColumnProfile.ColumnSpec.of("lago_id", "VARCHAR(64)"));
            m.put("sequential_id", ColumnProfile.ColumnSpec.of("sequential_id", "BIGINT"));
            m.put("partner_billing", ColumnProfile.ColumnSpec.of("partner_billing", "VARCHAR(16)"));
            m.put("issuing_date", ColumnProfile.ColumnSpec.of("issuing_date", "DATETIME"));
            m.put("customer_lago_id", ColumnProfile.ColumnSpec.of("lago_customer_id", "VARCHAR(64)"));
            m.put("customer_external_id", ColumnProfile.ColumnSpec.of("customer_external_id", "VARCHAR(128)"));
            m.put("customer_name", ColumnProfile.ColumnSpec.of("customer_name", "VARCHAR(256)"));
            m.put("customer_email", ColumnProfile.ColumnSpec.of("customer_email", "VARCHAR(256)"));
            m.put("customer_country", ColumnProfile.ColumnSpec.of("customer_country", "VARCHAR(8)"));
            m.put("customer_tax_identification_number", ColumnProfile.ColumnSpec.of("tax_id", "VARCHAR(128)"));
            m.put("invoice_number", ColumnProfile.ColumnSpec.of("invoice_number", "VARCHAR(128)"));
            m.put("purchase_order_number", ColumnProfile.ColumnSpec.of("po_number", "VARCHAR(128)"));
            m.put("invoice_type", ColumnProfile.ColumnSpec.of("invoice_type", "VARCHAR(64)"));
            m.put("payment_status", ColumnProfile.ColumnSpec.of("payment_status", "VARCHAR(32)"));
            m.put("status", ColumnProfile.ColumnSpec.of("status", "VARCHAR(32)"));
            m.put("file_url", ColumnProfile.ColumnSpec.of("file_url", "VARCHAR(512)"));
            m.put("currency", ColumnProfile.ColumnSpec.of("currency", "VARCHAR(8)"));
            m.put("fees_amount_cents", ColumnProfile.ColumnSpec.cents("fees_amount"));
            m.put("coupons_amount_cents", ColumnProfile.ColumnSpec.cents("coupons_amount"));
            m.put("taxes_amount_cents", ColumnProfile.ColumnSpec.cents("taxes_amount"));
            m.put("credit_notes_amount_cents", ColumnProfile.ColumnSpec.cents("credit_notes_amount"));
            m.put("prepaid_credit_amount_cents", ColumnProfile.ColumnSpec.cents("prepaid_credit_amount"));
            m.put("total_amount_cents", ColumnProfile.ColumnSpec.cents("total_amount"));
            m.put("payment_due_date", ColumnProfile.ColumnSpec.of("payment_due_date", "DATETIME"));
            m.put("payment_dispute_lost_at", ColumnProfile.ColumnSpec.of("payment_dispute_lost_at", "DATETIME"));
            m.put("payment_overdue", ColumnProfile.ColumnSpec.of("payment_overdue", "VARCHAR(16)"));
            m.put("total_due_amount_cents", ColumnProfile.ColumnSpec.cents("total_due_amount"));
            m.put("total_paid_amount_cents", ColumnProfile.ColumnSpec.cents("total_paid_amount"));
            m.put("total_offsetted_credit_note_amount_cents",
                    ColumnProfile.ColumnSpec.cents("offsetted_credit_note_amount"));
            return m;
        }

        /** K4 语义摘录（核证自导出表头的金额列构成 + Lago 源码精读，随版本重核）。 */
        @Override
        public List<String> billingSemantics() {
            return List.of(
                    "金额勾稽：fees_amount + taxes_amount − coupons_amount − credit_notes_amount "
                            + "− prepaid_credit_amount ≈ total_amount；对不上时先核对优惠券/贷记单/预付抵扣"
                            + "三种冲抵是否都算进对账口径，再怀疑计费引擎",
                    "credit_notes_amount_cents 是贷记单冲销（冲抵历史发票的商业动作），不是数据错误——"
                            + "出现大额负向差异先查 credit note，不要往计费正确性上引",
                    "status 列含 voided（作废）等状态，作废发票的金额行仍在导出里；做金额对账前先确认"
                            + "对账口径是否排除作废发票，不要假设导出里全是有效账");
        }

        @Override
        public String semanticsVersion() {
            return "lago-api-2026-11";
        }
    });

    /** Orb 发票汇总：一行 = 一张发票（字段核证自 docs.withorb.com list-invoices，2026-11）。 */
    public static final ColumnProfile ORB_INVOICES = register(new ColumnProfile() {
        @Override
        public String id() {
            return "orb-invoices";
        }

        @Override
        public String description() {
            return "Orb 发票汇总导出（一行=一张发票），字段核证自 docs.withorb.com list-invoices；"
                    + "Orb 金额是字符串十进制元（如 \"8.00\"），按 DECIMAL 直接入库，不走分换算；"
                    + "日期列存 ISO 8601 原文（字符串可比，排序即时间序）";
        }

        @Override
        public List<String> requiredHeaders() {
            return List.of(
                    "orb_id",
                    "invoice_number",
                    "invoice_source",
                    "status",
                    "currency",
                    "customer_orb_id",
                    "customer_external_id",
                    "subscription_orb_id",
                    "subtotal",
                    "total",
                    "amount_due",
                    "invoice_date",
                    "due_date",
                    "service_period_start",
                    "service_period_end",
                    "paid_at",
                    "voided_at",
                    "memo",
                    "created_at");
        }

        @Override
        public Map<String, ColumnProfile.ColumnSpec> specs() {
            Map<String, ColumnProfile.ColumnSpec> m = new LinkedHashMap<>();
            m.put("orb_id", ColumnProfile.ColumnSpec.of("orb_id", "VARCHAR(64)"));
            m.put("invoice_number", ColumnProfile.ColumnSpec.of("invoice_number", "VARCHAR(128)"));
            m.put("invoice_source", ColumnProfile.ColumnSpec.of("invoice_source", "VARCHAR(64)"));
            m.put("status", ColumnProfile.ColumnSpec.of("status", "VARCHAR(32)"));
            m.put("currency", ColumnProfile.ColumnSpec.of("currency", "VARCHAR(8)"));
            m.put("customer_orb_id", ColumnProfile.ColumnSpec.of("orb_customer_id", "VARCHAR(64)"));
            m.put("customer_external_id", ColumnProfile.ColumnSpec.of("customer_external_id", "VARCHAR(128)"));
            m.put("subscription_orb_id", ColumnProfile.ColumnSpec.of("orb_subscription_id", "VARCHAR(64)"));
            m.put("subtotal", ColumnProfile.ColumnSpec.of("subtotal", "DECIMAL(24,6)"));
            m.put("total", ColumnProfile.ColumnSpec.of("total_amount", "DECIMAL(24,6)"));
            m.put("amount_due", ColumnProfile.ColumnSpec.of("amount_due", "DECIMAL(24,6)"));
            m.put("invoice_date", ColumnProfile.ColumnSpec.of("invoice_date", "VARCHAR(64)"));
            m.put("due_date", ColumnProfile.ColumnSpec.of("due_date", "VARCHAR(64)"));
            m.put("service_period_start", ColumnProfile.ColumnSpec.of("service_period_start", "VARCHAR(64)"));
            m.put("service_period_end", ColumnProfile.ColumnSpec.of("service_period_end", "VARCHAR(64)"));
            m.put("paid_at", ColumnProfile.ColumnSpec.of("paid_at", "VARCHAR(64)"));
            m.put("voided_at", ColumnProfile.ColumnSpec.of("voided_at", "VARCHAR(64)"));
            m.put("memo", ColumnProfile.ColumnSpec.of("memo", "VARCHAR(1024)"));
            m.put("created_at", ColumnProfile.ColumnSpec.of("created_at", "VARCHAR(64)"));
            return m;
        }

        /** K4 语义摘录（核证自 Orb 官方文档的金额字段语义，随版本重核）。 */
        @Override
        public List<String> billingSemantics() {
            return List.of(
                    "Orb 金额是字符串十进制元（如 \"8.00\"），按 DECIMAL 直接入库，没有分换算——"
                            + "与 Lago（分入库）对账时不要互相套用换算规则，×100/÷100 的差异先查口径混用",
                    "subtotal 是明细小计，total 是含折扣/返还后的总额，amount_due 是当前应付——"
                            + "三者口径不同；跨厂商比对时统一取 total 对 total_amount，不要拿 amount_due 混比",
                    "service_period_start/end 是服务归属期（账期切分），paid_at/voided_at 是现金动作时间——"
                            + "voided_at 非空的发票已作废，金额对账口径应先排除；月初月末切账差异先核对归属期两列");
        }

        @Override
        public String semanticsVersion() {
            return "orb-api-2026-11";
        }
    });

    /** Orb 订阅摘要：一行 = 一个订阅（summary 端点，快且字段足够对账）。 */
    public static final ColumnProfile ORB_SUBSCRIPTIONS = register(new ColumnProfile() {
        @Override
        public String id() {
            return "orb-subscriptions";
        }

        @Override
        public String description() {
            return "Orb 订阅摘要导出（一行=一个订阅，GET /subscriptions/summary），"
                    + "字段核证自 docs.withorb.com；日期列存 ISO 8601 原文";
        }

        @Override
        public List<String> requiredHeaders() {
            return List.of(
                    "orb_id",
                    "status",
                    "name",
                    "plan_orb_id",
                    "plan_external_id",
                    "plan_name",
                    "customer_orb_id",
                    "customer_external_id",
                    "start_date",
                    "end_date",
                    "created_at");
        }

        @Override
        public Map<String, ColumnProfile.ColumnSpec> specs() {
            Map<String, ColumnProfile.ColumnSpec> m = new LinkedHashMap<>();
            m.put("orb_id", ColumnProfile.ColumnSpec.of("orb_id", "VARCHAR(64)"));
            m.put("status", ColumnProfile.ColumnSpec.of("status", "VARCHAR(32)"));
            m.put("name", ColumnProfile.ColumnSpec.of("name", "VARCHAR(256)"));
            m.put("plan_orb_id", ColumnProfile.ColumnSpec.of("orb_plan_id", "VARCHAR(64)"));
            m.put("plan_external_id", ColumnProfile.ColumnSpec.of("plan_external_id", "VARCHAR(128)"));
            m.put("plan_name", ColumnProfile.ColumnSpec.of("plan_name", "VARCHAR(256)"));
            m.put("customer_orb_id", ColumnProfile.ColumnSpec.of("orb_customer_id", "VARCHAR(64)"));
            m.put("customer_external_id", ColumnProfile.ColumnSpec.of("customer_external_id", "VARCHAR(128)"));
            m.put("start_date", ColumnProfile.ColumnSpec.of("start_date", "VARCHAR(64)"));
            m.put("end_date", ColumnProfile.ColumnSpec.of("end_date", "VARCHAR(64)"));
            m.put("created_at", ColumnProfile.ColumnSpec.of("created_at", "VARCHAR(64)"));
            return m;
        }
    });

    static {
        // 保险丝：requiredHeaders 与 specs 必须一一对应，构造期就炸出来，不等第一次导入
        for (ColumnProfile profile : BY_ID.values()) {
            for (String header : profile.requiredHeaders()) {
                if (!profile.specs().containsKey(header)) {
                    throw new IllegalStateException("profile " + profile.id()
                            + " 的 requiredHeaders[" + header + "] 在 specs 里没有条目");
                }
            }
        }
    }

    private static ColumnProfile register(ColumnProfile profile) {
        BY_ID.put(profile.id(), profile);
        return profile;
    }

    private ColumnProfiles() {
    }

    /**
     * 按 id 取 profile。空/空白 = 不用 profile（通用导入）；未知 id 直接报错并列出全部可用值
     * —— 拼错 profile 名静默退化成通用导入，金额单位差 100 倍的坑就埋下了。
     *
     * @throws IllegalArgumentException id 非空但不存在
     */
    public static ColumnProfile resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ColumnProfile profile = BY_ID.get(id.trim());
        if (profile == null) {
            throw new IllegalArgumentException("未知 profile: " + id + "，可用: " + String.join(", ", ids()));
        }
        return profile;
    }

    /** 全部内置 profile（MCP 工具描述 / 报错信息用）。 */
    public static List<ColumnProfile> all() {
        return List.copyOf(BY_ID.values());
    }

    /** 全部 profile id，保持注册顺序。 */
    public static List<String> ids() {
        return List.copyOf(BY_ID.keySet());
    }

    /**
     * 指定 profile 的语义摘录摘要（K4）：给归因/规则起草当上下文的一段文本。
     * profile 不存在或没有语义摘录时返回空串（调用方据此跳过，不硬塞段落）。
     */
    public static String semanticsDigest(String profileId) {
        if (profileId == null) {
            return "";
        }
        ColumnProfile profile = BY_ID.get(profileId.trim());
        if (profile == null || profile.billingSemantics().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("厂商计费语义摘录 [").append(profile.id())
                .append(" / ").append(profile.semanticsVersion()).append("]：");
        for (String s : profile.billingSemantics()) {
            sb.append('\n').append("- ").append(s);
        }
        return sb.toString();
    }
}
