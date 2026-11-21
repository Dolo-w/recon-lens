package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差异生命周期（豁免台账）HTTP 出口：认领 → 核销/豁免 → 撤销豁免 → 闭环率。
 *
 * <p>重点钉住三件事：
 * <ul>
 *   <li>状态机非法转移 fail-loud（400 带当前状态），绝不静默改写；</li>
 *   <li>RESOLVE / EXEMPT 不带 note 直接 400 —— 台账里一句"处理了"没有内容等于没处理；</li>
 *   <li>闭环率口径：jobId 为空只算 PROD，0/0 时 closureRate 是 null 不是 0%。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:diff_lifecycle_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class DiffLifecycleApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private long jobId;
    private long diffA;
    private long diffB;
    private long diffTrial;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff_kb");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");
        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count) "
                + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1)");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        diffA = insertDiff("SN001", "amount", "100.00", "120.00");
        diffB = insertDiff("SN002", "amount", "50.00", "0.00");
        // 试算任务的差异：全库口径（jobId 不传）必须排除它
        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count) "
                + "VALUES ('bill_match', 'b2', 'SUCCESS', 'TRIAL', 1)");
        long trialJob = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, 'VALUE_MISMATCH', 'LOW', 'T1', 'amount', '1', '2')",
                trialJob);
        diffTrial = jdbc.queryForObject("SELECT MAX(id) FROM recon_diff", Long.class);
    }

    private long insertDiff(String bizKey, String field, String left, String right) {
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, 'VALUE_MISMATCH', 'HIGH', ?, ?, ?, ?)",
                jobId, bizKey, field, left, right);
        return jdbc.queryForObject("SELECT MAX(id) FROM recon_diff", Long.class);
    }

    private MvcResult act(long diffId, String action, String operator, String note) throws Exception {
        return mvc.perform(post("/api/diffs/" + diffId + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", action, "operator", operator, "note", note))))
                .andReturn();
    }

    @Test
    @DisplayName("认领后核销_闭环")
    void claim_then_resolve() throws Exception {
        MvcResult claim = act(diffA, "CLAIM", "finance01", "");
        assertThat(claim.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(claim.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("status").asText())
                .isEqualTo("CLAIMED");

        // 核销不带 note → 400，一句能照着修的话
        mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "RESOLVE", "operator", "finance01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "核销必须写原因（note）：这条差异最后是怎么处理掉的"));

        MvcResult resolve = act(diffA, "RESOLVE", "finance01", "漏记优惠，已补账");
        assertThat(resolve.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(resolve.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(body.get("operator").asText()).isEqualTo("finance01");
        assertThat(body.get("note").asText()).isEqualTo("漏记优惠，已补账");

        // RESOLVED 是终态：再豁免 → 非法转移，400 且报出当前状态
        mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "EXEMPT", "operator", "finance01", "note", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("RESOLVED")));
    }

    @Test
    @DisplayName("豁免后撤销_回到OPEN可重新处置")
    void exempt_then_unexempt() throws Exception {
        assertThat(act(diffB, "EXEMPT", "finance02", "两侧口径差异，属已知例外").getResponse().getStatus())
                .isEqualTo(200);
        // 豁免状态下不能认领
        mvc.perform(post("/api/diffs/" + diffB + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "CLAIM", "operator", "finance02"))))
                .andExpect(status().isBadRequest());
        MvcResult unexempt = act(diffB, "UNEXEMPT", "audit02", "审计要求重新核");
        assertThat(unexempt.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(unexempt.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("status").asText())
                .isEqualTo("OPEN");

        // 撤销后历史完整：EXEMPT → UNEXEMPT 两条都在，谁做的都在
        MvcResult history = mvc.perform(get("/api/diffs/" + diffB + "/lifecycle")).andReturn();
        JsonNode items = json.readTree(history.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("history");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("action").asText()).isEqualTo("UNEXEMPT");
        assertThat(items.get(1).get("operator").asText()).isEqualTo("finance02");
    }

    @Test
    @DisplayName("闭环率_全库排除试算_0差异时rate为null")
    void closure_rate() throws Exception {
        act(diffA, "CLAIM", "finance01", "");
        act(diffA, "RESOLVE", "finance01", "补账完成");

        MvcResult all = mvc.perform(get("/api/diffs/lifecycle/summary")).andReturn();
        JsonNode body = json.readTree(all.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("total").asLong()).isEqualTo(2); // 试算差异被排除
        assertThat(body.get("byStatus").get("RESOLVED").asLong()).isEqualTo(1);
        assertThat(body.get("byStatus").get("OPEN").asLong()).isEqualTo(1);
        assertThat(body.get("closureRate").asDouble()).isEqualTo(0.5);

        MvcResult one = mvc.perform(get("/api/diffs/lifecycle/summary").param("jobId", String.valueOf(jobId)))
                .andReturn();
        assertThat(json.readTree(one.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("total").asLong()).isEqualTo(2);

        // 没有任何差异的任务：closureRate 是 null，不冒充 0% 也不冒充 100%
        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count) "
                + "VALUES ('bill_match', 'b3', 'SUCCESS', 'PROD', 1)");
        long empty = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        MvcResult emptyBody = mvc.perform(get("/api/diffs/lifecycle/summary")
                .param("jobId", String.valueOf(empty))).andReturn();
        JsonNode emptyJson = json.readTree(emptyBody.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        // 全局 NON_NULL 时 null 字段整个省略：字段缺失或 null 都算通过，不冒充 0% 也不冒充 100%
        assertThat(!emptyJson.has("closureRate") || emptyJson.get("closureRate").isNull()).isTrue();
    }

    @Test
    @DisplayName("列表withStatus带处置状态_没碰过的差异是OPEN")
    void list_with_status() throws Exception {
        act(diffA, "CLAIM", "finance01", "");
        MvcResult res = mvc.perform(get("/api/diffs").param("withStatus", "true")).andReturn();
        JsonNode items = json.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("items");
        assertThat(items).hasSize(2);
        for (JsonNode item : items) {
            if (item.get("id").asLong() == diffA) {
                assertThat(item.get("lifecycleStatus").asText()).isEqualTo("CLAIMED");
            } else {
                assertThat(item.get("lifecycleStatus").asText()).isEqualTo("OPEN");
            }
        }
    }

    @Test
    @DisplayName("不存在的差异_400不撞库")
    void unknown_diff() throws Exception {
        mvc.perform(post("/api/diffs/999999/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "CLAIM", "operator", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("差异不存在: 999999"));
        mvc.perform(get("/api/diffs/999999/lifecycle"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("operator缺失_400")
    void operator_required() throws Exception {
        mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "CLAIM", "operator", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "operator 必填 —— 不知道谁改的，就没法追溯"));
    }

    @Test
    @DisplayName("未知action_400列出可用值")
    void unknown_action() throws Exception {
        mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("action", "DELETE", "operator", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("CLAIM / RELEASE / RESOLVE / EXEMPT / UNEXEMPT")));
    }

    // ------------------------------------------------------------------
    // K1 知识回流：核销/豁免的定性结论 teach 进 recon_diff_kb（HUMAN/0.9）

    /**
     * 核销动作三件事一起验证：台账落了 reasonId、知识库多了 HUMAN/0.9 条目、
     * 差异说明从模型猜测换成人工结论。三者缺一，回流就是摆设。
     */
    @Test
    @DisplayName("核销回流知识库_HUMAN条目加说明同步")
    void resolve_teaches_backflow() throws Exception {
        MvcResult resolve = mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "action", "RESOLVE", "operator", "finance01",
                                "note", "两侧单位不一致，账单侧少乘了汇率系数",
                                "reasonId", "UNIT_SCALE_X100"))))
                .andReturn();
        assertThat(resolve.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(resolve.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("reasonId").asText()).isEqualTo("UNIT_SCALE_X100");

        // 知识库：HUMAN 来源 + 0.9 置信度 + 原因就是核销原因
        // 注意 rule_code 落库是 CauseKey.normalize 后的大写（BILL_MATCH），字符串比较不分标识符大小写
        java.util.Map<String, Object> kb = jdbc.queryForMap(
                "SELECT source, confidence, cause FROM recon_diff_kb WHERE rule_code = 'BILL_MATCH'");
        assertThat(kb.get("source")).isEqualTo("HUMAN");
        assertThat(new java.math.BigDecimal(kb.get("confidence").toString())
                .compareTo(new java.math.BigDecimal("0.900"))).isEqualTo(0);
        assertThat((String) kb.get("cause")).isEqualTo("两侧单位不一致，账单侧少乘了汇率系数");

        // 差异说明同步成人工结论（界面上不能还挂着模型那句）
        String explanation = jdbc.queryForObject(
                "SELECT explanation FROM recon_diff WHERE id = ?", String.class, diffA);
        assertThat(explanation).isEqualTo("两侧单位不一致，账单侧少乘了汇率系数");
    }

    @Test
    @DisplayName("豁免回流_动作语义是可豁免")
    void exempt_teaches_backflow() throws Exception {
        assertThat(act(diffB, "EXEMPT", "finance02", "两侧口径差异，属已知例外").getResponse().getStatus())
                .isEqualTo(200);
        java.util.Map<String, Object> kb = jdbc.queryForMap(
                "SELECT action FROM recon_diff_kb WHERE rule_code = 'BILL_MATCH'");
        assertThat((String) kb.get("action")).isEqualTo("此类差异已确认可豁免");
    }

    @Test
    @DisplayName("reasonId超长_400")
    void reason_id_too_long() throws Exception {
        mvc.perform(post("/api/diffs/" + diffA + "/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "action", "RESOLVE", "operator", "finance01", "note", "x",
                                "reasonId", "R".repeat(65)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "reasonId 超过 64 字符：它是分类 ID 不是说明文"));
    }
}
