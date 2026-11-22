package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.Alert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 告警的哑存储（清单 P0-6）。判定逻辑全在 {@code AlertService}，这里只做 CRUD。
 */
@Repository
public class AlertRepository {

    private static final RowMapper<Alert> MAPPER = (rs, i) -> new Alert(
            rs.getLong("id"),
            rs.getString("alert_key"),
            rs.getString("severity"),
            rs.getString("message"),
            rs.getDouble("metric_value"),
            rs.getDouble("threshold_value"),
            rs.getString("status"),
            rs.getBoolean("notified"),
            rs.getString("created_at"),
            rs.getString("resolved_at"));

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 同一 key 同时只允许一条 OPEN —— 这是"告警不刷屏"的关键约束。
     *
     * <p>用查询而不是唯一索引：需要允许历史多条 RESOLVED。
     */
    public Optional<Alert> findOpen(String alertKey) {
        List<Alert> rows = jdbc.query(
                "SELECT * FROM recon_alert WHERE alert_key = ? AND status = 'OPEN' ORDER BY id DESC",
                MAPPER, alertKey);
        return rows.stream().findFirst();
    }

    public Alert insert(Alert a) {
        jdbc.update("INSERT INTO recon_alert (alert_key, severity, message, metric_value, "
                        + "threshold_value, status, notified) VALUES (?,?,?,?,?,?,?)",
                a.alertKey(), a.severity(), a.message(), a.metricValue(),
                a.thresholdValue(), a.status(), a.notified());
        // H2/MySQL 都支持 identity 查询，但这里直接用 findOpen 更省事且不依赖方言
        return findOpen(a.alertKey()).orElse(a);
    }

    /** 条件恢复：把 OPEN 的置 RESOLVED，返回影响行数（0 = 本来就没有开着的）。 */
    public int resolve(String alertKey) {
        return jdbc.update(
                "UPDATE recon_alert SET status = 'RESOLVED', resolved_at = ? "
                        + "WHERE alert_key = ? AND status = 'OPEN'",
                new Timestamp(System.currentTimeMillis()), alertKey);
    }

    /** 标记已推送过（避免同一条告警反复发通知）。 */
    public void markNotified(long id) {
        jdbc.update("UPDATE recon_alert SET notified = TRUE WHERE id = ?", id);
    }

    public List<Alert> listRecent(int limit) {
        return jdbc.query("SELECT * FROM recon_alert ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    public List<Alert> listOpen() {
        return jdbc.query("SELECT * FROM recon_alert WHERE status = 'OPEN' ORDER BY id DESC", MAPPER);
    }

    public int countOpen() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_alert WHERE status = 'OPEN'", Integer.class);
        return n == null ? 0 : n;
    }
}
