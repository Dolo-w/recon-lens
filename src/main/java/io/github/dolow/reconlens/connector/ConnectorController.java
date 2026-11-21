package io.github.dolow.reconlens.connector;

import io.github.dolow.reconlens.connector.lago.LagoApiClient;
import io.github.dolow.reconlens.web.ApiSecurity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商连接器出口（连接器层第三步，常驻不随 agent 开关）：
 * <ul>
 *   <li>{@code GET /api/connectors} —— 目录：有哪些资源、按哪份 profile 落盘、key 是否已配；</li>
 *   <li>{@code POST /api/connectors/lago/sync} —— 拉 Lago 账单 → 按 profile 表头映射 → 落盘 CSV
 *       （原始凭证）。<b>不建表不灌数</b>：导入走 {@code recon_import_file} 的 agent 侧管道，
 *       分离原因见 {@link LagoConnectorService} 类注释。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final LagoConnectorService service;
    private final LagoApiClient client;
    private final ApiSecurity apiSecurity;

    public ConnectorController(LagoConnectorService service, LagoApiClient client, ApiSecurity apiSecurity) {
        this.service = service;
        this.client = client;
        this.apiSecurity = apiSecurity;
    }

    public record SyncRequest(String resource) {
    }

    @GetMapping
    public Map<String, Object> catalog() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lago", Map.of(
                "configured", client.isConfigured(), // 只报是否，不回显 key
                "resources", List.of(
                        Map.of("id", LagoConnectorService.Resource.FEES.id(),
                                "profileId", LagoConnectorService.Resource.FEES.profileId(),
                                "description", LagoConnectorService.Resource.FEES.description()),
                        Map.of("id", LagoConnectorService.Resource.INVOICES.id(),
                                "profileId", LagoConnectorService.Resource.INVOICES.profileId(),
                                "description", LagoConnectorService.Resource.INVOICES.description())),
                "sync", "POST /api/connectors/lago/sync {resource}"));
        return body;
    }

    @PostMapping("/lago/sync")
    public ResponseEntity<LagoConnectorService.SyncResult> sync(
            @RequestBody SyncRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        // 拉全量账单落盘 = 数据面写操作（写导入目录），与 POST /api/jobs 同一道密钥闸门
        apiSecurity.requireWrite(apiKey, "同步 Lago 账单（POST /api/connectors/lago/sync）");
        return ResponseEntity.status(HttpStatus.OK).body(service.sync(request.resource()));
    }

    /** 校验/拉取错误统一 400，message 直接可读（client 的人话翻译原样透出）。 */
    @ExceptionHandler({IllegalArgumentException.class, LagoApiClient.LagoApiException.class,
            IllegalStateException.class})
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
