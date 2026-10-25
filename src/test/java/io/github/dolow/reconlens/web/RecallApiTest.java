package io.github.dolow.reconlens.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:recall_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class RecallApiTest {

    @Autowired
    private MockMvc mvc;

    private static final String MANUAL = """
            结算口径说明。账期口径：账单侧以入账时间为准，结算侧以结算时间为准，跨月自然产生时间差。
            金额口径：手续费与汇率进位在结算侧才发生，因此 0.01 元以内属于正常。
            重复键口径：同一业务键在同一批次出现两次以上，多数是重复入账或消息重放，按幂等键去重后重跑。
            """;

    @Test
    void 导入口径文档后_用中文原话能检索到并标出命中词() throws Exception {
        mvc.perform(post("/api/docs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"结算口径说明\",\"source\":\"finance\",\"text\":\"" + MANUAL.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunks").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        String body = mvc.perform(get("/api/docs/search").param("q", "手续费 进位"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retriever").value(org.hamcrest.Matchers.containsString("关键词")))
                // 没配 recon.recall.embedding.url 时，链上第一层就不可用，必须如实说出来
                .andExpect(jsonPath("$.mode").value("FULLTEXT"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("未配置向量模型")))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.items[0].title").value("结算口径说明"))
                .andReturn().getResponse().getContentAsString();
        assertThat((Iterable<String>) JsonPath.read(body, "$.items[0].matched")).isNotEmpty();
    }

    @Test
    void 同一篇文档重复导入是幂等的() throws Exception {
        String payload = "{\"title\":\"幂等测试\",\"source\":\"finance\",\"text\":\"同一段话。\"}";
        mvc.perform(post("/api/docs").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/docs").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyIndexed").value(true));
    }

    @Test
    void 人确认案例必须带操作人_否则400() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"diffType\":\"AMOUNT_MISMATCH\","
                                + "\"causeKey\":\"C002\",\"resolution\":\"手续费进位\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("X-Operator")));
    }

    @Test
    void 写回案例后_按规则和类型检索出来_分数小于等于可信度() throws Exception {
        mvc.perform(post("/api/cases").header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"diffType\":\"MISSING_RIGHT\","
                                + "\"causeKey\":\"C008|ORD20260009\",\"resolution\":\"对方延迟入账，已确认\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidence").value(0.9))
                .andExpect(jsonPath("$.source").value("HUMAN"));

        mvc.perform(get("/api/cases/recall").param("ruleCode", "BILL_VS_SETTLE").param("diffType", "MISSING_RIGHT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resolution").value("对方延迟入账，已确认"))
                .andExpect(jsonPath("$.items[0].operator").value("alice"))
                .andExpect(jsonPath("$.items[0].score").value(org.hamcrest.Matchers.lessThanOrEqualTo(0.95)));
    }

    @Test
    void 模型自述的案例落05_且没有操作人() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"diffType\":\"DUPLICATE_KEY\","
                                + "\"causeKey\":\"C005\",\"resolution\":\"疑似重复入账\",\"source\":\"LLM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confidence").value(0.5))
                .andExpect(jsonPath("$.source").value("LLM"));
    }

    @Test
    void 案例可以带上数据版本_按版本查时别的版本不出现() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice")
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"diffType\":\"AMOUNT_MISMATCH\","
                                + "\"causeKey\":\"C002\",\"resolution\":\"旧版本口径\","
                                + "\"datasetVersion\":\"v-old\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice")
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"diffType\":\"AMOUNT_MISMATCH\","
                                + "\"causeKey\":\"C002\",\"resolution\":\"这一版口径\","
                                + "\"datasetVersion\":\"v-new\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/cases/recall").param("ruleCode", "BILL_VS_SETTLE")
                        .param("diffType", "AMOUNT_MISMATCH").param("datasetVersion", "v-new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetVersion").value("v-new"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].resolution").value("这一版口径"))
                .andExpect(jsonPath("$.items[0].datasetVersion").value("v-new"));
    }

    @Test
    void 没有正文的文档_400() throws Exception {
        mvc.perform(post("/api/docs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"空文档\",\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
