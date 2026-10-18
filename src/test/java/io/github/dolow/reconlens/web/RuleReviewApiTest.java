package io.github.dolow.reconlens.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审批入口的边界：<b>没有密钥就没有审批通道</b>，而且审批 token 绑定内容哈希。
 *
 * <p>这不是"接口功能测试"：如果 {@code X-Api-Key} 没配时接口仍然可用，
 * 闸门 3 就退化成"任何能发 HTTP 的进程都能发布规则"，前面所有设计都白做。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.review.api-key=test-key",
        "recon.review.token-secret=test-secret",
        // 独立内存库：默认库是 jdbc:h2:mem:recon（DB_CLOSE_DELAY=-1），多个上下文共用时 data.sql 会重复插入主键
        "spring.datasource.url=jdbc:h2:mem:review_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class RuleReviewApiTest {

    private static final String CODE = "REVIEW_API_TEST";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private RuleRepository trials;

    private MockMvc mvc;
    private int version;
    private String token;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Map<String, Object> body = new RuleParser().readYaml(yamlWithTolerance("0.01"), "单测");
        version = catalog.defineDraft(body, "单测", "alice", RuleOrigin.AGENT, null, "接口测试草稿").version();
    }

    @Test
    void 没有API密钥就没有审批通道() throws Exception {
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/submit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/submit")
                        .header("X-Api-Key", "wrong-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 审核页能直接看到字段级diff与跳过资格() throws Exception {
        version = catalog.defineDraft(new RuleParser().readYaml(nameOnlyYaml(), "单测"),
                "单测", "alice", RuleOrigin.HUMAN, version, "改文案").version();

        mvc.perform(get("/api/rule-reviews/" + CODE + "/" + version)
                        .header("X-Api-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.semantic").value(false))
                .andExpect(jsonPath("$.skippable").value(true));
    }

    @Test
    void 完整审批链路_跑完试算才能生效() throws Exception {
        submitAndGetToken();

        String approveBody = json.writeValueAsString(Map.of("token", token, "note", "看过了"));

        // ① 没跑试算就批准：M1 期间这里能过（调用方自己声称 PASS + FULL），M2 把这条通路关掉了。
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/approve")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("还没跑试算")));

        // ② 闸门 2 把结论写进 trial_*（判定链本身在 ShadowTrialServiceTest 里验，
        //    这里只验"批不批看库、不看请求"）。
        trials.saveTrial(CODE, version,
                new TrialState.Trialed(TrialVerdict.PASS, Coverage.FULL, List.of()),
                "sha256:test", "{}",
                io.github.dolow.reconlens.rule.SpecHasher.sha256(
                        trials.findByVersion(CODE, version).orElseThrow().specJson()));

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/approve")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"));

        assertThat(catalog.resolve(CODE).version()).isEqualTo(version);
    }

    @Test
    void 请求体里自己声称PASS_FULL不算数() throws Exception {
        submitAndGetToken();

        // 旧版接口的字段还在请求里 —— 必须被忽略，而不是被当成试算结论。
        // 这条用例是"人工声称"的回归防线：字段一旦被重新接回 DTO，这里立刻变绿失败地转红。
        String forged = json.writeValueAsString(Map.of(
                "token", token,
                "verdict", "PASS",
                "coverage", "FULL",
                "reasons", List.of()));

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/approve")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("还没跑试算")));

        assertThat(trials.findTrial(CODE, version).orElseThrow().verdict()).isNull();
    }

    @Test
    void 错误token被拒() throws Exception {
        submitAndGetToken();

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/approve")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", "forged"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("审批 token 无效")));
    }

    @Test
    void 跳过试算与批准是两个动作() throws Exception {
        // 只改名字的第二版：唯一允许跳过的情形
        int v2 = catalog.defineDraft(new RuleParser().readYaml(nameOnlyYaml(), "单测"),
                "单测", "alice", RuleOrigin.HUMAN, version, "改文案").version();
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/submit")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "alice"))
                .andExpect(status().isOk());

        // 不写理由 → 拒。理由不是形式主义：没有它就分不出"跳过了"和"还没跑"。
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/skip")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", " "))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/skip")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "只改了标题文案"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialVerdict").value("SKIPPED"));

        // 跳过之后审批不再需要签字（NO_SIGNOFF_REQUIRED），但审核记录上留着"跳过了"
        String token2 = JsonPath.read(mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/token")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.approvalToken");

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/approve")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"));

        assertThat(trials.findTrial(CODE, v2).orElseThrow().skipReason()).isEqualTo("只改了标题文案");
    }

    @Test
    void 语义变更不允许跳过试算() throws Exception {
        int v2 = catalog.defineDraft(new RuleParser().readYaml(yamlWithTolerance("0.5"), "单测"),
                "单测", "alice", RuleOrigin.HUMAN, version, "放宽容差").version();
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/submit")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "alice"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + v2 + "/skip")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "我觉得没事"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("SKIPPED_NOT_ALLOWED")));
    }

    private void submitAndGetToken() throws Exception {
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/submit")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "alice"))
                .andExpect(status().isOk());
        token = JsonPath.read(mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/token")
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.approvalToken");
    }

    private static String nameOnlyYaml() {
        return """
                code: %s
                name: 审批接口测试（改名）
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(CODE);
    }

    private static String yamlWithTolerance(String tolerance) {
        return """
                code: %s
                name: 审批接口测试
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: %s, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(CODE, tolerance);
    }
}
