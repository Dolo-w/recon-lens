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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预处理物化的 REST 入口：建模型表 → 用生成的 {@code <CODE>__MODEL} 规则发起对账 → 回看模型表清单；
 * 未知规则和不合法 shards 要是 400，不能变成 500。
 * <p>
 * 用例共享同一个内存库（{@code jdbc:h2:mem:recon;DB_CLOSE_DELAY=-1}），所以模型表的构建次数、模型规则的版本号
 * 会随用例顺序变化 —— 断言只认"是什么/对不对"，不认"第几次/第几版"。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class ModelApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void buildModelThenRunItOverHttp() throws Exception {
        mvc.perform(post("/api/models/BILL_VS_SETTLE").param("shards", "4").param("operator", "alice"))
                .andExpect(status().isOk())
                // 后缀必须是大写：规则 code 只允许 [A-Z][A-Z0-9_]*
                .andExpect(jsonPath("$.modelRuleCode").value("BILL_VS_SETTLE__MODEL"))
                .andExpect(jsonPath("$.ruleCode").value("BILL_VS_SETTLE"))
                .andExpect(jsonPath("$.shards").value(4))
                .andExpect(jsonPath("$.tables.length()").value(2))
                .andExpect(jsonPath("$.tables[0].side").value("left"))
                .andExpect(jsonPath("$.tables[0].rows").value(20))
                .andExpect(jsonPath("$.tables[0].table").value(containsString("recon_model_bill_vs_settle_")))
                .andExpect(jsonPath("$.tables[1].side").value("right"))
                .andExpect(jsonPath("$.tables[1].rows").value(21));

        // 读模型表跑出来的差异，必须和原规则逐条一致（条数 + 分类计数）
        String job = """
                {"ruleCode": "BILL_VS_SETTLE__MODEL", "batchId": "api-model", "sync": true}
                """;
        String body = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(job))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.diffRows").value(6))
                .andReturn().getResponse().getContentAsString();
        long jobId = Long.parseLong(body.replaceAll("(?s).*\"jobId\"\\s*:\\s*(\\d+).*", "$1"));

        mvc.perform(get("/api/diffs/summary").param("jobId", String.valueOf(jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.byType.AMOUNT_MISMATCH").value(2))
                .andExpect(jsonPath("$.byType.STATUS_MISMATCH").value(1))
                .andExpect(jsonPath("$.byType.MISSING_LEFT").value(1))
                .andExpect(jsonPath("$.byType.MISSING_RIGHT").value(1))
                .andExpect(jsonPath("$.byType.DUPLICATE_KEY").value(1));

        mvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(greaterThanOrEqualTo(2)))
                .andExpect(content().string(containsString("BILL_VS_SETTLE__MODEL")))
                .andExpect(content().string(containsString("alice")));
    }

    @Test
    void unknownRuleIsABadRequestNotA500() throws Exception {
        mvc.perform(post("/api/models/NO_SUCH_RULE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("model_build_failed"))
                .andExpect(jsonPath("$.message").value(containsString("未找到规则 NO_SUCH_RULE")));
    }

    @Test
    void shardsOutOfRangeIsABadRequest() throws Exception {
        mvc.perform(post("/api/models/BILL_VS_SETTLE").param("shards", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("shards 必须在 1 ~")));
    }
}
