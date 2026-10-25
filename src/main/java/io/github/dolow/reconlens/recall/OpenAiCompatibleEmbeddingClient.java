package io.github.dolow.reconlens.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI 兼容的 {@code POST /v1/embeddings} 客户端（默认按 bge-m3 的用法写）。
 *
 * <p>三条硬约束，都是"不装模型也能跑"换来的：
 * <ol>
 *   <li>没配 {@code recon.recall.embedding.url} 就 {@link #configured()} = false，一个请求都不发；</li>
 *   <li>任何失败（连不上、超时、非 200、JSON 不对、维度不对）都返回空，<b>不外抛</b>——
 *       向量只是检索的加速带，它坏了不该让"查口径"整条挂掉；</li>
 *   <li>慢。默认 3 秒超时：检索是同步路径，卡住比降级更难解释。</li>
 * </ol>
 *
 * <p>为什么维度要校验：库里存的是 {@code dim} + {@code BLOB}。换模型（1024 → 1536）之后
 * 老向量还在，余弦会算出一堆垃圾而不会报错——所以维度不一致的查询直接判不可用，
 * 让它降级，而不是给一个看似成功的错误答案。
 */
@Component
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final String url;
    private final String model;
    private final String apiKey;
    private final int configuredDim;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private volatile int observedDim = 0;

    public OpenAiCompatibleEmbeddingClient(
            @Value("${recon.recall.embedding.url:}") String url,
            @Value("${recon.recall.embedding.model:bge-m3}") String model,
            @Value("${recon.recall.embedding.api-key:}") String apiKey,
            @Value("${recon.recall.embedding.dim:1024}") int configuredDim,
            @Value("${recon.recall.embedding.timeout-ms:3000}") long timeoutMs) {
        this.url = url == null ? "" : url.trim();
        this.model = model == null ? "bge-m3" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.configuredDim = configuredDim;
        this.timeout = Duration.ofMillis(Math.max(300, timeoutMs));
    }

    @Override
    public boolean configured() {
        return !url.isEmpty();
    }

    @Override
    public int dim() {
        return observedDim > 0 ? observedDim : configuredDim;
    }

    @Override
    public String describe() {
        return configured()
                ? "向量检索（" + model + "，dim=" + dim() + "）"
                : "未配置向量模型（recon.recall.embedding.url 为空），检索降级为全文/关键词";
    }

    @Override
    public Optional<float[]> embed(String text) {
        if (!configured() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", text);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            if (!apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("向量服务返回 {}，本次检索降级：{}", response.statusCode(),
                        truncate(response.body()));
                return Optional.empty();
            }
            float[] vector = parse(response.body());
            if (vector.length == 0) {
                return Optional.empty();
            }
            if (vector.length != dim()) {
                LOG.warn("向量维度变了（期望 {}，实际 {}），本次不用向量检索：换模型后请重建索引",
                        dim(), vector.length);
                return Optional.empty();
            }
            observedDim = vector.length;
            return Optional.of(vector);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("向量服务不可用，本次检索降级：{}", e.getMessage());
            return Optional.empty();
        }
    }

    private float[] parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode arr = root.path("data").path(0).path("embedding");
            if (!arr.isArray()) {
                LOG.warn("向量响应里没有 data[0].embedding：{}", truncate(body));
                return new float[0];
            }
            float[] out = new float[arr.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) arr.get(i).asDouble();
            }
            return out;
        } catch (Exception e) {
            LOG.warn("向量响应解析失败：{}", e.getMessage());
            return new float[0];
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
