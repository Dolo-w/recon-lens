package io.github.dolow.reconlens.connector.orb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Orb REST API 客户端（v3 扩展架构 §三：第二连接器，usage-based 计费厂商）。
 *
 * <p>端点与分页按 Orb 官方文档核证（docs.withorb.com，2026-11）：
 * <ul>
 *   <li>列表端点 {@code GET /invoices} / {@code GET /subscriptions/summary}，
 *       Bearer 认证，分页参数 {@code limit}（1–100）+ {@code cursor}；</li>
 *   <li>响应形如 {@code {"data": [...], "pagination_metadata":
 *       {"has_more": true, "next_cursor": "..."}}}——翻页用 next_cursor 回填 cursor，
 *       has_more=false 或 next_cursor 空即停；</li>
 *   <li>与 Lago 不同：Orb 金额是<b>字符串十进制元</b>（如 {@code "8.00"}），不是分——
 *       落盘后按 DECIMAL 直接入库，不走 Lago 的 {@code _cents} 换算。</li>
 * </ul>
 *
 * <p><b>fail-loud</b>：非 200 一律抛 {@link OrbApiException}，401 说"key 无效"、
 * 429 说"限流"并带 Retry-After——绝不静默吞错，拉回来的数据要进账单表，半截数据比没有更危险。
 */
@Component
public class OrbApiClient {

    private static final Logger log = LoggerFactory.getLogger(OrbApiClient.class);

    private final ReconProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public OrbApiClient(ReconProperties properties) {
        this.properties = properties;
        int timeout = properties.getConnector().getOrb().getTimeoutSeconds();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    /** 单页拉取：返回该资源的 JSON 数组（不带分页元数据）。 */
    public List<JsonNode> fetchAll(String resource) {
        return fetchPath(pathOf(resource));
    }

    /**
     * 按给定 API 路径拉全（cursor 翻页）。资源名校验在 {@link #fetchAll}，
     * 这里留给 401/网络等形状测试直打任意端点。
     */
    public List<JsonNode> fetchPath(String path) {
        ReconProperties.Connector.Orb cfg = properties.getConnector().getOrb();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new OrbApiException("Orb API key 未配置：设 recon.connector.orb.api-key（或环境变量）后重试；"
                    + "当前连接器不可用，绝不静默返回空数据");
        }
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= cfg.getMaxPages(); page++) {
            StringBuilder url = new StringBuilder(cfg.getBaseUrl())
                    .append(path).append("?limit=").append(cfg.getPageSize());
            if (cursor != null && !cursor.isBlank()) {
                url.append("&cursor=").append(cursor);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new OrbApiException("Orb API 网络错误（" + path + "）：" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrbApiException("Orb API 拉取被中断", e);
            }
            if (response.statusCode() == 401) {
                throw new OrbApiException("Orb API key 无效（401）：核对 recon.connector.orb.api-key 后重试");
            }
            if (response.statusCode() == 429) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("未知");
                throw new OrbApiException("Orb API 限流（429）：Retry-After=" + retryAfter
                        + "，稍后重试或调小 recon.connector.orb.page-size");
            }
            if (response.statusCode() != 200) {
                throw new OrbApiException("Orb API 返回 " + response.statusCode() + "（" + path + "）"
                        + "：" + snippet(response.body()));
            }
            JsonNode body;
            try {
                body = mapper.readTree(response.body());
            } catch (IOException e) {
                throw new OrbApiException("Orb API 响应不是合法 JSON（" + path + "）：" + e.getMessage(), e);
            }
            JsonNode data = body.get("data");
            if (data == null || !data.isArray()) {
                throw new OrbApiException("Orb API 响应缺 data 数组（" + path + "）——响应形状与文档不符，"
                        + "核对 base-url 是否指向 Orb 官方 API");
            }
            data.forEach(out::add);
            JsonNode meta = body.get("pagination_metadata");
            if (meta == null || !meta.path("has_more").asBoolean(false)) {
                break;
            }
            String next = meta.path("next_cursor").asText(null);
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
        }
        log.info("Orb API 拉取完成: path={} rows={}", path, out.size());
        return out;
    }

    /** 资源 id → API 路径（未知资源在 service 层 resolve，这里兜底防拼 URL）。 */
    static String pathOf(String resource) {
        if ("invoices".equals(resource)) {
            return "/invoices";
        }
        if ("subscriptions".equals(resource)) {
            return "/subscriptions/summary";
        }
        throw new OrbApiException("未知资源: " + resource + "，可用: invoices, subscriptions");
    }

    /**
     * 按 {@code a.b.c} 点路径取 JSON 值，缺节点/空串/null 一律归 ""——
     * 与 {@code LagoApiClient.text} 同语义，映射层不用到处判空。
     */
    public static String text(JsonNode node, String dotPath) {
        JsonNode cur = node;
        for (String part : dotPath.split("\\.")) {
            if (cur == null) {
                return "";
            }
            cur = cur.get(part);
        }
        if (cur == null || cur.isNull()) {
            return "";
        }
        String s = cur.asText();
        return s == null ? "" : s;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 拉取失败统一出口，Controller 的人话翻译原样透出。 */
    public static class OrbApiException extends RuntimeException {

        public OrbApiException(String message) {
            super(message);
        }

        public OrbApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
