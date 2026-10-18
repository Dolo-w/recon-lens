package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认配置下 {@code POST /api/models} 生成的 {@code <规则>__MODEL} 必须是<b>草稿</b>：
 * 建表和"规则生效"是两件事，前者要密钥，后者要试算 + 人工审批。
 *
 * <p>这条用例故意不配 {@code recon.model.require-approval=false}（默认 true），
 * 和 {@link ModelApiTest} 形成对照：那个用例验的是"放开审批后的"完整链路。
 *
 * <p>本上下文没开 {@code recon.agent.enabled}，所以没有试算能力 —— 响应里不该出现 runId，
 * 而应明确告诉使用者"这个部署无法自动试算，需要人工审批"。
 *
 * <p>用独立的 H2 库名：默认库 {@code jdbc:h2:mem:recon} 带 {@code DB_CLOSE_DELAY=-1}，
 * 同一 JVM 里多个 Spring 上下文会共用它，{@code data.sql} 第二次执行直接撞主键。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:model_approval;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ModelApprovalTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void generatedModelRuleIsADraftAndCannotRunBeforeApproval() throws Exception {
        mvc.perform(post("/api/models/BILL_VS_SETTLE").param("shards", "2").param("operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelRuleLifecycle").value("DRAFT"))
                .andExpect(jsonPath("$.hint").value(containsString("草稿")))
                .andExpect(jsonPath("$.hint").value(containsString("无法自动试算")));

        String job = """
                {"ruleCode": "BILL_VS_SETTLE__MODEL", "sync": true}
                """;
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(job))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("BILL_VS_SETTLE__MODEL")));
    }
}
