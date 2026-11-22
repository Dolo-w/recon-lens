package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.store.LlmCallRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型成本归因端点（清单 P0-2）。
 *
 * <p>两个问题这张报表要能回答：
 * <ol>
 *   <li><b>这个月多少钱</b> —— {@code /api/costs/summary} 给总量与失败/重试次数；</li>
 *   <li><b>钱花在哪</b> —— {@code /api/costs/daily} 按 日 × 用途 × provider × 模型 × 结果 拆开。</li>
 * </ol>
 *
 * <p>GET 免 key（与财务报告、证据包导出同一口径：读侧开口，写侧才需要密钥）。
 */
@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final LlmCallRepository repository;

    public CostController(LlmCallRepository repository) {
        this.repository = repository;
    }

    /**
     * 按日聚合。{@code days} 默认 7，上限 365 防止有人拿它扫全表。
     *
     * <p>返回里的 {@code costYuan} 是**人看的**字段，{@code costMicros} 是**机器算的**原值。
     * 两者都给：前者方便直接贴进汇报，后者避免调用方自己去猜单位。
     */
    @GetMapping("/daily")
    public Map<String, Object> daily(@RequestParam(defaultValue = "7") int days) {
        int window = Math.min(Math.max(1, days), 365);
        List<Map<String, Object>> rows = repository.dailyByPurpose(window);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("windowDays", window);
        body.put("totals", summaryOf(repository.totals()));
        body.put("rows", rows.stream().map(CostController::withYuan).toList());
        return body;
    }

    /** 总量汇总：调用次数 / token / 成本 / 最终失败次数 / 重试成功次数。 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return summaryOf(repository.totals());
    }

    /** 最近 N 条明细，排障用（"刚才那次为什么失败"）。 */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(1, limit), 500);
        return repository.recent(n).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("purpose", c.purpose());
            m.put("provider", c.provider());
            m.put("model", c.model());
            m.put("tokens", c.totalTokens());
            m.put("latencyMs", c.latencyMs());
            m.put("attempt", c.attempt());
            m.put("outcome", c.outcome());
            m.put("errorKind", c.errorKind());
            m.put("costYuan", round6(c.costYuan()));
            m.put("calledAt", c.calledAt());
            return m;
        }).toList();
    }

    private static Map<String, Object> withYuan(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        Object micros = row.get("cost_micros");
        long value = micros instanceof Number n ? n.longValue() : parseLong(micros);
        out.put("costYuan", round6(value / 1_000_000.0));
        return out;
    }

    private static Map<String, Object> summaryOf(Map<String, Object> totals) {
        Map<String, Object> out = new LinkedHashMap<>();
        long micros = parseLong(totals.get("cost_micros"));
        out.put("calls", parseLong(totals.get("calls")));
        out.put("tokens", parseLong(totals.get("tokens")));
        out.put("failures", parseLong(totals.get("failures")));
        out.put("retried", parseLong(totals.get("retried")));
        out.put("costMicros", micros);
        out.put("costYuan", round6(micros / 1_000_000.0));
        return out;
    }

    /**
     * H2 返回的列名是大写、MySQL 是小写，{@code queryForMap} 的 key 因此不稳定；
     * 直接 {@code (Long)} 强转会在换库时炸。这里按大小写都找一遍。
     */
    static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
