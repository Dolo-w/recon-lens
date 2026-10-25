package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 起草补参的校验契约：模型可以提值，但不许提候选之外的值。
 *
 * <p>这组用例守的是"最坏也只多问一句"：模型猜错表名 / 猜错列名时必须整项丢弃，
 * 不能让它变成一条看起来正常、实际永远跑不出差异的规则。
 */
class DraftSlotFillerTest {

    private static final List<String> TABLES = List.of("bill_left", "bill_right");
    private static final List<String> COLUMNS = List.of("order_no", "amount", "status");

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> reply = new AtomicReference<>("{\"slots\":{}}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = ok(reply.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
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

    private static String ok(String content) {
        try {
            return "{\"choices\":[{\"message\":{\"content\":" + new ObjectMapper().writeValueAsString(content) + "}}]}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DraftSlotFiller filler() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new DraftSlotFiller(new LlmChatClient(baseUrl, "sk-test", "deepseek-chat", 2000, json), json);
    }

    @Test
    @DisplayName("模型补出的表名和列名都过白名单后才落槽")
    void 合法值才落槽() {
        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"left\":\"bill_left\",\"key\":\"order_no\"}}", DraftIntake.Hints.EMPTY,
                TABLES, COLUMNS, COLUMNS);

        assertThat(slots).isPresent();
        assertThat(slots.get()).containsEntry("left", "bill_left").containsEntry("key", "order_no");
    }

    @Test
    @DisplayName("模型编了一张候选里没有的表：整项丢弃")
    void 候选外的表不接() {
        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"left\":\"bill_x\"}}", DraftIntake.Hints.EMPTY, TABLES, COLUMNS, COLUMNS);
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("模型给的列只在单侧存在：不接，留给下一轮反问")
    void 单侧列不接() {
        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"key\":\"order_no\"}}", DraftIntake.Hints.EMPTY,
                TABLES, List.of("order_no"), List.of("id"));
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("模型给了两侧都没有的列：整项丢弃，不自作聪明")
    void 认不出的列不接() {
        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"compare\":\"whatever\"}}", DraftIntake.Hints.EMPTY, TABLES, COLUMNS, COLUMNS);
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("复合键用「和」连接，已经给过的槽位不许被改")
    void 复合键且不覆盖已有值() {
        DraftIntake.Hints hints = DraftIntake.parse("右 bill_right", TABLES, DraftIntake.Hints.EMPTY);

        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"left\":\"bill_left\",\"right\":\"bill_left\",\"key\":\"order_no和status\"}}",
                hints, TABLES, COLUMNS, COLUMNS);

        assertThat(slots).isPresent();
        assertThat(slots.get()).containsEntry("left", "bill_left").containsEntry("key", "order_no和status");
        // right 已经定了，模型说了也不采纳
        assertThat(slots.get()).doesNotContainKey("right");
    }

    @Test
    @DisplayName("走真实 HTTP 一遍：模型回答被解析成槽位")
    void 走网络() {
        reply.set("{\"slots\":{\"left\":\"bill_left\",\"key\":\"order_no\"},\"reason\":\"用户在回答左表\"}");

        Optional<Map<String, String>> slots = filler().fill(
                "左边用账单表", DraftIntake.Hints.EMPTY, TABLES, COLUMNS, COLUMNS);

        assertThat(slots).isPresent();
        assertThat(slots.get()).containsEntry("left", "bill_left");
    }

    @Test
    @DisplayName("模型拿业务键当比对字段：不接，按 key 比 key 永远没有差异")
    void 业务键不当比对字段() {
        Optional<Map<String, String>> slots = filler().parse(
                "{\"slots\":{\"key\":\"order_no\",\"compare\":\"order_no\"}}", DraftIntake.Hints.EMPTY,
                TABLES, COLUMNS, COLUMNS);

        assertThat(slots).isPresent();
        assertThat(slots.get()).containsEntry("key", "order_no");
        assertThat(slots.get()).doesNotContainKey("compare");
    }

    @Test
    @DisplayName("没配 key 时直接返回空，不影响确定性兜底")
    void 未配置() {
        DraftSlotFiller filler = new DraftSlotFiller(
                new LlmChatClient("", "", "deepseek-chat", 1000, json), json);
        assertThat(filler.configured()).isFalse();
        assertThat(filler.fill("order_no", DraftIntake.Hints.EMPTY, TABLES, COLUMNS, COLUMNS)).isEmpty();
    }
}
