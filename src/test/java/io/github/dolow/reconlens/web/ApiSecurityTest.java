package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据面写接口的鉴权：配了 {@code recon.security.api-key} 就必须带 {@code X-Api-Key}。
 *
 * <p>这三条曾经全是裸奔的：{@code POST /api/jobs}（能跑任意规则）、
 * {@code POST /api/models/{ruleCode} }（能落一条 ACTIVE 规则）、{@code DELETE /api/rules/{code}}。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        "recon.security.api-key=test-key"
})
class ApiSecurityTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 跑批接口不带密钥被拒() throws Exception {
        mvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"batchId\":\"security-test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Key", "wrong")
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"batchId\":\"security-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 建模接口不带密钥被拒() throws Exception {
        mvc.perform(post("/api/models/BILL_VS_SETTLE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 删规则接口不带密钥被拒() throws Exception {
        mvc.perform(delete("/api/rules/CUSTOMER_SUM_RECON"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 读接口不受写密钥影响() throws Exception {
        mvc.perform(get("/api/rules/BILL_VS_SETTLE"))
                .andExpect(status().isOk());
    }
}
