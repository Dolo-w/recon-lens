package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.alert.AlertService;
import io.github.dolow.reconlens.domain.Alert;
import io.github.dolow.reconlens.store.AlertRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警查询与手动检查（清单 P0-6）。
 *
 * <p>GET 免 key（监控/值班看板要能直接拉），POST 是写动作走 {@link ApiSecurity#requireWrite}。
 * 手动检查端点的用途是交付时现场演示"规则真的会触发"，不必等 5 分钟一次的定时器。
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository repository;
    private final AlertService service;
    private final ApiSecurity security;

    public AlertController(AlertRepository repository, AlertService service, ApiSecurity security) {
        this.repository = repository;
        this.service = service;
        this.security = security;
    }

    /** 当前状态：开着的告警 + 最近历史。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("openCount", repository.countOpen());
        body.put("open", repository.listOpen());
        body.put("recent", repository.listRecent(Math.min(Math.max(1, limit), 500)));
        return body;
    }

    /** 只给监控用的轻量版本（只返回开着的条数与最高级别），避免看板拉全量。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Alert> open = repository.listOpen();
        String highest = open.stream().anyMatch(a -> Alert.SEVERITY_CRITICAL.equals(a.severity()))
                ? Alert.SEVERITY_CRITICAL
                : (open.isEmpty() ? "OK" : Alert.SEVERITY_WARNING);
        return Map.of("openCount", open.size(), "highestSeverity", highest);
    }

    /**
     * 手动跑一轮规则，返回<b>本轮新产生</b>的告警。
     *
     * <p>已存在的不会重复返回——这与定时评估的去重语义一致，否则演示时会看到"同一条报 N 次"。
     */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        security.requireWrite(apiKey, "手动触发告警检查");
        List<Alert> raised = service.evaluate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("raised", raised);
        body.put("openCount", repository.countOpen());
        return body;
    }
}
