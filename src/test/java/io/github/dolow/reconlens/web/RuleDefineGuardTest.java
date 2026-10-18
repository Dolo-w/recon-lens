package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.rule.RuleCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/rules} 默认关闭（评审 P0-1）。
 *
 * <p>它落库即 ACTIVE，闸门 1（结构/安全）、闸门 2（影子试算）、闸门 3（人工审批）全都不经过。
 * 留着它是为了兼容引入 Agent 之前的人工/CI 通路，但它不能是"默认可用"的状态 ——
 * 否则同一个系统里，最危险的那条写入路径反而是唯一不需要密钥的。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        // 独立内存库：默认库是 jdbc:h2:mem:recon（DB_CLOSE_DELAY=-1），多个上下文共用时 data.sql 会重复插入主键
        "spring.datasource.url=jdbc:h2:mem:rule_define_guard;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class RuleDefineGuardTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RuleCatalog catalog;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 默认配置下直写规则返回403且不落库() throws Exception {
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice")
                        .header("X-Api-Key", "whatever")
                        .content("{\"code\":\"GUARDED_RULE\",\"left\":{\"table\":\"t\",\"keyField\":\"k\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("legacy-define-enabled")))
                .andExpect(jsonPath("$.remediation").value(containsString("草稿")));

        assertThat(catalog.exists("GUARDED_RULE")).as("被拒的请求不能留下半条规则").isFalse();
    }
}
