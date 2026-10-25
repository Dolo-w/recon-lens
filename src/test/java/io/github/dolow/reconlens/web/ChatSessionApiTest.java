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

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话隔离在 HTTP 这一层的口径（ADR-0001 N8）。
 *
 * <p>为什么非要一个接口测试：{@code SessionScope} 的单元测试只能证明"查不到"，
 * 证明不了"查不到的时候状态码是 404 而不是 403"。而<b>状态码本身就是信息</b>——
 * 404 与 403 的区别一旦落到线上，拿一串自增 id 扫一遍就能问出哪些会话存在。
 *
 * <p>同时钉住"404 的响应体里不许出现标题"：会话标题在真实使用里就是「8 月对账 王老板」这种字符串。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        // 独立内存库：默认库 jdbc:h2:mem:recon 会被多个上下文共用，data.sql 重复插入主键
        "spring.datasource.url=jdbc:h2:mem:chat_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ChatSessionApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private io.github.dolow.reconlens.chat.ChatClaims chatClaims;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 别人的私有会话一律404_且响应体里没有标题() throws Exception {
        String created = mvc.perform(post("/api/chat/sessions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"8 月对账 王老板\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.session.visibility").value("PRIVATE"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.session.id");

        mvc.perform(get("/api/chat/sessions/" + id).header("X-Operator", "bob"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("王老板"))));

        mvc.perform(post("/api/chat/sessions/" + id + "/messages")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"content\":\"我是同事\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 共享之后_能看但写进去是403() throws Exception {
        String created = mvc.perform(post("/api/chat/sessions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"要交接的会话\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.session.id");

        mvc.perform(post("/api/chat/sessions/" + id + "/visibility")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"SHARED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.visibility").value("SHARED"));

        mvc.perform(get("/api/chat/sessions/" + id).header("X-Operator", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(id));

        mvc.perform(post("/api/chat/sessions/" + id + "/messages")
                        .header("X-Operator", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"content\":\"我接着问一句\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 缺X_Operator直接400_不落无主会话() throws Exception {
        mvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"没有操作人\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
    @Test
    void 轮次分页_默认最后一页_并且带游标() throws Exception {
        int id = openSession("分页会话");
        for (int i = 1; i <= 12; i++) {
            say(id, "第 " + i + " 句");
        }

        mvc.perform(get("/api/chat/sessions/" + id + "?limit=5").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns.length()").value(5))
                .andExpect(jsonPath("$.turns[0].seq").value(8))
                .andExpect(jsonPath("$.turns[4].seq").value(12))
                .andExpect(jsonPath("$.turnsHasMore").value(true))
                .andExpect(jsonPath("$.nextBefore").value(8));

        mvc.perform(get("/api/chat/sessions/" + id + "?limit=5&before=8").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].seq").value(3))
                .andExpect(jsonPath("$.turns[4].seq").value(7));

        mvc.perform(get("/api/chat/sessions/" + id + "?limit=5&before=3").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns.length()").value(2))
                .andExpect(jsonPath("$.turnsHasMore").value(false))
                .andExpect(jsonPath("$.nextBefore").doesNotExist());
    }

    @Test
    void 手动压一次摘要_落库并回显水位线() throws Exception {
        int id = openSession("摘要会话");
        say(id, "跑一下 BILL_VS_SETTLE");
        say(id, "为什么差这么多");

        mvc.perform(post("/api/chat/sessions/" + id + "/summary").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("问（2）：为什么差这么多")))
                .andExpect(jsonPath("$.summaryUptoSeq").value(2));

        mvc.perform(get("/api/chat/sessions/" + id).header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.summaryUptoSeq").value(2))
                .andExpect(jsonPath("$.session.summary").isNotEmpty());
    }

    @Test
    void 别人压不了我的摘要() throws Exception {
        int id = openSession("别人的会话");
        say(id, "问一句");

        mvc.perform(post("/api/chat/sessions/" + id + "/summary").header("X-Operator", "bob"))
                .andExpect(status().isNotFound());
    }

    private int openSession(String title) throws Exception {
        String created = mvc.perform(post("/api/chat/sessions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.session.id");
    }

    private void say(int id, String content) throws Exception {
        mvc.perform(post("/api/chat/sessions/" + id + "/messages")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"user\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 从头翻_拿到第一条并且能继续往后走() throws Exception {
        int id = openSession("从头翻会话");
        for (int i = 1; i <= 12; i++) {
            say(id, "第 " + i + " 句");
        }

        mvc.perform(get("/api/chat/sessions/" + id + "?limit=5&from=1").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns.length()").value(5))
                .andExpect(jsonPath("$.turns[0].seq").value(1))
                .andExpect(jsonPath("$.turnsHasMore").value(false))
                .andExpect(jsonPath("$.nextBefore").doesNotExist())
                .andExpect(jsonPath("$.turnsHasMoreAfter").value(true))
                .andExpect(jsonPath("$.nextAfter").value(6));

        mvc.perform(get("/api/chat/sessions/" + id + "?limit=50&before=5").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turns[0].seq").value(1))
                .andExpect(jsonPath("$.turns[3].seq").value(4))
                .andExpect(jsonPath("$.turnsHasMoreAfter").value(true))
                .andExpect(jsonPath("$.nextAfter").value(5));
    }

    @Test
    void 当前有效结论_按会话开出来_后说的覆盖先说的() throws Exception {
        String created = mvc.perform(post("/api/chat/sessions")
                        .header("X-Operator", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"结论板\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.session.id");

        chatClaims.record(id, "draft:ORDER_STATUS_RECON", "维度 order_no，比 amount（第 3 轮）", 3);
        chatClaims.record(id, "draft:ORDER_STATUS_RECON", "维度 order_no，比 status（第 10 轮）", 10);

        mvc.perform(get("/api/chat/sessions/" + id + "/claims").header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].seq").value(10))
                .andExpect(jsonPath("$.items[0].value", org.hamcrest.Matchers.containsString("比 status")))
                .andExpect(jsonPath("$.block", org.hamcrest.Matchers.containsString("〔当前有效〕")));

        mvc.perform(get("/api/chat/sessions/" + id + "/claims").header("X-Operator", "bob"))
                .andExpect(status().isNotFound());
    }
}