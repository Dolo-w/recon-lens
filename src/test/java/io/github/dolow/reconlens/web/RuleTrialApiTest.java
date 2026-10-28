package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.rule.RuleCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闸门 1 拒绝时的<b>对外形态</b>：不是一句中文，而是 422 + 结构化违规。
 *
 * <p>为什么要专门测这个：违规信息以前被拼进 {@code toString()}，REST 只能靠正则从中文里抠，
 * {@code location} / {@code suggestion} 全丢。调用方拿到"试算失败"时无法区分
 * "规则碰不到数据"（闸门 1，语句根本没发出去）和"跑了但结果不对"（闸门 2）——
 * 这两件事的处置完全相反。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.review.api-key=test-key",
        "recon.agent.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:rule_trial_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class RuleTrialApiTest {

    private static final String CODE = "TRIAL_API_GHOST";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RuleCatalog catalog;

    private MockMvc mvc;
    private int version;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Map<String, Object> body = new RuleParser().readYaml(ghostTableYaml(), "单测");
        // defineDraft 只做结构校验：闸门 1 的"表在不在白名单"要等试算前才查，这正是要测的那一步
        version = catalog.defineDraft(body, "单测", "alice", RuleOrigin.AGENT, null, "幽灵表草稿").version();
    }

    @Test
    void 试算被闸门一拒绝时回四百二十二和结构化违规() throws Exception {
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/trial")
                        .header("X-Api-Key", "test-key"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("gate_rejected"))
                .andExpect(jsonPath("$.ruleCode").value(CODE))
                .andExpect(jsonPath("$.ruleVersion").value(version))
                .andExpect(jsonPath("$.violations[0].code").value("UNKNOWN_TABLE"))
                .andExpect(jsonPath("$.violations[0].location").value("left.table"))
                .andExpect(jsonPath("$.action").exists());
    }

    @Test
    void 没有密钥就没有试算通道() throws Exception {
        mvc.perform(post("/api/rule-reviews/" + CODE + "/" + version + "/trial"))
                .andExpect(status().isForbidden());
    }

    private static String ghostTableYaml() {
        return """
                code: %s
                name: 幽灵表试算
                left: {label: L, table: bill_ghost, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(CODE);
    }
}
