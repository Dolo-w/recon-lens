package io.github.dolow.reconlens.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型归因：走 OpenAI 兼容的 /chat/completions。
 * <p>设计上它只是"更好一点的解释器"，不是必需品 —— 没配 key、网络不通、模型胡说，
 * 都会安静地落回 {@link RuleBasedExplainer}，对账结果本身不受影响。
 */
@Component
public class LlmDiffExplainer implements DiffExplainer {

    private static final Logger log = LoggerFactory.getLogger(LlmDiffExplainer.class);

    private static final String SYSTEM_PROMPT = """
            你是资深清结算/对账工程师。用户会给你一批已检测出的对账差异，请为每条给出一句中文说明，
            指出最可能的业务原因和下一步排查动作。不要复述数字，不要客套话，不要 Markdown。
            """;

    /** 输出约定：`差异ID|说明`，一行一条 —— 解析简单，模型也不容易写崩。 */
    private static final Pattern LINE = Pattern.compile("^#?(\\d+)\\s*[|｜]\\s*(.+)$");

    private final ReconProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleBasedExplainer fallback;

    private volatile RestClient client;

    public LlmDiffExplainer(ReconProperties properties, RuleBasedExplainer fallback) {
        this.properties = properties;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return "llm-openai-compatible";
    }

    /** 当前配置是否真的能用（enabled + key + baseUrl）。 */
    public boolean available() {
        return properties.getLlm().usable();
    }

    @Override
    public String explain(RuleSpec rule, DiffRecord diff) {
        Map<Long, String> explained = explainBatch(rule, List.of(diff));
        return explained.getOrDefault(diff.id(), fallback.explain(rule, diff));
    }

    /**
     * 批量归因：一次请求解释多条，省 token 也省往返。
     * 返回可能少于入参 —— 没解析出来的 id 由调用方落回规则模板。
     */
    public Map<Long, String> explainBatch(RuleSpec rule, List<DiffRecord> diffs) {
        if (!available() || diffs.isEmpty()) {
            return Map.of();
        }
        List<DiffRecord> batch = diffs.size() > properties.getLlm().getBatchLimit()
                ? new ArrayList<>(diffs.subList(0, properties.getLlm().getBatchLimit()))
                : new ArrayList<>(diffs);
        try {
            String content = call(buildUserPrompt(rule, batch));
            return parse(content);
        } catch (Exception e) {
            log.warn("大模型归因失败，本次落回规则模板: {}", e.toString());
            return Map.of();
        }
    }

    private String call(String userPrompt) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getLlm().getModel());
        payload.put("temperature", 0.2);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));

        String response = client().post()
                .uri("/chat/completions")
                .body(mapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);

        JsonNode root = mapper.readTree(response == null ? "{}" : response);
        return root.path("choices").path(0).path("message").path("content").asText("");
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

    private String buildUserPrompt(RuleSpec rule, List<DiffRecord> diffs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("规则: ").append(rule.code()).append("（").append(rule.name()).append("）\n");
        sb.append("左侧: ").append(rule.left().describe()).append("，右侧: ").append(rule.right().describe()).append('\n');
        sb.append("差异列表:\n");
        for (DiffRecord diff : diffs) {
            sb.append('#').append(diff.id())
                    .append(" | 类型=").append(diff.diffType())
                    .append(" | 业务键=").append(diff.bizKey());
            if (diff.field() != null) {
                sb.append(" | 字段=").append(diff.field());
            }
            sb.append(" | 左=").append(nullToDash(diff.leftValue()))
                    .append(" | 右=").append(nullToDash(diff.rightValue()));
            if (diff.deltaValue() != null) {
                sb.append(" | 相差=").append(diff.deltaValue().stripTrailingZeros().toPlainString());
            }
            sb.append('\n');
        }
        sb.append("请按 `差异ID|一句话说明` 的格式逐行输出，共 ").append(diffs.size()).append(" 行。");
        return sb.toString();
    }

    private Map<Long, String> parse(String content) {
        Map<Long, String> explained = new LinkedHashMap<>();
        for (String raw : content.split("\\R")) {
            Matcher matcher = LINE.matcher(raw.trim());
            if (matcher.matches()) {
                try {
                    explained.put(Long.parseLong(matcher.group(1)), matcher.group(2).trim());
                } catch (NumberFormatException ignored) {
                    // 模型多写了几位数字，忽略这一行
                }
            }
        }
        return explained;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
