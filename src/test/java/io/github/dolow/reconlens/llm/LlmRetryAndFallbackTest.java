package io.github.dolow.reconlens.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.LlmCall;
import io.github.dolow.reconlens.store.LlmCallRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 清单 P0-4（重试 / 退避 / 备用 provider）+ P0-2（每次尝试都记账）的行为验证。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 起真 HTTP 服务，而不是 mock {@code RestClient}：
 * 要验证的正是「HTTP 状态码 → 可重试/不可重试」的判定，mock 掉 HTTP 层就把被测逻辑一起 mock 掉了。
 *
 * <p>退避延迟在测试里配 0，所以整个类不睡任何时间。
 */
class LlmRetryAndFallbackTest {

    private HttpServer primary;
    private HttpServer fallback;
    private final AtomicInteger primaryHits = new AtomicInteger();
    private final AtomicInteger fallbackHits = new AtomicInteger();
    private LlmCallRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(LlmCallRepository.class);
    }

    @AfterEach
    void tearDown() {
        if (primary != null) {
            primary.stop(0);
        }
        if (fallback != null) {
            fallback.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 重试
    // ------------------------------------------------------------------

    @Test
    @DisplayName("500 前两次、第三次成功 → 重试到成功，账本记 RETRY/RETRY/RETRIED")
    void retriesOnServerErrorThenSucceeds() throws Exception {
        primary = start(primaryHits, attempt -> attempt <= 2 ? 500 : 200);

        Optional<String> result = client(props(primaryUrl(), 3)).complete(LlmPurpose.EXPLAIN, "s", "u");

        assertThat(result).contains("ok");
        assertThat(primaryHits.get())
                .as("500 是暂时性故障，必须真的重试（共 3 次尝试）")
                .isEqualTo(3);

        List<LlmCall> calls = capturedCalls();
        assertThat(calls).extracting(LlmCall::outcome)
                .as("前两次失败要单记 RETRY，不能污染最终失败率")
                .containsExactly("RETRY", "RETRY", "RETRIED");
        assertThat(calls).extracting(LlmCall::attempt).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("429 限流也重试（等一会儿就好，不是配置错）")
    void retriesOnRateLimit() throws Exception {
        primary = start(primaryHits, attempt -> attempt == 1 ? 429 : 200);

        assertThat(client(props(primaryUrl(), 2)).complete("s", "u")).contains("ok");
        assertThat(primaryHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("401 不可重试：只调一次就放弃，不做无用重试")
    void doesNotRetryOnUnauthorized() throws Exception {
        primary = start(primaryHits, attempt -> 401);

        Optional<String> result = client(props(primaryUrl(), 3)).complete(LlmPurpose.EXPLAIN, "s", "u");

        assertThat(result).isEmpty();
        assertThat(primaryHits.get())
                .as("401 是密钥错，重试一万次也一样；立刻失败才能快速暴露配置问题")
                .isEqualTo(1);
        assertThat(capturedCalls()).extracting(LlmCall::outcome).containsExactly("FAILED");
        assertThat(capturedCalls()).extracting(LlmCall::errorKind).containsExactly("http-401");
    }

    @Test
    @DisplayName("一直 500 → 尝试次数用尽后返回空，且账本里有一次最终 FAILED")
    void givesUpAfterMaxAttempts() throws Exception {
        primary = start(primaryHits, attempt -> 500);

        assertThat(client(props(primaryUrl(), 2)).complete("s", "u")).isEmpty();
        assertThat(primaryHits.get()).isEqualTo(2);
        assertThat(capturedCalls()).extracting(LlmCall::outcome)
                .as("两次尝试各记 RETRY，穷尽后再补一条终态 FAILED")
                .containsExactly("RETRY", "RETRY", "FAILED");
    }

    // ------------------------------------------------------------------
    // 备用 provider
    // ------------------------------------------------------------------

    @Test
    @DisplayName("主 provider 全挂 → 备用 provider 接管，账本记 FALLBACK")
    void fallsBackToSecondaryProvider() throws Exception {
        primary = start(primaryHits, attempt -> 500);
        fallback = start(fallbackHits, attempt -> 200);

        ReconProperties properties = props(primaryUrl(), 2);
        properties.getLlm().getFallback().setBaseUrl(fallbackUrl());
        properties.getLlm().getFallback().setModel("backup-model");

        Optional<String> result = client(properties).complete(LlmPurpose.HARVEST, "s", "u");

        assertThat(result)
                .as("DeepSeek 挂了不该让整条采集链瘫掉 —— 这是清单里'全链路瘫'的直接解药")
                .contains("ok");
        assertThat(primaryHits.get()).isEqualTo(2);
        assertThat(fallbackHits.get()).isEqualTo(1);

        List<LlmCall> calls = capturedCalls();
        assertThat(calls).extracting(LlmCall::provider).contains("fallback");
        assertThat(calls).extracting(LlmCall::outcome).contains("FALLBACK");
        assertThat(calls.get(calls.size() - 1).model()).isEqualTo("backup-model");
    }

    @Test
    @DisplayName("没配 fallback 时不额外发请求（行为与旧版一致）")
    void noFallbackConfiguredMeansSingleProvider() throws Exception {
        primary = start(primaryHits, attempt -> 500);

        assertThat(client(props(primaryUrl(), 1)).complete("s", "u")).isEmpty();
        assertThat(primaryHits.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 未配置与成本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("没配 key 时不发请求，账本记 UNAVAILABLE（留在账上才能解释'为什么没花钱'）")
    void recordsUnavailableWithoutNetworkCall() throws Exception {
        primary = start(primaryHits, attempt -> 200);
        ReconProperties properties = new ReconProperties();
        properties.getLlm().setEnabled(false);

        assertThat(client(properties).complete("s", "u")).isEmpty();
        assertThat(primaryHits.get()).isZero();
        assertThat(capturedCalls()).extracting(LlmCall::outcome).containsExactly("UNAVAILABLE");
    }

    @Test
    @DisplayName("成本按单价快照算：prompt×输入价 + completion×输出价（整数 micros）")
    void computesCostFromTokenBreakdown() throws Exception {
        primary = start(primaryHits, attempt -> 200); // 固定返回 prompt=10 / completion=5

        ReconProperties properties = props(primaryUrl(), 1);
        properties.getLlm().setPriceInputPerMtok(2.0);
        properties.getLlm().setPriceOutputPerMtok(8.0);

        client(properties).complete(LlmPurpose.EXPLAIN, "s", "u");

        LlmCall call = capturedCalls().get(0);
        assertThat(call.promptTokens()).isEqualTo(10);
        assertThat(call.completionTokens()).isEqualTo(5);
        assertThat(call.totalTokens()).isEqualTo(15);
        // 10 × 2 + 5 × 8 = 60 micro 元 = 0.00006 元
        assertThat(call.costMicros()).isEqualTo(60);
        assertThat(call.priceInputPerMtok()).isEqualTo(2.0);
        assertThat(call.priceOutputPerMtok()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("记账失败不影响调用结果（账本是旁路）")
    void repositoryFailureDoesNotBreakCall() throws Exception {
        primary = start(primaryHits, attempt -> 200);
        LlmCallRepository broken = mock(LlmCallRepository.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(broken).insertQuietly(any());

        OpenAiCompatibleChatClient client =
                new OpenAiCompatibleChatClient(props(primaryUrl(), 1), broken, LlmMetrics.noop());
        assertThat(client.complete("s", "u"))
                .as("账本写不进去不能让一次已经成功的模型调用变成失败")
                .contains("ok");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private OpenAiCompatibleChatClient client(ReconProperties properties) {
        return new OpenAiCompatibleChatClient(properties, repository, LlmMetrics.noop());
    }

    private ReconProperties props(String baseUrl, int maxAttempts) {
        ReconProperties properties = new ReconProperties();
        ReconProperties.Llm llm = properties.getLlm();
        llm.setEnabled(true);
        llm.setBaseUrl(baseUrl);
        llm.setApiKey("test-key");
        llm.setModel("test-model");
        llm.setMaxAttempts(maxAttempts);
        llm.setRetryBaseDelayMillis(0); // 测试不睡
        return properties;
    }

    @SuppressWarnings("unchecked")
    private List<LlmCall> capturedCalls() {
        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        // 客户端走的是 insertQuietly（吞异常的那层），不是 insert
        verify(repository, atLeastOnce()).insertQuietly(captor.capture());
        return captor.getAllValues();
    }

    private String primaryUrl() {
        return "http://127.0.0.1:" + primary.getAddress().getPort();
    }

    private String fallbackUrl() {
        return "http://127.0.0.1:" + fallback.getAddress().getPort();
    }

    /** 按尝试次数决定返回码：1 起算。 */
    private interface StatusPolicy {
        int statusOf(int attempt);
    }

    private static HttpServer start(AtomicInteger hits, StatusPolicy policy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int attempt = hits.incrementAndGet();
            int status = policy.statusOf(attempt);
            byte[] body = (status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
                    : "{\"error\":\"simulated-" + status + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
