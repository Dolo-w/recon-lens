package io.github.dolow.reconlens.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨会话检索（ADR-0001 N9）：默认只搜自己，team 必须显式传，别人的 PRIVATE 永远搜不到。
 *
 * <p>为什么这些用例必须走 HTTP 而不是直接调 service：N9 的一半内容在"不传 scope 会发生什么"，
 * 那是绑定层的默认值，service 里看不到。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:chat_recall;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class ChatRecallApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long newSession(String operator, String title) throws Exception {
        MvcResult result = mvc().perform(post("/api/chat/sessions")
                        .header("X-Operator", operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.session.id");
        return id.longValue();
    }

    private void say(String operator, long sessionId, String text) throws Exception {
        mvc().perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header("X-Operator", operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"content\":\"" + text + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 默认只搜自己的会话_别人的内容不会出现() throws Exception {
        long alice = newSession("alice", "私有会话");
        say("alice", alice, "这条差异我怀疑是刷单");
        long bob = newSession("bob", "bob 的会话");
        say("bob", bob, "我也怀疑这笔是刷单");

        String body = mvc().perform(get("/api/chat/recall").param("q", "刷单")
                        .header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("MINE"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("bob 的会话");
        assertThat(body).doesNotContain("私有会话");
        assertThat(body).contains("只看我自己的会话");
    }

    @Test
    void team_范围能看到别人的共享会话_并且每条命中带_owner() throws Exception {
        long alice = newSession("alice", "alice 的共享会话");
        say("alice", alice, "手工冲销的口径要问财务");
        mvc().perform(post("/api/chat/sessions/" + alice + "/visibility")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"SHARED\"}"))
                .andExpect(status().isOk());
        long bob = newSession("bob", "bob 的会话");
        say("bob", bob, "我想看看别人怎么处理手工冲销");

        mvc().perform(get("/api/chat/recall").param("q", "手工冲销").param("scope", "team")
                        .header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TEAM"))
                .andExpect(jsonPath("$.items[0].owner").exists())
                .andExpect(jsonPath("$.description").value(
                        org.hamcrest.Matchers.containsString("团队范围")));
    }

    @Test
    void team_范围也搜不到别人的_private_会话() throws Exception {
        long alice = newSession("alice", "alice 的私有会话");
        say("alice", alice, "这句只有我自己能看到番茄炒蛋");
        long bob = newSession("bob", "bob 的会话");
        say("bob", bob, "起个会话占位");

        // 断言看 total 而不是"响应里有没有那四个字"：检索词本身就在 body 的 q 字段里，
        // 拿字符串匹配等于自己骗自己（第一版就是这么写红的）
        mvc().perform(get("/api/chat/recall").param("q", "番茄炒蛋").param("scope", "team")
                        .header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void scope_写错要_400_而不是猜() throws Exception {
        newSession("alice", "校验会话");
        mvc().perform(get("/api/chat/recall").param("q", "差异").param("scope", "everyone")
                        .header("X-Operator", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("scope 只能是 mine 或 team")));
    }

    @Test
    void 缺操作人_400_空检索词_400() throws Exception {
        mvc().perform(get("/api/chat/recall").param("q", "差异"))
                .andExpect(status().isBadRequest());
        mvc().perform(get("/api/chat/recall").param("q", " ").header("X-Operator", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("检索词不能为空")));
    }
}
