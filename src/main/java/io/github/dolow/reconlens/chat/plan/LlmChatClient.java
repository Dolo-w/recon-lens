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

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final ObjectMapper json;
    private final HttpClient http;

    public LlmChatClient(String baseUrl, String apiKey, String model, int timeoutMs, ObjectMapper json) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model.trim();
        this.timeoutMs = timeoutMs <= 0 ? 8000 : timeoutMs;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 3000)))
                .build();
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
                "max_tokens", 400,
                "response_format", Map.of("type", "json_object"),
                "stream", false);
        return send(body);
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
        return send(body);
    }

    private Optional<String> send(Map<String, Object> body) {
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
                log.warn("模型返回 {}：{}", response.statusCode(), clip(response.body(), 200));
                return Optional.empty();
            }
            JsonNode root = json.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                log.warn("模型响应里没有 content：{}", clip(response.body(), 200));
                return Optional.empty();
            }
            return Optional.of(content.asText(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            // 超时/连不上/JSON 坏都在这里：模型不可用不该变成一个 500
            log.warn("模型调用失败：{}", e.toString());
            return Optional.empty();
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
