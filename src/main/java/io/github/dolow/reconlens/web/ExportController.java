package io.github.dolow.reconlens.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 争议证据包导出端点。GET 免 key（与差异/任务查询同一口径），产出 CSV 附件。
 *
 * <p>用法：{@code GET /api/jobs/{id}/evidence-pack.csv?priceCode=openai&model=gpt-4o&tokenType=OUTPUT}
 * —— 带 priceCode 出计价列（金额影响 = 我方-对方，负=少收），不带就只出差异+凭证。
 */
@RestController
@RequestMapping("/api/jobs")
public class ExportController {

    private final EvidencePackService evidencePackService;

    public ExportController(EvidencePackService evidencePackService) {
        this.evidencePackService = evidencePackService;
    }

    @GetMapping("/{id}/evidence-pack.csv")
    public ResponseEntity<byte[]> evidencePack(@PathVariable long id,
                                               @RequestParam(required = false) String priceCode,
                                               @RequestParam(required = false) String model,
                                               @RequestParam(required = false) String tokenType,
                                               @RequestParam(required = false) String at) {
        Instant atInstant = null;
        if (at != null && !at.isBlank()) {
            try {
                atInstant = Instant.parse(at.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "at 必须是 ISO-8601 UTC 时间（如 2026-11-14T00:00:00Z），收到：" + at);
            }
        }
        byte[] body = evidencePackService.exportJob(id, priceCode, model, tokenType, atInstant);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"evidence-pack-job-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /** 校验错误统一 400，message 直接可读（与 PriceController 同一口径）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
