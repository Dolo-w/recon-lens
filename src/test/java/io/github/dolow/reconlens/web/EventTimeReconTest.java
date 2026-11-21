package io.github.dolow.reconlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 事件级对账端到端：规则配 {@code eventTime} → 差异带业务发生时刻 →
 * 计价按<b>业务发生时刻</b>匹配价格（月中调价归因的核心）。
 *
 * <p>钉住四件事：
 * <ul>
 *   <li>差异落库带 event_at（原样字符串，不猜时区）；</li>
 *   <li>不传 at 时，两条落在不同调价窗口的差异<b>各自</b>按各自事件时间的单价计价——
 *       任务完成时刻兜底做不到这一点（两条会被算到同一个价上）；</li>
 *   <li>显式传 at 仍然最高优先（人主动指定 > 事件时间）；</li>
 *   <li>证据包带「事件时间」「计价时点」两列，证据链完整。</li>
 * </ul>
 *
 * <p>事件时间解析不了的回退（乱串 → 任务时刻）在 {@code parseEventAt} 单测里钉。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.rules-location=classpath:rules-test/",
        "spring.datasource.url=jdbc:h2:mem:event_time_recon;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class EventTimeReconTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM recon_price_entry");
        jdbc.update("DELETE FROM recon_diff_evidence");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");
        jdbc.execute("DROP TABLE IF EXISTS et_left");
        jdbc.execute("DROP TABLE IF EXISTS et_right");
        jdbc.execute("CREATE TABLE et_left (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32), "
                + "output_tokens DECIMAL(20,4), event_time DATETIME)");
        jdbc.execute("CREATE TABLE et_right (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32), "
                + "output_tokens DECIMAL(20,4))");
        // 两行业务时间分别落在两个调价窗口
        jdbc.update("INSERT INTO et_left (order_no, output_tokens, event_time) VALUES "
                + "('E1', 500000, '2026-03-15 10:00:00'), ('E2', 500000, '2026-07-01 00:00:00')");
        jdbc.update("INSERT INTO et_right (order_no, output_tokens) VALUES "
                + "('E1', 1000000), ('E2', 1000000)");
        // 月中调价：3-15 的业务按旧价 2，7-01 的按新价 4
        jdbc.update("INSERT INTO recon_price_entry (price_code, model, token_type, unit_price_per_million, "
                + "currency, effective_from, effective_to) VALUES "
                + "('LLM', 'm', 'OUTPUT', 2, 'CNY', '2026-01-01 00:00:00', '2026-06-01 00:00:00'), "
                + "('LLM', 'm', 'OUTPUT', 4, 'CNY', '2026-06-01 00:00:00', NULL)");
    }

    private long runRule() throws Exception {
        String body = mvc.perform(post("/api/jobs").contentType("application/json")
                        .content("{\"ruleCode\":\"EVENT_TIME_RECON\",\"batchId\":\"et-test\",\"sync\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("jobId").asLong();
    }

    @Test
    @DisplayName("差异带事件时间_计价按业务发生时刻各自匹配调价窗口")
    void diffsCarryEventTimeAndImpactMatchesEachWindow() throws Exception {
        long jobId = runRule();

        List<java.util.Map<String, Object>> rows = jdbc.query(
                "SELECT biz_key, event_at FROM recon_diff ORDER BY biz_key",
                (rs, i) -> java.util.Map.of("bizKey", rs.getString("biz_key"), "eventAt", rs.getString("event_at")));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("bizKey", "E1").containsEntry("eventAt", "2026-03-15 10:00:00.0");
        assertThat(rows.get(1)).containsEntry("bizKey", "E2").containsEntry("eventAt", "2026-07-01 00:00:00.0");

        // 不传 at：每条按各自事件时间的单价计价（E1×2、E2×4），任务完成时刻兜底做不到
        String body = mvc.perform(post("/api/prices/impact").contentType("application/json")
                        .content("{\"jobId\":" + jobId + ",\"priceCode\":\"LLM\",\"model\":\"m\",\"tokenType\":\"OUTPUT\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode items = json.readTree(body).path("items");
        assertThat(items).hasSize(2);
        assertThat(new java.math.BigDecimal(items.get(0).path("unitPricePerMillion").asText()))
                .isEqualByComparingTo("2");
        assertThat(new java.math.BigDecimal(items.get(0).path("impact").asText()))
                .isEqualByComparingTo("-1");   // 0.5M×2 - 1M×2
        assertThat(new java.math.BigDecimal(items.get(1).path("unitPricePerMillion").asText()))
                .isEqualByComparingTo("4");
        assertThat(new java.math.BigDecimal(items.get(1).path("impact").asText()))
                .isEqualByComparingTo("-2");   // 0.5M×4 - 1M×4
        // 计价时点证据带出：事件时间原样回传
        assertThat(items.get(0).path("eventAt").asText()).contains("2026-03-15");
        assertThat(items.get(0).path("pricedAt").asText()).startsWith("2026-03-15");
    }

    @Test
    @DisplayName("显式at优先于事件时间_人主动指定最高")
    void explicitAtBeatsEventTime() throws Exception {
        long jobId = runRule();
        String body = mvc.perform(post("/api/prices/impact").contentType("application/json")
                        .content("{\"jobId\":" + jobId + ",\"priceCode\":\"LLM\",\"model\":\"m\",\"tokenType\":\"OUTPUT\","
                                + "\"at\":\"2026-05-20T00:00:00Z\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode items = json.readTree(body).path("items");
        assertThat(items).hasSize(2);
        // 2026-05-20 在旧价窗 [01-01, 06-01) 内 → 两条都被按 2 算（人指定的时点说了算）
        assertThat(new java.math.BigDecimal(items.get(0).path("unitPricePerMillion").asText()))
                .isEqualByComparingTo("2");
        assertThat(new java.math.BigDecimal(items.get(1).path("unitPricePerMillion").asText()))
                .isEqualByComparingTo("2");
        assertThat(items.get(1).path("pricedAt").asText()).isEqualTo("2026-05-20T00:00:00Z");
    }

    @Test
    @DisplayName("证据包带事件时间与计价时点列")
    void evidencePackCarriesEventAndPricedAt() throws Exception {
        long jobId = runRule();
        byte[] bytes = mvc.perform(get("/api/jobs/" + jobId + "/evidence-pack.csv?priceCode=LLM&model=m&tokenType=OUTPUT"))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = new EvidencePackApiTestHelper().parse(new String(bytes, StandardCharsets.UTF_8));
        assertThat(rows.get(0)).containsSubsequence("事件时间", "计价时点");
        int evAt = rows.get(0).indexOf("事件时间");
        int prAt = rows.get(0).indexOf("计价时点");
        List<List<String>> data = rows.subList(1, rows.size());
        assertThat(data).anySatisfy(r -> {
            assertThat(r.get(evAt)).startsWith("2026-03-15");
            assertThat(r.get(prAt)).startsWith("2026-03-15");
        });
    }

    @Test
    @DisplayName("parseEventAt_ISO与日期形态可解析_乱串回退null")
    void parseEventAtCoversForms() {
        assertThat(io.github.dolow.reconlens.pricing.PriceImpactService.parseEventAt("2026-07-01T00:00:00Z"))
                .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(io.github.dolow.reconlens.pricing.PriceImpactService.parseEventAt("2026-07-01 12:30:00"))
                .isEqualTo(Instant.parse("2026-07-01T12:30:00Z")); // 按 UTC 解析，不猜本地时区
        assertThat(io.github.dolow.reconlens.pricing.PriceImpactService.parseEventAt("not-a-time")).isNull();
        assertThat(io.github.dolow.reconlens.pricing.PriceImpactService.parseEventAt(null)).isNull();
    }

    /** 简易 RFC4180 解析（与 EvidencePackApiTest 同一套拆列逻辑的独立实现，避免测试间耦合）。 */
    private static final class EvidencePackApiTestHelper {
        List<List<String>> parse(String text) {
            List<List<String>> rows = new java.util.ArrayList<>();
            List<String> cur = new java.util.ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    cur.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n') {
                    cur.add(cell.toString());
                    rows.add(cur);
                    cur = new java.util.ArrayList<>();
                    cell.setLength(0);
                } else if (c != '\r') {
                    cell.append(c);
                }
            }
            if (cell.length() > 0 || !cur.isEmpty()) {
                cur.add(cell.toString());
                rows.add(cur);
            }
            return rows;
        }
    }
}
