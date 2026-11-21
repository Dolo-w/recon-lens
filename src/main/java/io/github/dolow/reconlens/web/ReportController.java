package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.export.FinanceReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 财务可读报告端点：GET /api/reports/finance?days=30&priceCode=&model=&tokenType=
 *
 * <p>返回一页内联样式的中文 HTML（inline disposition，浏览器直接打开/打印/转发）。
 * GET 免 key（与证据包导出同一口径）。带 priceCode 才出「金额影响」段，
 * 不带就如实标注缺席 —— 宁可缺席，不出一份误导性报告。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final FinanceReportService reportService;

    public ReportController(FinanceReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/finance")
    public ResponseEntity<byte[]> finance(@RequestParam(defaultValue = "30") int days,
                                          @RequestParam(required = false) String priceCode,
                                          @RequestParam(required = false) String model,
                                          @RequestParam(required = false) String tokenType) {
        String html = reportService.html(days, priceCode, model, tokenType);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"finance-report.html\"")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    /** 校验错误统一 400，message 直接可读（与 ExportController 同一口径）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
