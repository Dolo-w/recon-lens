package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.PriceEntry;
import io.github.dolow.reconlens.pricing.PriceCatalogService;
import io.github.dolow.reconlens.pricing.PriceNotResolvedException;
import io.github.dolow.reconlens.pricing.PriceQuote;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token 价格表出口（Phase 2.5）：
 * <ul>
 *   <li>GET  /api/prices?priceCode=&model=&tokenType= —— 列表（都可选过滤）</li>
 *   <li>POST /api/prices —— 录入（归一 + 重叠 fail-loud）</li>
 *   <li>DELETE /api/prices/{id}</li>
 *   <li>POST /api/prices/resolve —— 单笔试算：给定请求时间戳与 tokens，返回命中的
 *       单价与金额（月中调价回溯、cache 分价、阶梯价都在这一步裁决）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/prices")
public class PriceController {

    private final PriceCatalogService service;

    public PriceController(PriceCatalogService service) {
        this.service = service;
    }

    public record CreateRequest(String priceCode, String model, String tokenType,
                                BigDecimal unitPricePerMillion, String currency,
                                String effectiveFrom, String effectiveTo,
                                BigDecimal tierMin, BigDecimal tierMax, String sourceNote) {
    }

    public record ResolveRequest(String priceCode, String model, String tokenType,
                                 String at, BigDecimal tokens) {
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String priceCode,
                                    @RequestParam(required = false) String model,
                                    @RequestParam(required = false) String tokenType) {
        List<PriceEntry> items = service.list(priceCode, model, tokenType);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    @PostMapping
    public ResponseEntity<PriceEntry> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(new PriceEntry(null, request.priceCode(), request.model(),
                        request.tokenType(), request.unitPricePerMillion(), request.currency(),
                        parseTime(request.effectiveFrom(), "effectiveFrom"),
                        parseTime(request.effectiveTo(), "effectiveTo"),
                        request.tierMin(), request.tierMax(), request.sourceNote(), null)));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        service.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody ResolveRequest request) {
        PriceQuote quote = service.resolve(request.priceCode(), request.model(), request.tokenType(),
                parseTime(request.at(), "at"), request.tokens());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", quote.entry().model());
        body.put("tokenType", quote.entry().tokenType());
        body.put("currency", quote.entry().currency());
        body.put("unitPricePerMillion", quote.entry().unitPricePerMillion());
        body.put("at", quote.at().toString());
        body.put("tokens", quote.tokens().toPlainString());
        body.put("amount", quote.amount().toPlainString());
        body.put("entryId", quote.entry().id());
        body.put("effectiveFrom", quote.entry().effectiveFrom().toString());
        body.put("effectiveTo", quote.entry().effectiveTo() == null
                ? null : quote.entry().effectiveTo().toString());
        return body;
    }

    /** 校验/解析错误统一 400，message 直接可读（解析失败异常已带可用窗列表）。 */
    @ExceptionHandler({IllegalArgumentException.class, PriceNotResolvedException.class})
    public Map<String, Object> badRequest(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }

    private static Instant parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 必须是 ISO-8601 UTC 时间（如 2026-11-15T00:00:00Z）");
        }
    }
}
