package io.github.dolow.reconlens.chat.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个只做一件事的 OpenAI 兼容对话客户端：给一段 system + 一句 user，要回一段 JSON。
 *
 * <p><b>为什么不用现成的 SDK</b>：这里只需要一个 POST，多引一个依赖就要多解释一通
 * 它的重试、日志、线程模型。而本项目对模型的态度是"可拔掉"——依赖越薄，拔掉越干净。
 *
 * <p><b>为什么所有失败都返回空而不是抛异常</b>：模型只是第二层，第一层词表永远在。
 * 一次 8 秒超时如果把整轮会话变成 500，那这个"可选的加速器"就在拖累主流程。
 * 失败一律 WARN + 空，由 {@link CompositePlanner} 决定要不要熔断。
 */
public final class LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(LlmChatClient.class);

    /**
     * 会话路径的重试次数<b>默认 1（不重试）</b>，与归因路径的策略刻意不同。
     *
     * <p>理由是交互语义：用户在等屏幕上的回复，重试一次就是最长再等一个 timeout（8 秒），
     * 而且会让上层熔断器看到的"失败一次"变成"底层请求两次"，把熔断阈值算歪。
     * 会话路径对模型挂掉的正解是<b>立刻退回词表</b>（已实现），而不是原地重试。
     *
     * <p>想开可以在部署档调 {@code recon.chat.llm.max-attempts}——能力在，默认关。
     */
    private static final long RETRY_DELAY_MS = 200L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final ObjectMapper json;
    private final HttpClient http;
    private final int maxAttempts;

    /**
     * 成本账本与指标（可空）。chat 是烧 token 最快的一条路径 ——
     * 不记账就等于"路由花了多少钱"永远是个谜。测试构造时传 null 即可退化。
     */
    private final io.github.dolow.reconlens.store.LlmCallRepository callRepository;
    private final io.github.dolow.reconlens.llm.LlmMetrics metrics;
    private final double priceInputPerMtok;
    private final double priceOutputPerMtok;

    public LlmChatClient(String baseUrl, String apiKey, String model, int timeoutMs, ObjectMapper json) {
        this(baseUrl, apiKey, model, timeoutMs, json, null, null, 0, 0, 1);
    }

    public LlmChatClient(String baseUrl, String apiKey, String model, int timeoutMs, ObjectMapper json,
                         io.github.dolow.reconlens.store.LlmCallRepository callRepository,
                         io.github.dolow.reconlens.llm.LlmMetrics metrics,
                         double priceInputPerMtok, double priceOutputPerMtok,
                         int maxAttempts) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model.trim();
        this.timeoutMs = timeoutMs <= 0 ? 8000 : timeoutMs;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 3000)))
                .build();
        this.callRepository = callRepository;
        this.metrics = metrics;
        this.priceInputPerMtok = Math.max(0, priceInputPerMtok);
        this.priceOutputPerMtok = Math.max(0, priceOutputPerMtok);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /** 给日志用的一句话；**永不回显 key**。 */
    public String describe() {
        if (!configured()) {
            return "未配置（recon.chat.llm.api-key 为空），只用词表";
        }
        return "OpenAI 兼容：" + baseUrl + " / " + model;
    }

    /**
     * 让模型给一份 JSON（规划结果）。
     *
     * <p>{@code max_tokens} 是 400 的时候实测被截断过一次：模型选工具时会把参数原样带回来
     * （{@code "businessDescription": "按订单、资源对账"}），400 装不下就成了半截 JSON，
     * 解析失败 → 三次之后熔断，接下来几轮全落回词表。宁可按 900 给，它不会真写满。
     */
    public Optional<String> completeJson(String system, String user) {
        if (!configured()) {
            return Optional.empty();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0,
                "max_tokens", 900,
                "response_format", Map.of("type", "json_object"),
                "stream", false);
        return send(body, io.github.dolow.reconlens.llm.LlmPurpose.CHAT_ROUTE);
    }

    /**
     * 纯文本补全：给"把结构化结果说成人话"用。
     *
     * <p>与 {@link #completeJson} 分开的原因：{@code response_format=json_object} 会逼模型
     * 按 JSON 输出，而"总结成两三句中文"本来就不是 JSON。同一个方法塞两种模式，
     * 迟早会出现"要 JSON 的时候拿到散文、要散文的时候拿到 JSON"。
     */
    public Optional<String> completeText(String system, String user, int maxTokens) {
        if (!configured()) {
            return Optional.empty();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)),
                "temperature", 0.2,
                "max_tokens", maxTokens <= 0 ? 240 : maxTokens,
                "stream", false);
        return send(body, io.github.dolow.reconlens.llm.LlmPurpose.CHAT_NARRATE);
    }

    /**
     * 发一次请求，带一次轻量重试（只在"等一会儿可能就好"的错误上）。
     *
     * <p><b>为什么要重试</b>：会话路由是用户感知最直接的路径。一次 502 就让整轮对话掉回词表，
     * 体验上是"它听不懂我说话"，而不是"网络抖了一下"。但重试次数必须少（2 次），
     * 因为用户在等 —— 归因链路可以等 3 次超时，这里不行。
     *
     * <p>记账也在这里做：这是 chat 路径唯一的出口，放在这里才不会漏。
     */
    private Optional<String> send(Map<String, Object> body, String purpose) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String kind = "http-" + response.statusCode();
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                    boolean willRetry = retryable && attempt < maxAttempts;
                    record(purpose, willRetry ? "RETRY" : "FAILED", started, 0, 0, kind);
                    log.warn("模型返回 {}：{}", response.statusCode(), clip(response.body(), 200));
                    if (willRetry) {
                        pauseBeforeRetry();
                        continue;
                    }
                    return Optional.empty();
                }

                JsonNode root = json.readTree(response.body());
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (content.isMissingNode() || content.isNull()) {
                    // 200 但没 content：重试大概率也一样，按最终失败处理
                    record(purpose, "FAILED", started, 0, 0, "empty-content");
                    log.warn("模型响应里没有 content：{}", clip(response.body(), 200));
                    return Optional.empty();
                }
                int[] usage = usageOf(root);
                record(purpose, attempt > 1 ? "RETRIED" : "OK", started, usage[0], usage[1], null);
                return Optional.of(content.asText(""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                record(purpose, "FAILED", started, 0, 0, "interrupted");
                return Optional.empty();
            } catch (Exception e) {
                // 超时/连不上/JSON 坏都在这里：模型不可用不该变成一个 500
                boolean willRetry = attempt < maxAttempts;
                record(purpose, willRetry ? "RETRY" : "FAILED", started, 0, 0, "network");
                log.warn("模型调用失败：{}", e.toString());
                if (willRetry) {
                    pauseBeforeRetry();
                }
            }
        }
        return Optional.empty();
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 从响应里取 {@code [prompt, completion, total]}；拿不到就 0（不猜）。 */
    static int[] usageOf(JsonNode root) {
        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        return new int[]{prompt, completion, prompt + completion};
    }

    /** 记账 + 打指标；两条都是旁路，失败不影响调用结果。 */
    private void record(String purpose, String outcome, long started, int prompt, int completion,
                        String errorKind) {
        long latency = System.currentTimeMillis() - started;
        if (callRepository != null) {
            long cost = io.github.dolow.reconlens.domain.LlmCall.costMicros(
                    prompt, completion, priceInputPerMtok, priceOutputPerMtok);
            callRepository.insertQuietly(new io.github.dolow.reconlens.domain.LlmCall(
                    null, purpose, "chat", model, prompt, completion, prompt + completion,
                    latency, 1, outcome, errorKind, cost, priceInputPerMtok, priceOutputPerMtok, null));
        }
        if (metrics != null) {
            metrics.record(purpose, model, "chat", outcome, latency, prompt, completion);
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
