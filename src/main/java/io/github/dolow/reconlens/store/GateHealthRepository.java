package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 闸门的健康状态（{@code recon_agent_gate}）。
 *
 * <p>为什么这张表必须存在：canary 失败不是"这条规则有问题"，而是"<b>这一版闸门不可信</b>"。
 * 如果只把它记在某个内存标志里，重启后 {@code trusted} 就丢了 —— 降级签字通道会在无人察觉的情况下重新打开。
 * 而一旦它被打开，{@code INCONCLUSIVE × NONE} 就能被"接受降级验证"批准，闸门 2 直接变成摆设。
 *
 * <p><b>默认不可信（fail-closed）</b>：查不到记录 = 这一版闸门从没自检过 = 不可信，下次试算必须先跑 canary。
 * 反过来写（查不到就当作可信）会让"新部署刚上线"成为一段无人看守的窗口。
 */
@Repository
public class GateHealthRepository {

    private final JdbcTemplate jdbc;

    public GateHealthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一次 canary 的结论。
     *
     * @param detail 失败时的差异（期望 2 条、实际几条、注入了什么），成功时为 {@code null}
     */
    public record GateHealth(String gateVersion, boolean trusted, Long canaryJobId, Instant checkedAt, String detail) {
    }

    public Optional<GateHealth> find(String gateVersion) {
        List<GateHealth> rows = jdbc.query(
                "SELECT gate_version, trusted, canary_job_id, checked_at, detail"
                        + " FROM recon_agent_gate WHERE gate_version = ?",
                (rs, rowNum) -> new GateHealth(
                        rs.getString("gate_version"),
                        rs.getBoolean("trusted"),
                        (Long) rs.getObject("canary_job_id"),
                        toInstant(rs.getTimestamp("checked_at")),
                        rs.getString("detail")),
                gateVersion);
        return rows.stream().findFirst();
    }

    /** 没有记录也算不可信 —— 见类注释里的 fail-closed 约定。 */
    public boolean isTrusted(String gateVersion) {
        return find(gateVersion).map(GateHealth::trusted).orElse(false);
    }

    public void markTrusted(String gateVersion, long canaryJobId) {
        upsert(gateVersion, true, canaryJobId, null);
    }

    public void markUntrusted(String gateVersion, Long canaryJobId, String detail) {
        upsert(gateVersion, false, canaryJobId, detail);
    }

    /**
     * 忘掉这一版闸门的自检结论，下次试算重新跑 canary。
     * <b>只允许由人显式触发</b>（修好闸门之后的 {@code retryGate}）—— 自动清除等于让
     * "闸门不可信"退化成一次不起眼的抖动，而它本该是一次需要人介入的事件。
     */
    public int clear(String gateVersion) {
        return jdbc.update("DELETE FROM recon_agent_gate WHERE gate_version = ?", gateVersion);
    }

    /**
     * 先 update 再 insert，避免 {@code MERGE}（H2）与 {@code ON DUPLICATE KEY}（MySQL）的方言分裂。
     * {@code checked_at} 每次都刷新：它的含义是"这一版闸门上次被验证的时间"。
     */
    private void upsert(String gateVersion, boolean trusted, Long canaryJobId, String detail) {
        int updated = jdbc.update("UPDATE recon_agent_gate SET trusted = ?, canary_job_id = ?,"
                        + " checked_at = CURRENT_TIMESTAMP, detail = ? WHERE gate_version = ?",
                trusted, canaryJobId, detail, gateVersion);
        if (updated == 0) {
            jdbc.update("INSERT INTO recon_agent_gate (gate_version, trusted, canary_job_id, checked_at, detail)"
                            + " VALUES (?,?,?,CURRENT_TIMESTAMP,?)",
                    gateVersion, trusted, canaryJobId, detail);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
