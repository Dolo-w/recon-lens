package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.LlmCall;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 大模型调用账本的哑存储（清单 P0-2）。只做写入与聚合查询，不做判定。
 *
 * <p><b>写入必须是 best-effort</b>：账本写失败绝不能影响业务调用本身 ——
 * 对账结论比计费账本重要。调用方负责吞掉异常（{@link #insertQuietly}）。
 */
@Repository
public class LlmCallRepository {

    private static final RowMapper<LlmCall> MAPPER = (rs, i) -> new LlmCall(
            rs.getLong("id"),
            rs.getString("purpose"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getInt("prompt_tokens"),
            rs.getInt("completion_tokens"),
            rs.getInt("total_tokens"),
            rs.getLong("latency_ms"),
            rs.getInt("attempt"),
            rs.getString("outcome"),
            rs.getString("error_kind"),
            rs.getLong("cost_micros"),
            rs.getDouble("price_input_snapshot"),
            rs.getDouble("price_output_snapshot"),
            rs.getString("called_at"));

    private final JdbcTemplate jdbc;

    public LlmCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LlmCall insert(LlmCall c) {
        jdbc.update("INSERT INTO recon_llm_call (purpose, provider, model, prompt_tokens, "
                        + "completion_tokens, total_tokens, latency_ms, attempt, outcome, error_kind, "
                        + "cost_micros, price_input_snapshot, price_output_snapshot) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.purpose(), c.provider(), c.model(), c.promptTokens(), c.completionTokens(),
                c.totalTokens(), c.latencyMs(), c.attempt(), c.outcome(), c.errorKind(),
                c.costMicros(), c.priceInputPerMtok(), c.priceOutputPerMtok());
        return c;
    }

    /**
     * 记账失败只打日志不抛：账本是旁路账，不能因为它写不进去就让一次已经成功的模型调用变成失败。
     *
     * @return 是否记上了（false 仅用于测试断言，业务侧不看）
     */
    public boolean insertQuietly(LlmCall c) {
        try {
            insert(c);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 按日 × purpose 聚合（日报）。
     *
     * <p><b>为什么用 Java 聚合而不是 SQL GROUP BY</b>：截取日期前缀的写法在 H2
     * （{@code FORMATDATETIME}）和 MySQL（{@code DATE_FORMAT}）互不兼容，
     * 而 {@code SUBSTRING} 直接作用在 TIMESTAMP 上两家都会报类型错。
     * 这是报表查询（一天最多几千条），不是热路径 —— 换来的是"换库不用改 SQL"。
     *
     * @param days 回看天数（含今天）
     */
    public List<Map<String, Object>> dailyByPurpose(int days) {
        record Agg(long calls, long tokens, long costMicros, long latencySum) {
        }
        Map<String, Agg> buckets = new java.util.LinkedHashMap<>();
        Map<String, String[]> labels = new java.util.HashMap<>();

        for (LlmCall c : since(days)) {
            String day = c.calledAt() == null || c.calledAt().length() < 10
                    ? "unknown" : c.calledAt().substring(0, 10);
            String key = day + "|" + c.purpose() + "|" + c.provider() + "|" + c.model() + "|" + c.outcome();
            labels.putIfAbsent(key, new String[]{day, c.purpose(), c.provider(), c.model(), c.outcome()});
            Agg prev = buckets.getOrDefault(key, new Agg(0, 0, 0, 0));
            buckets.put(key, new Agg(prev.calls() + 1,
                    prev.tokens() + c.totalTokens(),
                    prev.costMicros() + c.costMicros(),
                    prev.latencySum() + c.latencyMs()));
        }

        return buckets.entrySet().stream()
                .map(e -> {
                    String[] l = labels.get(e.getKey());
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("day", l[0]);
                    row.put("purpose", l[1]);
                    row.put("provider", l[2]);
                    row.put("model", l[3]);
                    row.put("outcome", l[4]);
                    row.put("calls", e.getValue().calls());
                    row.put("tokens", e.getValue().tokens());
                    row.put("cost_micros", e.getValue().costMicros());
                    row.put("avg_latency_ms", e.getValue().calls() == 0 ? 0
                            : e.getValue().latencySum() / e.getValue().calls());
                    return row;
                })
                // 日期新的在前；同一天按成本降序
                .sorted(java.util.Comparator
                        .comparing((Map<String, Object> r) -> String.valueOf(r.get("day"))).reversed()
                        .thenComparing(r -> -((Number) r.get("cost_micros")).longValue()))
                .toList();
    }

    /** 窗口内的原始账本行（新的在前）。 */
    public List<LlmCall> since(int days) {
        java.sql.Timestamp from = java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().minusDays(Math.max(1, days)).atStartOfDay());
        return jdbc.query("SELECT * FROM recon_llm_call WHERE called_at >= ? ORDER BY id DESC",
                MAPPER, from);
    }

    /** 总量汇总（报表页头用）。 */
    public Map<String, Object> totals() {
        return jdbc.queryForMap(
                "SELECT COUNT(*) AS calls, "
                        + "COALESCE(SUM(total_tokens), 0) AS tokens, "
                        + "COALESCE(SUM(cost_micros), 0) AS cost_micros, "
                        + "COALESCE(SUM(CASE WHEN outcome IN ('FAILED') THEN 1 ELSE 0 END), 0) AS failures, "
                        + "COALESCE(SUM(CASE WHEN outcome = 'RETRIED' THEN 1 ELSE 0 END), 0) AS retried "
                        + "FROM recon_llm_call");
    }

    /** 最近 N 条（排障用）。 */
    public List<LlmCall> recent(int limit) {
        return jdbc.query("SELECT * FROM recon_llm_call ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    /** 今天的累计成本（micros 元）。日成本告警用。 */
    public long todayCostMicros() {
        java.sql.Timestamp from = java.sql.Timestamp.valueOf(
                java.time.LocalDate.now().atStartOfDay());
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(cost_micros), 0) FROM recon_llm_call WHERE called_at >= ?",
                Long.class, from);
        return value == null ? 0L : value;
    }

    /**
     * 窗口内的失败率（告警用）：返回 [总次数, 失败次数]。
     * {@code minutes} 是回看窗口，避免用全表累计值判断"现在挂没挂"。
     */
    public int[] failureStats(int minutes) {
        java.sql.Timestamp since = java.sql.Timestamp.valueOf(
                java.time.LocalDateTime.now().minusMinutes(Math.max(1, minutes)));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN outcome = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed "
                        + "FROM recon_llm_call WHERE called_at >= ?",
                since);
        int total = ((Number) row.getOrDefault("total", row.getOrDefault("TOTAL", 0))).intValue();
        int failed = ((Number) row.getOrDefault("failed", row.getOrDefault("FAILED", 0))).intValue();
        return new int[]{total, failed};
    }
}
