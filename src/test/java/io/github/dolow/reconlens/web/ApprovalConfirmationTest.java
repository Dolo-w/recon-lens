package io.github.dolow.reconlens.web;

import com.jayway.jsonpath.JsonPath;
import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.rule.RuleLifecycleService;
import io.github.dolow.reconlens.rule.RuleTrialRunner;
import io.github.dolow.reconlens.store.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一次性确认码：Agent 只转发、不审批。
 *
 * <p>这组用例钉的是"<b>模型编不出码</b>"这条线 —— 没有码就发不出审批，
 * 码只能用一次，并且绑定 (code, version, decision)。曾经 {@code recon_submit_approval}
 * 靠一句自填的"用户已同意"就能 47ms 批掉一条规则。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_confirmation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        "recon.review.api-key=test-key",
        "recon.review.token-secret=test-secret",
        // 试算服务挂在 agent 开关下；这里直接调用 runner 把结论造出来
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right"
})
class ApprovalConfirmationTest {

    private static final String CODE = "CONFIRMATION_TEST";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private RuleLifecycleService lifecycle;

    @Autowired
    private RuleTrialRunner trialRunner;

    @Autowired
    private RuleRepository rules;

    private MockMvc mvc;
    private int version;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Map<String, Object> body = new RuleParser().readYaml("""
                code: CONFIRMATION_TEST
                name: 确认码测试
                left:
                  label: 账单
                  table: bill_left
                  keyType: STRING
                  dimension: [customer_id, order_no]
                right:
                  label: 结算
                  table: bill_right
                  keyType: STRING
                  dimension: [customer_id, order_no]
                shard:
                  count: 1
                  parallelism: 1
                compare:
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                  severity: HIGH
                """, "单测");
        version = catalog.defineDraft(body, "单测", "alice", RuleOrigin.HUMAN, null, "确认码用例").version();
        trialRunner.run(CODE, version);
    }

    @Test
    void 用户在REST领码后Agent才能带着码完成审批() throws Exception {
        String issued = mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "wang")
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationCode").isNotEmpty())
                .andExpect(jsonPath("$.issuedBy").value("wang"))
                .andExpect(jsonPath("$.ttlSeconds").value(300))
                // 签发不改状态：拿到码 ≠ 批了
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(issued, "$.confirmationCode");

        lifecycle.confirmApproval(CODE, version, "APPROVED", "wang", code, "E2E 人工确认");

        assertThat(rules.findByVersion(CODE, version).orElseThrow().lifecycle()).isEqualTo(RuleLifecycle.ACTIVE);
    }

    @Test
    void 同一个确认码不能用第二次() throws Exception {
        String issued = mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "wang")
                        .content("{\"decision\":\"APPROVED\"}"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(issued, "$.confirmationCode");

        lifecycle.confirmApproval(CODE, version, "APPROVED", "wang", code, null);

        assertThatThrownBy(() -> lifecycle.confirmApproval(CODE, version, "APPROVED", "wang", code, null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("已经被用过");
    }

    @Test
    void 确认码绑定决策方式改一个字母都不行() throws Exception {
        String issued = mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Key", "test-key")
                        .header("X-Operator", "wang")
                        .content("{\"decision\":\"APPROVED\"}"))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(issued, "$.confirmationCode");

        assertThatThrownBy(() -> lifecycle.confirmApproval(CODE, version, "REJECTED", "wang", code, null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("绑定的不是");
        // 用错一次不核销：码仍可用于原本的决策
        lifecycle.confirmApproval(CODE, version, "APPROVED", "wang", code, null);
        assertThat(rules.findByVersion(CODE, version).orElseThrow().lifecycle()).isEqualTo(RuleLifecycle.ACTIVE);
    }

    @Test
    void 没有API密钥就领不到确认码() throws Exception {
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 编出来的码服务端不认() {
        assertThatThrownBy(() -> lifecycle.confirmApproval(CODE, version, "APPROVED", "wang", "00000000", null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("确认码");
    }
}
