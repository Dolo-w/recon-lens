package io.github.dolow.reconlens.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 表单入口（无 key 用户的主界面）在 HTTP 这一层的口径。
 *
 * <p>这一组测试守的是"<b>没有模型也能把一件事做完</b>"：拉表单 → 提交 → 拿到结果和槽位。
 * 只要它绿着，就说明 ADR-0001 P0 的出口条件里"只用浏览器能走完起草→试算→看差异"这一环
 * 没有偷偷依赖 MCP 宿主或 LLM。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        // 独立内存库：默认 jdbc:h2:mem:recon 会被多个上下文共用，data.sql 重复插入主键
        "spring.datasource.url=jdbc:h2:mem:chat_form_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ChatFormApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 拉表单不需要操作人_十五个动作一个不少() throws Exception {
        mvc.perform(get("/api/chat/forms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(15)))
                .andExpect(jsonPath("$.items[?(@.action=='DEFINE_RULE')].fields[0].required").value(true))
                .andExpect(jsonPath("$.items[?(@.action=='RUN')].label").value("跑一次对账"));
    }

    @Test
    void 表单跑一次_自动开会话并记住槽位() throws Exception {
        String created = mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RUN","values":{"ruleCode":"BILL_VS_SETTLE"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.action").value("RUN"))
                .andExpect(jsonPath("$.text", containsString("任务 #")))
                .andExpect(jsonPath("$.slots.ruleCode").value("BILL_VS_SETTLE"))
                .andExpect(jsonPath("$.slots.jobId").isNumber())
                .andExpect(jsonPath("$.slotsSaved").value(true))
                .andReturn().getResponse().getContentAsString();

        int sessionId = JsonPath.read(created, "$.sessionId");

        // 第二轮只给 action：规则名和任务号都该从槽位里捞出来
        mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":" + sessionId + ",\"action\":\"JOB_SUMMARY\",\"values\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text", not(containsString("哪个任务"))))
                .andExpect(jsonPath("$.slots.jobId").isNumber());

        // 会话里留下的是人话，不是 JSON
        mvc.perform(get("/api/chat/sessions/" + sessionId).header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].content").value(containsString("〔表单〕RUN")));
    }

    @Test
    void 起草缺正文是400_不是建一条空草稿() throws Exception {
        mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DEFINE_RULE\",\"values\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("规则正文")));
    }

    @Test
    void 未知动作列表里带上可选值_免得用户猜() throws Exception {
        mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DROP_TABLE\",\"values\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("未知动作")))
                .andExpect(jsonPath("$.error", containsString("RUN")));
    }

    @Test
    void 数字字段打错字要报出来_不能当成没填() throws Exception {
        mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"JOB_SUMMARY\",\"values\":{\"jobId\":\"1O0\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("要填数字")))
                .andExpect(jsonPath("$.error", containsString("1O0")));
    }

    @Test
    void 表单也不能绕开隔离_别人的会话直接404() throws Exception {
        String created = mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"LIST_RULES\",\"values\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int sessionId = JsonPath.read(created, "$.sessionId");

        mvc.perform(post("/api/chat/actions")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":" + sessionId + ",\"action\":\"LIST_RULES\",\"values\":{}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 缺操作人一律400() throws Exception {
        mvc.perform(post("/api/chat/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"LIST_RULES\",\"values\":{}}"))
                .andExpect(status().isBadRequest());
    }
}
