package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.DiffLifecycle.Action;
import io.github.dolow.reconlens.domain.DiffLifecycle.DiffAction;
import io.github.dolow.reconlens.domain.DiffLifecycle.Status;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 差异生命周期台账（豁免台账）的 SQL 层。append-only：只有 INSERT，没有 UPDATE/DELETE ——
 * 处置历史就是审计线索，改历史等于销毁证据。当前状态 = {@code MAX(id)} 那条动作的 status。
 */
@Repository
public class DiffLifecycleRepository {

    private static final String ACTION_COLUMNS =
            "id, diff_id, job_id, action, status, operator, note, created_at";

    private static final RowMapper<DiffAction> ACTION_MAPPER = (rs, i) -> new DiffAction(
            rs.getLong("id"),
            rs.getLong("diff_id"),
            rs.getLong("job_id"),
            Action.valueOf(rs.getString("action")),
            Status.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getString("note"),
            timestamp(rs.getTimestamp("created_at")));

    /** NULL 时间戳不会出现（DDL 有 DEFAULT），防御式兜底而已。 */
    private static java.time.Instant timestamp(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public DiffLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DiffAction insert(long diffId, long jobId, Action action, Status status,
                             String operator, String note) {
        jdbc.update("INSERT INTO recon_diff_lifecycle (diff_id, job_id, action, status, operator, note) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                diffId, jobId, action.name(), status.name(), operator, note);
        // 台账要回显 id 与时间戳：取该差异最新一条（就是刚插的这条 —— 单写者，无并发竞态窗口）
        return history(diffId).get(0);
    }

    /** 当前状态：最新一条动作的 status；没有台账记录 = OPEN。 */
    public Optional<Status> currentStatus(long diffId) {
        return jdbc.queryForList("SELECT status FROM recon_diff_lifecycle WHERE diff_id = ? "
                        + "ORDER BY id DESC LIMIT 1", String.class, diffId)
                .stream().findFirst().map(Status::valueOf);
    }

    /** 一批差异的当前状态（diff 列表页用，避免逐条 N 次查询）。不在台账里的差异不出现。 */
    public Map<Long, Status> currentStatuses(Collection<Long> diffIds) {
        if (diffIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(diffIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.diff_id, l.status FROM recon_diff_lifecycle l "
                        + "JOIN (SELECT diff_id, MAX(id) AS max_id FROM recon_diff_lifecycle "
                        + "WHERE diff_id IN (" + placeholders + ") GROUP BY diff_id) m "
                        + "ON l.id = m.max_id",
                diffIds.toArray());
        Map<Long, Status> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            out.put(((Number) row.get("diff_id")).longValue(), Status.valueOf((String) row.get("status")));
        }
        return out;
    }

    /** 处置历史，最新在前。 */
    public List<DiffAction> history(long diffId) {
        return jdbc.query("SELECT " + ACTION_COLUMNS + " FROM recon_diff_lifecycle "
                + "WHERE diff_id = ? ORDER BY id DESC", ACTION_MAPPER, diffId);
    }

    /**
     * 闭环汇总：按当前状态计数。口径与 {@link ReconRepository#countDiffs} 一致 ——
     * jobId 为空 = 全库且只算 PROD 任务，指定 jobId = 该任务全部差异。
     *
     * @return 状态 → 条数（无记录的差异计入 OPEN）
     */
    public Map<Status, Long> countByStatus(Long jobId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(ls.status, 'OPEN') AS st, COUNT(*) AS cnt FROM recon_diff d ");
        List<Object> args = new java.util.ArrayList<>();
        if (jobId == null) {
            sql.append("JOIN recon_job j ON j.id = d.job_id AND j.kind = 'PROD' ");
        }
        // LEFT JOIN 必须在 WHERE 之前 —— 先过滤再连台账，还是先连台账再过滤，语义和语法都只对前者成立
        sql.append("LEFT JOIN recon_diff_lifecycle ls ON ls.id = ")
                .append("(SELECT MAX(l2.id) FROM recon_diff_lifecycle l2 WHERE l2.diff_id = d.id) ");
        if (jobId != null) {
            sql.append("WHERE d.job_id = ? ");
            args.add(jobId);
        }
        sql.append("GROUP BY COALESCE(ls.status, 'OPEN')");
        Map<Status, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), args.toArray())) {
            out.put(Status.valueOf((String) row.get("st")), ((Number) row.get("cnt")).longValue());
        }
        // 四个状态都给键，报表消费方不用判空
        for (Status s : Status.values()) {
            out.putIfAbsent(s, 0L);
        }
        return out;
    }
}
