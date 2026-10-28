package io.github.dolow.reconlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 看板的接口测试：<b>先真的聊两句，再问统计，数字必须对得上</b>。
 *
 * <p>只断言"接口 200"没有意义——这份测试的价值全在"聊了什么"和"统计报了什么"之间那条线。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:chat_stats;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class ChatStatsApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("聊几句之后_统计能说出意图分布与工具使用")
    void 聊几句之后_统计能说出意图分布与工具使用() throws Exception {
        long sessionId = newSession("stats");
        // 一句一句发、一句一句等：三条 SSE 同时排进 chatExecutor（core 2）时，
        // 第三条会排队，于是"助手轮次没到"看起来像功能坏了，其实是池子被占满（B26）。
        chat(sessionId, "有哪些规则");        // LIST_RULES，调 recon_list_rules
        awaitAssistantTurns(sessionId, 1);
        chat(sessionId, "我能做什么");        // HELP，空手回
        awaitAssistantTurns(sessionId, 2);
        chat(sessionId, "跑一下 BILL_VS_SETTLE"); // RUN，先停在回执（写动作一律要确认）
        awaitAssistantTurns(sessionId, 3);
        chat(sessionId, "确认");                  // 确认后才真的调 recon_run
        awaitAssistantTurns(sessionId, 4);

        JsonNode body = json.readTree(mockMvc.perform(get("/api/chat/stats")
                        .header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.path("totals").path("sessions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("totals").path("assistantTurns").asInt()).isGreaterThanOrEqualTo(3);

        assertThat(body.path("byIntent").path("LIST_RULES").asInt()).isEqualTo(1);
        assertThat(body.path("byIntent").path("HELP").asInt()).isEqualTo(1);
        // 写动作两段式：回执一轮 + 确认后执行一轮，两轮都是 RUN（口径：按执行轮次记，不按"用户说了几句"记）
        assertThat(body.path("byIntent").path("RUN").asInt()).isEqualTo(2);

        assertThat(body.path("byTool").path("recon_list_rules").asInt()).isEqualTo(1);
        assertThat(body.path("byTool").path("recon_run").asInt()).isEqualTo(1);
        assertThat(body.path("byTool").path("recon_list_diffs").asInt()).isZero();

        assertThat(body.path("emptyToolTurns").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("emptyToolRatio").asDouble()).isBetween(0.0, 1.0);
        assertThat(body.path("avgUserTurnsPerSession").asDouble()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("没聊过的会话不计入意图分布_但会计入会话数")
    void 没聊过的会话不计入意图分布_但会计入会话数() throws Exception {
        long before = json.readTree(mockMvc.perform(get("/api/chat/stats").header("X-Operator", "bob"))
                .andReturn().getResponse().getContentAsString()).path("totals").path("sessions").asInt();
        newSession("空会话");
        JsonNode after = json.readTree(mockMvc.perform(get("/api/chat/stats").header("X-Operator", "bob"))
                .andReturn().getResponse().getContentAsString());
        assertThat(after.path("totals").path("sessions").asInt()).isEqualTo((int) before + 1);
        assertThat(after.path("byIntent").path("UNKNOWN").asInt()).isZero();
    }

    /**
     * 等这一轮真的落库。
     *
     * <p>为什么要等：{@code POST /api/chat} 是 SSE，MockMvc 拿到的是"流开始了"，不等到流结束；
     * 不等就会在 half-done 的状态上断言，测出来的是调度快慢而不是功能对不对。
     */
    private void awaitAssistantTurns(long sessionId, int atLeast) throws Exception {
        // 30 秒：SSE 是异步落库，机器忙的时候 10 秒不够（B26 就是这条偶发红）
        for (int i = 0; i < 300; i++) {
            JsonNode body = json.readTree(mockMvc.perform(get("/api/chat/sessions/" + sessionId)
                            .header("X-Operator", "alice"))
                    .andReturn().getResponse().getContentAsString());
            long assistant = 0;
            for (JsonNode turn : body.path("turns")) {
                if ("assistant".equals(turn.path("role").asText())) {
                    assistant++;
                }
            }
            if (assistant >= atLeast) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等了 30 秒，会话 " + sessionId + " 的助手轮次还没到 " + atLeast + " 条");
    }

    // ---------------------------------------------------------------- helpers

    private long newSession(String title) throws Exception {
        MvcResult r = mockMvc.perform(jsonPost("/api/chat/sessions", "{\"title\":\"" + title + "\"}")
                        .header("X-Operator", "alice"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("session").path("id").asLong();
    }

    private void chat(long sessionId, String message) throws Exception {
        mockMvc.perform(jsonPost("/api/chat",
                        "{\"sessionId\":" + sessionId + ",\"message\":\"" + message + "\"}")
                        .header("X-Operator", "alice"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
