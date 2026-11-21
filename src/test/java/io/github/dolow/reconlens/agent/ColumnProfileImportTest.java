package io.github.dolow.reconlens.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厂商 profile 导入（连接器层第二步）端到端：按 Lago 源码核证的真实导出表头构造 CSV，
 * preview 命中映射 → confirm 建表灌数 → 表里是改名后的列 + 分换算成元的金额。
 * 表尾多余列（厂商可选列）走通用推断，两套机制不互斥。
 */
class ColumnProfileImportTest {

    private static JdbcTemplate jdbc;
    private static FileImporter importer;

    @TempDir
    static Path importDir;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:column_profile_import;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL, schema_hash VARCHAR(64), row_count BIGINT,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        AgentProperties properties = new AgentProperties();
        properties.getFileImport().setDir(importDir.toString());
        SchemaCatalog schema = new SchemaCatalog(ds, properties,
                new io.github.dolow.reconlens.store.RegisteredTableRepository(jdbc));
        TableRegistrar registrar = new TableRegistrar(schema,
                new io.github.dolow.reconlens.store.RegisteredTableRepository(jdbc), jdbc);
        importer = new FileImporter(properties, registrar, jdbc);
    }

    private static void writeCsv(String name, String content) throws IOException {
        Files.writeString(importDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** Lago invoice_fees.rb base_headers 的前几列 + 关键金额列，逐字对齐源码。 */
    private static final String LAGO_FEE_HEADERS = """
            invoice_lago_id,invoice_number,invoice_issuing_date,fee_lago_id,fee_item_type,fee_item_code,\
            fee_item_name,fee_item_description,fee_item_invoice_display_name,fee_item_filter_invoice_display_name,\
            fee_item_grouped_by,subscription_external_id,subscription_plan_code,fee_from_date,fee_to_date,\
            fee_amount_currency,fee_units,fee_precise_unit_amount,fee_taxes_amount_cents,fee_total_amount_cents""";

    /** Lago invoices.rb base_headers 全 29 列，逐字对齐源码。 */
    private static final String LAGO_INVOICE_HEADERS = """
            lago_id,sequential_id,partner_billing,issuing_date,customer_lago_id,customer_external_id,\
            customer_name,customer_email,customer_country,customer_tax_identification_number,invoice_number,\
            purchase_order_number,invoice_type,payment_status,status,file_url,currency,fees_amount_cents,\
            coupons_amount_cents,taxes_amount_cents,credit_notes_amount_cents,prepaid_credit_amount_cents,\
            total_amount_cents,payment_due_date,payment_dispute_lost_at,payment_overdue,total_due_amount_cents,\
            total_paid_amount_cents,total_offsetted_credit_note_amount_cents""";

    @Test
    void lago_fee明细_改名加_分换算成元() throws IOException {
        writeCsv("lago_fees.csv", LAGO_FEE_HEADERS + """
                
                inv_001,INV-2026-001,2026-11-01,fee_001,subscription,plan_pro,专业版,,专业版订阅,,,"cus-001",plan_pro,2026-11-01,2026-11-30,CNY,1,999900,8000,1000000
                inv_001,INV-2026-001,2026-11-01,fee_002,add_on,token_pack,Token 包,,Token 加油包,,,"cus-001",plan_pro,2026-11-01,2026-11-30,CNY,2.5,0.04,0,123456
                """);
        FileImporter.ImportPreview p = importer.preview("lago_fees.csv", null, "lago-invoice-fees");
        assertThat(p.error()).isNull();
        // 改名 + 强制类型
        assertThat(p.columns()).containsSubsequence("lago_invoice_id", "invoice_number", "issuing_date",
                "item_code", "units", "unit_amount", "tax_amount", "total_amount");
        assertThat(p.columnTypes().get(p.columns().indexOf("total_amount"))).isEqualTo("DECIMAL(24,6)");
        assertThat(p.columnTypes().get(p.columns().indexOf("issuing_date"))).isEqualTo("DATETIME");
        // 说明里有换算
        assertThat(p.notes()).anySatisfy(n -> assertThat(n).contains("lago-invoice-fees"));
        assertThat(p.notes()).anySatisfy(n -> assertThat(n).contains("total_amount").contains("分 → 元"));

        FileImporter.ImportPreview done = importer.confirm("lago_fees.csv", "recon_lago_fees", "alice", null,
                "lago-invoice-fees");
        assertThat(done.error()).isNull();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT invoice_number, item_code, units, total_amount, tax_amount FROM recon_lago_fees "
                        + "ORDER BY item_code");
        assertThat(rows).hasSize(2);
        // ORDER BY item_code：plan_pro 在前（1000000 分 → 10000.00 元），token_pack 在后（123456 分 → 1234.56 元）
        assertThat((BigDecimal) rows.get(0).get("total_amount")).isEqualByComparingTo("10000.000000");
        assertThat((BigDecimal) rows.get(0).get("tax_amount")).isEqualByComparingTo("80.000000");
        assertThat((BigDecimal) rows.get(1).get("total_amount")).isEqualByComparingTo("1234.560000");
        assertThat((BigDecimal) rows.get(1).get("units")).isEqualByComparingTo("2.5");
    }

    @Test
    void 表尾追加的厂商可选列走通用推断() throws IOException {
        // Lago invoices.rb base_headers 全 29 列 + 开启 progressive billing 后表尾追加的列；
        // 再塞一个带逗号的 note 验证 RFC4180 解析与通用推断照常生效
        writeCsv("lago_inv.csv", LAGO_INVOICE_HEADERS + ",progressive_billing_credit_amount_cents,note"
                + """
                
                lgo_1,1,false,2026-11-01,cus_lgo_1,cus-001,客户一,a@b.c,CN,,INV-1,PO-1,subscription,succeeded,finalized,http://f/CNY,,500000,0,0,0,0,500000,2026-12-01,,false,500000,0,0,25000,"合计, 含优惠"
                lgo_2,2,false,2026-11-02,cus_lgo_1,cus-001,客户一,a@b.c,CN,,INV-2,PO-1,subscription,succeeded,finalized,http://f/CNY,,120000,0,0,0,0,120000,2026-12-01,,false,120000,0,0,0,"另一行"
                """);
        FileImporter.ImportPreview p = importer.preview("lago_inv.csv", null, "lago-invoices");
        assertThat(p.error()).isNull();
        assertThat(p.columns().indexOf("fees_amount")).isGreaterThanOrEqualTo(0);
        assertThat(p.columns()).contains("progressive_billing_credit_amount_cents");
        assertThat(p.columnTypes().get(p.columns().indexOf("progressive_billing_credit_amount_cents")))
                .isEqualTo("BIGINT");

        FileImporter.ImportPreview done = importer.confirm("lago_inv.csv", "recon_lago_inv", "alice", null,
                "lago-invoices");
        assertThat(done.error()).isNull();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fees_amount, total_amount, progressive_billing_credit_amount_cents, note "
                        + "FROM recon_lago_inv WHERE invoice_number = 'INV-1'");
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("5000.000000");
        assertThat((BigDecimal) row.get("fees_amount")).isEqualByComparingTo("5000.000000");
        assertThat(row.get("progressive_billing_credit_amount_cents")).isEqualTo(25000L);
        assertThat(row.get("note")).isEqualTo("合计, 含优惠");
    }

    @Test
    void 表头不匹配_报错列出期望表头() throws IOException {
        // 20 列但第一列就不是 Lago 的表头 —— 真不匹配，不是列数不够
        writeCsv("not_lago.csv", LAGO_FEE_HEADERS.replace("invoice_lago_id", "not_lago_id") + """
                
                SN001,INV-1,2026-11-01,fee_001,subscription,plan_pro,专业版,,专业版,,,"cus-001",plan_pro,2026-11-01,2026-11-30,CNY,1,999900,8000,1000000
                """);
        FileImporter.ImportPreview p = importer.preview("not_lago.csv", null, "lago-invoice-fees");
        assertThat(p.error()).isNotNull();
        assertThat(p.error()).contains("不匹配").contains("第 1 列期望 \"invoice_lago_id\"，实际 \"not_lago_id\"")
                .contains("lago-invoice-fees");
        // 没有建任何表
        assertThat(importer.tableExists("recon_not_lago")).isFalse();
    }

    @Test
    void 列数不够_报错() throws IOException {
        writeCsv("short.csv", """
                invoice_lago_id,invoice_number
                inv_001,INV-1
                """);
        FileImporter.ImportPreview p = importer.preview("short.csv", null, "lago-invoices");
        assertThat(p.error()).isNotNull();
        assertThat(p.error()).contains("不够 profile lago-invoices 要求的");
    }

    @Test
    void 未知profile_报错并列出可用值_绝不静默退化() throws IOException {
        writeCsv("any.csv", """
                a,b
                1,2
                """);
        FileImporter.ImportPreview p = importer.preview("any.csv", null, "lago-invoce-fees"); // 拼错
        assertThat(p.error()).isNotNull();
        assertThat(p.error()).contains("未知 profile").contains("lago-invoice-fees, lago-invoices");
        // 不带 profile 的通用导入照常可用
        FileImporter.ImportPreview generic = importer.preview("any.csv");
        assertThat(generic.error()).isNull();
    }

    @Test
    void profile列的坏值fail_loud_报错带行号() throws IOException {
        // total_amount_cents 混进非数字 → 灌数必须炸回滚，不能静默 NULL（金额列的静默 NULL 就是假差异）
        writeCsv("bad_lago.csv", LAGO_FEE_HEADERS + """
                
                inv_001,INV-1,2026-11-01,fee_001,subscription,plan_pro,专业版,,专业版,,,"cus-001",plan_pro,2026-11-01,2026-11-30,CNY,1,999900,8000,not_a_number
                """);
        FileImporter.ImportPreview done = importer.confirm("bad_lago.csv", "recon_lago_bad", "alice", null,
                "lago-invoice-fees");
        assertThat(done.error()).isNotNull();
        assertThat(done.error()).contains("灌数失败");
        assertThat(importer.tableExists("recon_lago_bad")).isFalse();
    }
}
