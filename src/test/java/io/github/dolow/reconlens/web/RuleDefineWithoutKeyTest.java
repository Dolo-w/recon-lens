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
 * 开关打开、但<b>没配密钥</b>：仍然拒绝。
 *
 * <p>这条最容易写错成"打开开关就放行" —— 那等于把 boolean 当鉴权。
 * 开关表达的是"我知道这条通路有风险，仍然要开"；密钥表达的是"只有我知道怎么开"。
 * 两个条件同时成立，这条通路才算"有人负责"。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.rules.legacy-define-enabled=true",
        // 独立内存库（同上：共享 jdbc:h2:mem:recon 会让 data.sql 重复插主键）
        "spring.datasource.url=jdbc:h2:mem:rule_define_no_key;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class RuleDefineWithoutKeyTest {

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
    void 开关打开但没配密钥时仍然拒绝() throws Exception {
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice")
                        .content("{\"code\":\"NO_KEY_RULE\",\"left\":{\"table\":\"t\",\"keyField\":\"k\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(containsString("recon.review.api-key")));

        assertThat(catalog.exists("NO_KEY_RULE")).isFalse();
    }
}
