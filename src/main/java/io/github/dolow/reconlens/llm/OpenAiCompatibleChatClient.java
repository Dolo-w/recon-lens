package io.github.dolow.reconlens.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI 兼容的 {@code /chat/completions} 实现（DeepSeek / 通义 / vLLM / Ollama 的 openai 兼容端点都能用）。
 *
 * <p>这段代码原样来自 {@code LlmDiffExplainer}：baseUrl 结尾斜杠归一、连接与读取超时、Bearer key、
 * 懒初始化 {@link RestClient}。抽取时<b>只搬了"发请求 + 取 content"</b>，
 * 把"失败要不要落回模板"留在了原处 —— 那是调用方的语义，不是客户端的能力。
 *
 * <p>温度固定 0.2：两个用途（写一句归因、起草一条规则）都需要稳定复现，
 * 而"更有创意"在这个系统里等于"更不可审计"。要调就改这一处，别散到调用方去。
 *
 * <p>它<b>不是 agent 包的 Bean</b>：归因链路在引入 Agent 之前就要用它，所以无条件装配。
 * 这不会破坏"关掉 Agent 等于回到引入之前"——它没有后台线程、没有调度、不读写任何表。
 */
@Component
public class OpenAiCompatibleChatClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private static final double TEMPERATURE = 0.2;

    private final ReconProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 最近一次请求的真实 token 用量。用 ThreadLocal 而不是字段：client 是单例 Bean，
     * 会话线程可能并发；{@code complete → lastTokens} 必须落在同一个线程里才有意义。
     */
    private static final ThreadLocal<Integer> LAST_TOKENS = ThreadLocal.withInitial(() -> 0);

    private volatile RestClient client;

    public OpenAiCompatibleChatClient(ReconProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.getLlm().usable();
    }

    @Override
    public String name() {
        return "llm-openai-compatible";
    }

    @Override
    public Optional<String> complete(String system, String user) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", properties.getLlm().getModel());
            payload.put("temperature", TEMPERATURE);
            payload.put("messages", List.of(
                    Map.of("role", "system", "content", system == null ? "" : system),
                    Map.of("role", "user", "content", user == null ? "" : user)));

            String response = client().post()
                    .uri("/chat/completions")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(response == null ? "{}" : response);
            LAST_TOKENS.set(usageOf(root));
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return content == null || content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (Exception e) {
            // 网络不通 / 401 / 429 / 返回体不是 JSON … 全部按"这次没拿到"处理。
            // 不抛异常是接口契约：调用方有更重要的事（出对账结论），不该被一次 API 抖动打断。
            log.warn("调用大模型失败（本次按不可用处理，由调用方决定降级方式）: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public int lastTokens() {
        return LAST_TOKENS.get();
    }

    /**
     * 从响应里取真实用量：{@code usage.total_tokens} 优先；只有分项时相加；都没有返回 0。
     * 返回 0 不代表"没花钱"，只代表这次拿不到，调用方会退回字符数估算。
     */
    static int usageOf(JsonNode root) {
        JsonNode usage = root.path("usage");
        int total = usage.path("total_tokens").asInt(0);
        if (total > 0) {
            return total;
        }
        return usage.path("prompt_tokens").asInt(0) + usage.path("completion_tokens").asInt(0);
    }

    private RestClient client() {
        RestClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    ReconProperties.Llm llm = properties.getLlm();
                    int timeoutMillis = (int) (llm.getTimeoutSeconds() * 1000L);
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(timeoutMillis);
                    factory.setReadTimeout(timeoutMillis);

                    String baseUrl = llm.getBaseUrl().endsWith("/")
                            ? llm.getBaseUrl().substring(0, llm.getBaseUrl().length() - 1)
                            : llm.getBaseUrl();
                    local = RestClient.builder()
                            .baseUrl(baseUrl)
                            .requestFactory(factory)
                            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + llm.resolveApiKey())
                            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }
}
