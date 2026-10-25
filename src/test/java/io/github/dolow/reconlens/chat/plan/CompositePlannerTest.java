package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型优先理解 + 词表快路径 + 熔断，用**真的 HTTP** 测，不 mock 客户端。
 *
 * <p>为什么不用 mock：这一层的风险全在"网络坏了怎么办"——超时、非 200、响应体不是 JSON。
 * mock 掉客户端等于把要测的东西测没了。本地起一个 {@link HttpServer} 既真实又不用外网。
 */
class CompositePlannerTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> nextBody = new AtomicReference<>(ok("""
            {"action":"LIST_RULES","confidence":0.88,"reason":"用户想看看有哪些规则","options":[]}
            """));
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);

    @BeforeEach
    void startServer() throws IOException {
        calls.set(0);
        nextStatus.set(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String ok(String content) {
        try {
            return "{\"choices\":[{\"message\":{\"content\":" + new ObjectMapper().writeValueAsString(content) + "}}]}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompositePlanner planner() {
        LlmChatClient client = new LlmChatClient(baseUrl(), "sk-test", "deepseek-chat", 2000, json);
        return new CompositePlanner(new KeywordPlanner(new ChatIntentRouter()), new LlmPlanner(client, json));
    }

    @Test
    @DisplayName("短问句 + 词表确定命中：走快路径，不额外问模型")
    void 词表命中不打网络() {
        ChatPlan plan = planner().plan("有哪些规则", ChatSlots.EMPTY).orElseThrow();
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.KEYWORD);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("长问句即使词表命中，也先让模型理解一遍")
    void 长问句也走模型() {
        ChatPlan plan = planner().plan("帮我把所有的规则都列出来看看", ChatSlots.EMPTY).orElseThrow();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.LLM);
    }

    @Test
    @DisplayName("挂着待答问题时，哪怕只是一句「就这两个吧」也交给模型理解")
    void 挂起时交给模型() {
        ChatSlots slots = ChatSlots.EMPTY.withPending(ChatIntent.DEFINE_RULE.name(),
                java.util.Map.of("question", "左侧用哪张表？"));
        planner().plan("就这两个吧", slots);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("词表没听懂才交给模型，来源标记为 LLM")
    void 词表失手走模型() {
        ChatPlan plan = planner().plan("这摊子事儿你帮我瞅瞅", ChatSlots.EMPTY).orElseThrow();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.LLM);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(plan.reason()).contains("规则");
    }

    @Test
    @DisplayName("模型 500 时安静退回词表，不让会话变成错误页")
    void 模型挂了退回词表() {
        nextStatus.set(500);
        ChatPlan plan = planner().plan("这摊子事儿你帮我瞅瞅", ChatSlots.EMPTY).orElseThrow();
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.KEYWORD);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.HELP);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("连续失败三次熔断，之后不再打网络")
    void 熔断() {
        nextStatus.set(500);
        CompositePlanner planner = planner();
        for (int i = 0; i < 3; i++) {
            planner.plan("这摊子事儿你帮我瞅瞅", ChatSlots.EMPTY);
        }
        assertThat(calls.get()).isEqualTo(3);
        ChatPlan after = planner.plan("这摊子事儿你帮我瞅瞅", ChatSlots.EMPTY).orElseThrow();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(after.source()).isEqualTo(ChatPlan.Source.KEYWORD);
        assertThat(planner.state()).contains("熔断");
    }

    @Test
    @DisplayName("模型说 HELP 却没给候选时，词表确定命中仍然算数（只防模型抽风，不让词表抢答）")
    void 模型抽风不吞确定命中() {
        nextBody.set(ok("{\"action\":\"HELP\",\"confidence\":0.1,\"reason\":\"猜不出\",\"options\":[]}"));
        ChatPlan plan = planner().plan("跑一下 BILL_VS_SETTLE", ChatSlots.EMPTY).orElseThrow();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.KEYWORD);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.RUN);
    }

    @Test
    @DisplayName("模型说不懂并给候选时，别拿词表的名词猜测去执行")
    void 模型提出澄清时优先于名词猜测() {
        nextBody.set(ok("{\"action\":\"HELP\",\"confidence\":0.3,\"reason\":\"说不清要调什么\",\"options\":[\"LIST_RULES\",\"DEFINE_RULE\"]}"));
        ChatPlan plan = planner().plan("规则这块我还想再调调", ChatSlots.EMPTY).orElseThrow();
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.LLM);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.HELP);
        assertThat(plan.hasOptions()).isTrue();
    }

    @Test
    @DisplayName("词表命中写动作但缺参数时，让模型用事实补一次")
    void 写动作缺参数交给模型补() {
        nextBody.set(ok("{\"action\":\"RUN\",\"args\":{\"ruleCode\":\"BILL_VS_SETTLE\"},"
                + "\"confidence\":0.8,\"reason\":\"事实里只有这条账单结算规则\"}"));
        io.github.dolow.reconlens.chat.ContextFacts facts = new io.github.dolow.reconlens.chat.ContextFacts(
                "facts", java.util.Set.of("BILL_VS_SETTLE"), java.util.Set.of(12L),
                java.util.Set.of("bill_left", "bill_right"));

        ChatPlan plan = planner().plan("跑一下那个账单和结算的对账", ChatSlots.EMPTY, facts, List.of()).orElseThrow();

        assertThat(plan.source()).isEqualTo(ChatPlan.Source.LLM);
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.RUN);
        assertThat(plan.call().ruleCode()).isEqualTo("BILL_VS_SETTLE");
        assertThat(plan.evidence()).isEqualTo(ChatPlan.Evidence.CONSISTENT);
    }

    @Test
    @DisplayName("模型说了个库里不存在的规则：判冲突并要求澄清，绝不执行")
    void 事实冲突要澄清() {
        nextBody.set(ok("{\"action\":\"RUN\",\"args\":{\"ruleCode\":\"NO_SUCH_RULE\"},"
                + "\"confidence\":0.95,\"reason\":\"用户点名了这条\"}"));
        io.github.dolow.reconlens.chat.ContextFacts facts = new io.github.dolow.reconlens.chat.ContextFacts(
                "facts", java.util.Set.of("BILL_VS_SETTLE"), java.util.Set.of(), java.util.Set.of());

        ChatPlan plan = planner().plan("瞅瞅 NO_SUCH_RULE 那摊子", ChatSlots.EMPTY, facts, List.of()).orElseThrow();

        assertThat(plan.call().intent()).isEqualTo(ChatIntent.HELP);
        assertThat(plan.hasOptions()).isTrue();
        assertThat(plan.options()).contains("RUN");
        assertThat(plan.reason()).contains("没找到");
    }

    @Test
    @DisplayName("没配 key 时状态说清楚只用词表")
    void 未配置() {
        CompositePlanner planner = new CompositePlanner(new KeywordPlanner(new ChatIntentRouter()),
                new LlmPlanner(new LlmChatClient("", "", "deepseek-chat", 1000, json), json));
        assertThat(planner.describe()).contains("未配置");
        planner.plan("跑一下 BILL_VS_SETTLE", ChatSlots.EMPTY);
        assertThat(calls.get()).isZero();
    }
}
