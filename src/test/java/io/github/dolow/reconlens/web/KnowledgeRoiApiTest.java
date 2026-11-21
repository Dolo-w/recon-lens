package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 知识层 ROI 端点端到端（v4 验收口径第 1 条：「越用越准可量化」）。
 *
 * <p>钉住四件事：
 * <ul>
 *   <li>归因跑完，四层计数有数：模式库关闭时全部走模板（L2=0，L3=差异总数）；</li>
 *   <li>{@code llmCallsSaved = knowledgeHits + patternHits}（口径一致性）；</li>
 *   <li>人工修正回流后 {@code kbBySource} 出现 HUMAN 计数；</li>
 *   <li>note 里必须写「重启归零」—— 指标不诚实比没有指标更糟。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.explain.patterns.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:knowledge_roi_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class KnowledgeRoiApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long jobId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM recon_diff_kb");
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");

        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count, finished_at) "
                + "VALUES ('BILL_VS_SETTLE', 'b1', 'SUCCESS', 'PROD', 1, '2026-11-20 10:00:00')");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);

        insertDiff("AMOUNT_MISMATCH", "R001", "amount", "100", "125");
        insertDiff("AMOUNT_MISMATCH", "R002", "amount", "50", "60");
    }

    private void insertDiff(String type, String bizKey, String field, String left, String right) {
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, ?, 'HIGH', ?, ?, ?, ?)",
                jobId, type, bizKey, field, left, right);
    }

    @Test
    @DisplayName("归因跑完_四层计数有数_模板兜底计入L3")
    void countersReflectExplainRun() throws Exception {
        mvc.perform(post("/api/diffs/explain").param("jobId", String.valueOf(jobId)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        String body = mvc.perform(get("/api/knowledge/roi"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 计数是进程级累计（多个测试方法共享上下文，执行顺序不保证），钉下界与恒等关系，不钉绝对值
        assertThat(Long.parseLong(extract(body, "templateFallbacks"))).isGreaterThanOrEqualTo(2);
        assertThat(Long.parseLong(extract(body, "patternHits"))).isEqualTo(0);
        assertThat(Long.parseLong(extract(body, "knowledgeHits"))).isEqualTo(0);
        assertThat(Long.parseLong(extract(body, "llmExplained"))).isEqualTo(0);
        assertThat(Long.parseLong(extract(body, "llmCallsSaved"))).isEqualTo(0);
        assertThat(body).contains("重启归零");
    }

    @Test
    @DisplayName("人工修正回流_kbBySource出现HUMAN_闭环口径一致")
    void humanCorrectionShowsInKbBySource() throws Exception {
        mvc.perform(post("/api/diffs/explain").param("jobId", String.valueOf(jobId)));

        long diffId = jdbc.queryForObject(
                "SELECT id FROM recon_diff WHERE biz_key = 'R001'", Long.class);
        mvc.perform(post("/api/diffs/" + diffId + "/attribution")
                .contentType("application/json")
                .content("{\"cause\":\"9月税费口径差异\",\"action\":\"按税费重算\",\"operator\":\"vv\"}"));

        String body = mvc.perform(get("/api/knowledge/roi"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"HUMAN\":1");
    }

    @Test
    @DisplayName("口径一致性_llmCallsSaved等于L1命中加L0联想")
    void llmCallsSavedEqualsKnowledgePlusPatternHits() throws Exception {
        // 先跑一次归因制造模板计数，再用第一次 explain 之后的 kb 状态重跑一次：
        // 第二次 R001/R002 已进 kb（模板不写库，所以仍是模板）——这里只钉公式本身的恒等关系
        mvc.perform(post("/api/diffs/explain").param("jobId", String.valueOf(jobId)));

        String body = mvc.perform(get("/api/knowledge/roi"))
                .andReturn().getResponse().getContentAsString();
        long saved = Long.parseLong(extract(body, "llmCallsSaved"));
        long kb = Long.parseLong(extract(body, "knowledgeHits"));
        long l0 = Long.parseLong(extract(body, "patternHits"));
        assertThat(saved).isEqualTo(kb + l0);
    }

    private static String extract(String body, String field) {
        int i = body.indexOf("\"" + field + "\":");
        if (i < 0) {
            throw new IllegalStateException("字段缺失: " + field);
        }
        int start = i + field.length() + 3;
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)))) {
            end++;
        }
        return body.substring(start, end);
    }
}
