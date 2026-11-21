package io.github.dolow.reconlens.connector.lago;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lago REST API 客户端（连接器层第三步：原生 API 拉账单）。
 *
 * <p>端点与字段全部按 Lago 源码核证（本地克隆 api/ 目录，2026-11）：
 * <ul>
 *   <li>列表端点 {@code GET /fees} / {@code GET /invoices}，分页参数 {@code page}/{@code per_page}，
 *       响应形如 {@code {"fees": [...], "meta": {"next_page": 2, ...}}}——meta 结构核证自
 *       {@code app/controllers/concerns/pagination.rb}；</li>
 *   <li>fee / invoice JSON 字段与 CSV 导出同构（CSV 导出内部用的就是同一套 V1 serializer），
 *       所以 API 拉回的行可以直接按 {@code ColumnProfiles} 的表头拼装，走同一条导入管道。</li>
 * </ul>
 *
 * <p><b>fail-loud</b>：非 200 一律抛 {@link LagoApiException}，401 说"key 无效"、
 * 429 说"限流"并带 Retry-After——绝不静默吞错，拉回来的数据要进账单表，半截数据比没有更危险。
 */
@Component
public class LagoApiClient {

    private static final Logger log = LoggerFactory.getLogger(LagoApiClient.class);

    private final ReconProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public LagoApiClient(ReconProperties properties) {
        this.properties = properties;
        int timeout = properties.getConnector().getLago().getTimeoutSeconds();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    /** 单页拉取：返回该资源的 JSON 数组（不带 meta）。 */
    public List<JsonNode> fetchAll(String resource) {
        ReconProperties.Connector.Lago cfg = properties.getConnector().getLago();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new LagoApiException("Lago API key 未配置：设 recon.connector.lago.api-key（或环境变量）后重试；"
                    + "当前连接器不可用，绝不静默返回空数据");
        }
        List<JsonNode> out = new ArrayList<>();
        int page = 1;
        while (true) {
            if (page > cfg.getMaxPages()) {
                throw new LagoApiException("已拉满 max-pages=" + cfg.getMaxPages() + " 页还没到底（已 "
                        + out.size() + " 行）——像是配置问题，先调大 recon.connector.lago.max-pages 或核对环境，"
                        + "绝不静默截断账单");
            }
            JsonNode body = get(resource + "?page=" + page + "&per_page=" + cfg.getPageSize());
            JsonNode items = body.get(resource);
            if (items == null || !items.isArray()) {
                throw new LagoApiException("响应里没有 " + resource + " 数组——确认 base-url 指向 "
                        + cfg.getBaseUrl() + " 形如 /api/v1 的 API 根地址");
            }
            items.forEach(out::add);
            JsonNode meta = body.get("meta");
            int next = meta == null || meta.get("next_page") == null || meta.get("next_page").isNull()
                    ? -1 : meta.get("next_page").asInt(-1);
            if (next <= 0) {
                break;
            }
            page = next;
        }
        log.info("Lago API 拉取完成: resource={} rows={}", resource, out.size());
        return out;
    }

    private JsonNode get(String path) {
        ReconProperties.Connector.Lago cfg = properties.getConnector().getLago();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getBaseUrl() + "/" + path))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LagoApiException("请求 Lago API 失败（网络/超时）：" + e.getMessage()
                    + " —— base-url=" + cfg.getBaseUrl());
        }
        if (response.statusCode() != 200) {
            throw new LagoApiException(humanMessage(response));
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new LagoApiException("Lago API 响应不是合法 JSON：" + e.getMessage());
        }
    }

    /** 非 200 的说人话翻译：401 是 key、403 是权限、429 是限流，其余带状态码与响应前 200 字符。 */
    private static String humanMessage(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        String snippet = body.length() > 200 ? body.substring(0, 200) + "…" : body;
        return switch (response.statusCode()) {
            case 401 -> "Lago API 认证失败（401）：API key 无效或已撤销——核对 recon.connector.lago.api-key。响应：" + snippet;
            case 403 -> "Lago API 拒绝访问（403）：key 有效但没这个资源的权限。响应：" + snippet;
            case 404 -> "Lago API 404：端点不存在，核对 recon.connector.lago.base-url 是否指向 /api/v1。响应：" + snippet;
            case 429 -> "Lago API 限流（429）" + (response.headers().firstValue("Retry-After").isPresent()
                    ? "，Retry-After=" + response.headers().firstValue("Retry-After").get() : "")
                    + "——稍后重试。响应：" + snippet;
            default -> "Lago API 返回 " + response.statusCode() + "。响应：" + snippet;
        };
    }

    /** 从嵌套 JSON 按点路径取文本值：{@code item.type}、{@code customer.name}；缺失/null 返回 ""。 */
    public static String text(JsonNode node, String dottedPath) {
        JsonNode cur = node;
        for (String part : dottedPath.split("\\.")) {
            if (cur == null || !cur.isObject()) {
                return "";
            }
            cur = cur.get(part);
        }
        if (cur == null || cur.isNull()) {
            return "";
        }
        if (cur.isBoolean()) {
            return cur.asBoolean() ? "true" : "false";
        }
        return cur.asText();
    }

    /** API key 是否已配置（连接器目录展示用；不回显 key 本身）。 */
    public boolean isConfigured() {
        String key = properties.getConnector().getLago().getApiKey();
        return key != null && !key.isBlank();
    }

    public Map<String, String> endpoints() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("fees", "GET /fees -> profile lago-invoice-fees");
        out.put("invoices", "GET /invoices -> profile lago-invoices");
        return out;
    }

    /** Lago API 错误：message 已翻译成人话，直接透给调用方。 */
    public static class LagoApiException extends RuntimeException {
        public LagoApiException(String message) {
            super(message);
        }
    }
}
