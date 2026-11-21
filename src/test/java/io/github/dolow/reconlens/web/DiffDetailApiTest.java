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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 差异详情出口：差异本体 + 两侧原始行凭证。
 *
 * <p>钉住三件事：
 * <ul>
 *   <li>凭证按自然键 (job, shard, biz_key) 关联 —— 左右两侧各一份，缺侧只给一侧；</li>
 *   <li>凭证是差异落库时刻的整行快照（含非比对列），复盘不用回源表；</li>
 *   <li>不存在的差异 404，不冒充空对象。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:diff_detail_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class DiffDetailApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private long diffId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM recon_diff_evidence");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");
        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count) "
                + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1)");
        long jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        jdbc.update("""
                INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name,
                                        left_value, right_value, delta_value)
                VALUES (?, 0, 'AMOUNT_MISMATCH', 'HIGH', 'A1', 'amount', '3000', '2999', 1)
                """, jobId);
        diffId = jdbc.queryForObject("SELECT MAX(id) FROM recon_diff", Long.class);
        jdbc.update("""
                INSERT INTO recon_diff_evidence (job_id, shard_id, side, biz_key, source, row_json)
                VALUES (?, 0, 'LEFT', 'A1', '账单(bill_left)',
                        '{"order_no":"A1","amount":"3000","memo":"含非比对列"}'),
                       (?, 0, 'RIGHT', 'A1', '结算(bill_right)',
                        '{"order_no":"A1","amount":"2999"}')
                """, jobId, jobId);
    }

    @Test
    void detailCarriesDiffBodyAndBothSidesEvidence() throws Exception {
        String body = mvc.perform(get("/api/diffs/" + diffId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("diffType").asText()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(root.get("bizKey").asText()).isEqualTo("A1");
        assertThat(root.get("deltaValue").asDouble()).isEqualTo(1.0);

        JsonNode evidence = root.get("evidence");
        assertThat(evidence.get("left").get("rowJson").asText()).contains("memo").contains("含非比对列");
        assertThat(evidence.get("right").get("rowJson").asText()).contains("2999");
        assertThat(evidence.get("left").get("source").asText()).contains("bill_left");
    }

    @Test
    void missingSideEvidenceIsAnEmptyObjectNotAnError() throws Exception {
        // 单边缺失差异（真实引擎里缺失的 bizKey 不会有对侧数据，自然键只关联到一侧凭证）：
        // detail 照样 200，缺的那侧是空对象
        long jobId = jdbc.queryForObject("SELECT MAX(job_id) FROM recon_diff", Long.class);
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, left_value) "
                + "VALUES (?, 0, 'MISSING_RIGHT', 'HIGH', 'B2', '', 'B2 单据')", jobId);
        jdbc.update("INSERT INTO recon_diff_evidence (job_id, shard_id, side, biz_key, source, row_json) "
                + "VALUES (?, 0, 'LEFT', 'B2', '账单(bill_left)', '{\"order_no\":\"B2\"}')", jobId);
        long missingId = jdbc.queryForObject(
                "SELECT MAX(id) FROM recon_diff WHERE diff_type = 'MISSING_RIGHT'", Long.class);
        String body = mvc.perform(get("/api/diffs/" + missingId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("evidence").get("left").get("rowJson").asText()).contains("B2");
        // 缺的一侧是空 JSON 对象，不是 null 也不是报错
        assertThat(root.get("evidence").get("right").isObject()).isTrue();
        assertThat(root.get("evidence").get("right").size()).isZero();
    }

    @Test
    void unknownDiffIs404() throws Exception {
        mvc.perform(get("/api/diffs/999999"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void listEndpointStillWorksAndStaysLean() throws Exception {
        // 列表不带凭证（口径不变），详情才带 —— 50 条列表每条背 8KB JSON 会把响应顶爆
        String body = mvc.perform(get("/api/diffs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        assertThat(root.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(root.get("items").size()).isGreaterThanOrEqualTo(1);
        assertThat(root.get("items").get(0).has("evidence")).isFalse();
    }
}
