package io.github.dolow.reconlens.connector;

import io.github.dolow.reconlens.connector.lago.LagoApiClient;
import io.github.dolow.reconlens.web.ApiSecurity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 厂商连接器出口（v3 扩展架构 §三，多厂商）：
 * <ul>
 *   <li>{@code GET /api/connectors} —— 目录：每个 {@link BillingConnector} 一段，
 *       报资源清单、按哪份 profile 落盘、key 是否已配（只报布尔不回显 key）；</li>
 *   <li>{@code POST /api/connectors/{name}/sync} —— 拉厂商账单 → 按 profile 表头映射 →
 *       落盘 CSV（原始凭证）。<b>不建表不灌数</b>：导入走 {@code recon_import_file} 的
 *       agent 侧管道，分离原因见 {@link LagoConnectorService} 类注释。
 *       原路径 {@code /lago/sync} 由 {@code {name}} 自然匹配，向后兼容。</li>
 * </ul>
 *
 * <p>实现注册即收：Spring 把所有 {@link BillingConnector} Bean 注进来，新增厂商零改动。
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final Map<String, BillingConnector> connectors;
    private final ApiSecurity apiSecurity;

    public ConnectorController(List<BillingConnector> impls, ApiSecurity apiSecurity) {
        this.connectors = impls.stream()
                .collect(Collectors.toMap(BillingConnector::name, c -> c,
                        (a, b) -> a, LinkedHashMap::new));
        this.apiSecurity = apiSecurity;
    }

    public record SyncRequest(String resource) {
    }

    @GetMapping
    public Map<String, Object> catalog() {
        Map<String, Object> body = new LinkedHashMap<>();
        for (BillingConnector connector : connectors.values()) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("configured", connector.configured()); // 只报是否，不回显 key
            section.put("resources", connector.resources().stream()
                    .map(r -> Map.of("id", r.id(), "profileId", r.profileId(),
                            "description", r.description()))
                    .collect(Collectors.toList()));
            section.put("sync", "POST /api/connectors/" + connector.name() + "/sync {resource}");
            body.put(connector.name(), section);
        }
        return body;
    }

    @PostMapping("/{name}/sync")
    public ResponseEntity<BillingConnector.SyncOutcome> sync(
            @PathVariable String name,
            @RequestBody SyncRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        BillingConnector connector = connectors.get(name);
        if (connector == null) {
            throw new IllegalArgumentException("未知连接器: " + name
                    + "，可用: " + String.join(", ", connectors.keySet()));
        }
        // 拉全量账单落盘 = 数据面写操作（写导入目录），与 POST /api/jobs 同一道密钥闸门
        apiSecurity.requireWrite(apiKey, "同步 " + name + " 账单（POST /api/connectors/" + name + "/sync）");
        return ResponseEntity.status(HttpStatus.OK).body(connector.sync(request.resource()));
    }

    /** 校验/拉取错误统一 400，message 直接可读（client 的人话翻译原样透出）。 */
    @ExceptionHandler({IllegalArgumentException.class, LagoApiClient.LagoApiException.class,
            IllegalStateException.class})
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
