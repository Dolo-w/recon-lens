package io.github.dolow.reconlens.tool;

import io.github.dolow.reconlens.pricing.PriceCatalogService;
import io.github.dolow.reconlens.domain.PriceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2.5 三组能力的 MCP 工具层端到端（价格表 / 生命周期台账 / 证据包 / Lago 连接器）。
 *
 * <p>直接调 {@link ToolRegistry#invoke} —— 与宿主大模型走的是同一条路，测的就是
 * 「Agent 一句话查差异/出证据包」这句话本身能不能落地。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.export.dir=target/phase2-tool-test-export",
        "spring.datasource.url=jdbc:h2:mem:phase2_tools;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class Phase2ToolInvocationTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceCatalogService priceCatalog;

    private long jobId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM recon_price_entry");
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");

        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count, finished_at) "
                + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1, '2026-11-16 10:00:00')");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, 'AMOUNT_MISMATCH', 'HIGH', 'B001', "
                        + "'output_tokens', '500000', '1000000')",
                jobId);
        priceCatalog.create(new PriceEntry(null, "openai", "gpt-4o", "OUTPUT",
                new BigDecimal("4"), "CNY", java.time.Instant.parse("2026-11-15T00:00:00Z"),
                null, null, null, null, null));
    }

    @Test
    @DisplayName("recon_price_impact_一句话出少收的钱")
    void priceImpactToolEndToEnd() {
        ToolOutcome outcome = registry.invoke(ToolNames.PRICE_IMPACT, Map.of(
                "jobId", jobId, "priceCode", "openai", "model", "gpt-4o", "tokenType", "OUTPUT"));

        assertThat(outcome.text()).contains("金额影响合计").contains("-2"); // 0.5M×4 - 1M×4 = -2（少收）
        assertThat(new java.math.BigDecimal((String) outcome.structured().get("totalImpact")))
                .isEqualByComparingTo("-2"); // DECIMAL 标度，比数值不比字面量
        assertThat(outcome.structured()).containsEntry("pricedCount", 1).containsEntry("diffTotal", 1);
    }

    @Test
    @DisplayName("recon_diff_lifecycle_豁免后SUMMARY闭环率")
    void lifecycleExemptThenSummary() {
        long diffId = jdbc.queryForObject("SELECT id FROM recon_diff WHERE biz_key = 'B001'", Long.class);
        registry.invoke(ToolNames.DIFF_LIFECYCLE, Map.of(
                "action", "EXEMPT", "diffId", diffId, "operator", "vv", "note", "客户已确认"));

        ToolOutcome summary = registry.invoke(ToolNames.DIFF_LIFECYCLE,
                Map.of("action", "SUMMARY", "jobId", jobId));
        assertThat(summary.text()).contains("闭环率 100%").contains("EXEMPTED：1 条");
    }

    @Test
    @DisplayName("recon_evidence_pack_落盘文件可读_带BOM")
    void evidencePackWritesFile() throws Exception {
        ToolOutcome outcome = registry.invoke(ToolNames.EVIDENCE_PACK, Map.of(
                "jobId", jobId, "priceCode", "openai", "model", "gpt-4o", "tokenType", "OUTPUT"));

        String path = outcome.text().replaceAll("(?s).*文件：(\\S+)\\n.*", "$1");
        Path file = Path.of(path);
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content.startsWith("\uFEFF")).isTrue();
        assertThat(content).contains("B001").contains("金额影响");
    }

    @Test
    @DisplayName("recon_lago_sync_未知资源_报错列出可用值")
    void lagoSyncUnknownResourceThrows() {
        assertThatThrownBy(() -> registry.invoke(ToolNames.LAGO_SYNC, Map.of("resource", "plans")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fees, invoices");
    }

    @Test
    @DisplayName("recon_list_prices_空表说人话指引录入")
    void listPricesEmptyHasGuidance() {
        jdbc.update("DELETE FROM recon_price_entry");
        ToolOutcome outcome = registry.invoke(ToolNames.LIST_PRICES, Map.of());
        assertThat(outcome.text()).contains("价格表还没有条目").contains("POST /api/prices");
    }
}
