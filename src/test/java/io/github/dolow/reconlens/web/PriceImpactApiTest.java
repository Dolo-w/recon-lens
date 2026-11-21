package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 计量漂移归因端到端：差异 × 价格 = "少收的钱"。
 *
 * <p>钉住四件事：
 * <ul>
 *   <li>job 级汇总：可计价差异逐条乘价 + 汇总金额；状态类差异跳过并如实计数；</li>
 *   <li>月中调价回溯在归因里同样生效（按请求 at / 任务完成时刻匹配窗）；</li>
 *   <li>单条计价 fail-loud：状态类差异没有金额语义，400 说明白；</li>
 *   <li>impact 方向语义：负数 = 少收（left 我方口径 - right 对方口径）。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:price_impact_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class PriceImpactApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private long jobId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM recon_price_entry");
        jdbc.update("DELETE FROM recon_diff_evidence");
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");

        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count, finished_at) "
                + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1, '2026-11-16 10:00:00')");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);

        // 月中调价：11-15 前旧价 8.0，11-15 起新价 4.0（每百万）
        mvc.perform(post("/api/prices").contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT","unitPricePerMillion":8,
                 "currency":"CNY","effectiveFrom":"2026-11-01T00:00:00Z","effectiveTo":"2026-11-15T00:00:00Z"}
                """));
        mvc.perform(post("/api/prices").contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT","unitPricePerMillion":4,
                 "currency":"CNY","effectiveFrom":"2026-11-15T00:00:00Z"}
                """));

        // 差异 1：我方 2M tokens vs 对方 1.9M（漂移），任务完成时刻 11-16 → 新价 4.0
        insertDiff("AMOUNT_MISMATCH", "B001", "output_tokens", "2000000", "1900000");
        // 差异 2：我方少记 0.5M（少收），旧价 8.0（显式 at=11-14）
        insertDiff("AMOUNT_MISMATCH", "B002", "output_tokens", "500000", "1000000");
        // 差异 3：状态类，无数值语义 → 汇总跳过 / 单条 400
        insertDiff("STATUS_MISMATCH", "B003", "status", "PAID", "REFUNDED");
    }

    private void insertDiff(String type, String bizKey, String field, String left, String right) {
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, ?, 'HIGH', ?, ?, ?, ?)",
                jobId, type, bizKey, field, left, right);
    }

    @Test
    void jobSummaryPricesEveryNumericDiffAndSkipsStatusOnes() throws Exception {
        String body = mvc.perform(post("/api/prices/impact").contentType("application/json").content("""
                {"jobId":%d,"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT"}
                """.formatted(jobId)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("diffTotal").asInt()).isEqualTo(3);
        assertThat(root.get("pricedCount").asInt()).isEqualTo(2);
        assertThat(root.get("skippedCount").asInt()).isEqualTo(1);
        assertThat(root.get("skipped").get(0).asText()).contains("STATUS_MISMATCH");

        // 差异 1（at 缺省=任务完成时刻 11-16 → 新价 4.0）：left 2M×4 = 8，right 1.9M×4 = 7.6，impact +0.4
        // 差异 2 在汇总里也按任务时刻（新价 4.0）：left 0.5M×4=2，right 1M×4=4，impact -2（少收）
        assertThat(root.get("totalImpact").asDouble()).isEqualTo(-1.6);
        assertThat(root.get("impactNote").asText()).contains("少收");
    }

    @Test
    void explicitAtOverridesJobTimestampForRetroactivePricing() throws Exception {
        // 显式 at=11-14（旧价 8.0）作用于全部差异：
        // B001 left 2M×8=16 right 1.9M×8=15.2 → +0.8；B002 left 0.5M×8=4 right 1M×8=8 → -4；合计 -3.2
        String body = mvc.perform(post("/api/prices/impact").contentType("application/json").content("""
                {"jobId":%d,"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT",
                 "at":"2026-11-14T00:00:00Z"}
                """.formatted(jobId)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("totalImpact").asDouble()).isEqualTo(-3.2);
        assertThat(root.get("at").asText()).isEqualTo("2026-11-14T00:00:00Z");
    }

    @Test
    void singleDiffImpactCarriesQuoteEvidence() throws Exception {
        long diffId = jdbc.queryForObject(
                "SELECT id FROM recon_diff WHERE biz_key = 'B001'", Long.class);
        String body = mvc.perform(post("/api/diffs/" + diffId + "/price-impact")
                        .contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT"}
                """))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("leftAmount").asDouble()).isEqualTo(8.0);
        assertThat(root.get("rightAmount").asDouble()).isEqualTo(7.6);
        assertThat(root.get("impact").asDouble()).isEqualTo(0.4);
        // 命中条目证据一起带回：争议证据包直接引用"适用价格"
        assertThat(root.get("unitPricePerMillion").asDouble()).isEqualTo(4.0);
        assertThat(root.get("effectiveFrom").asText()).isEqualTo("2026-11-15T00:00:00Z");
    }

    @Test
    void statusDiffHasNoAmountSemanticsIs400() throws Exception {
        long diffId = jdbc.queryForObject(
                "SELECT id FROM recon_diff WHERE biz_key = 'B003'", Long.class);
        String body = mvc.perform(post("/api/diffs/" + diffId + "/price-impact")
                        .contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT"}
                """))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json.readTree(body).get("error").asText()).contains("没有金额语义");
    }
}
