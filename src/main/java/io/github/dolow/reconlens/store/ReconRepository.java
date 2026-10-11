package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.engine.DiffWriter;
import io.github.dolow.reconlens.engine.MatchStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 任务 / 分片 / 差异的持久化。全部走 JdbcTemplate，SQL 保持 H2 与 MySQL 都可执行。 */
@Repository
public class ReconRepository implements DiffWriter {

    private static final String DIFF_COLUMNS =
            "id, job_id, shard_id, diff_type, severity, biz_key, field_name, left_value, right_value, delta_value, delta_ratio, explanation";

    private static final String JOB_COLUMNS =
            "id, rule_code, batch_id, status, shard_count, shard_done, left_rows, right_rows, matched_rows, diff_rows, "
                    + "remark, error_msg, created_at, started_at, finished_at";

    private static final String SHARD_COLUMNS =
            "job_id, shard_id, status, attempts, scanned_left, scanned_right, matched_rows, diff_rows, error_msg, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 任务 ----------------

    public long createJob(String ruleCode, Integer ruleVersion, String ruleSnapshot, String batchId, String remark, int shardCount) {
        jdbc.update("INSERT INTO recon_job (rule_code, rule_version, rule_snapshot, batch_id, status, shard_count, remark) VALUES (?,?,?,?,?,?,?)",
                ruleCode, ruleVersion, ruleSnapshot, batchId, JobStatus.PENDING.name(), shardCount, remark);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        return id == null ? -1L : id;
    }

    /** 任务生效的规则版本（内联规则或历史任务可能没有）。 */
    public Integer ruleVersion(long jobId) {
        List<Integer> found = jdbc.queryForList("SELECT rule_version FROM recon_job WHERE id = ?", Integer.class, jobId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 任务创建时的规则快照：重跑照着它执行，规则后来被改过也不会让历史任务换语义。
     */
    public Optional<String> ruleSnapshot(long jobId) {
        List<String> found = jdbc.queryForList("SELECT rule_snapshot FROM recon_job WHERE id = ?", String.class, jobId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public void createShards(long jobId, int shardCount) {
        for (int shardId = 0; shardId < shardCount; shardId++) {
            jdbc.update("INSERT INTO recon_shard (job_id, shard_id, status) VALUES (?,?,?)",
                    jobId, shardId, ShardStatus.PENDING.name());
        }
    }

    public void markJobRunning(long jobId) {
        jdbc.update("UPDATE recon_job SET status=?, started_at=COALESCE(started_at, CURRENT_TIMESTAMP), error_msg=NULL WHERE id=?",
                JobStatus.RUNNING.name(), jobId);
    }

    /** 重跑前重置状态：把 failed 分片重新排进队列。 */
    public void markJobForRetry(long jobId) {
        jdbc.update("UPDATE recon_job SET status=?, finished_at=NULL, error_msg=NULL WHERE id=?", JobStatus.RUNNING.name(), jobId);
    }

    /** 汇总分片统计并落最终状态。 */
    public void finishJob(long jobId, JobStatus status, String errorMsg) {
        jdbc.update("""
                UPDATE recon_job SET
                  status = ?,
                  shard_done = (SELECT COUNT(*) FROM recon_shard s WHERE s.job_id = recon_job.id AND s.status = 'SUCCESS'),
                  left_rows = (SELECT COALESCE(SUM(s.scanned_left),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  right_rows = (SELECT COALESCE(SUM(s.scanned_right),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  matched_rows = (SELECT COALESCE(SUM(s.matched_rows),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  diff_rows = (SELECT COALESCE(SUM(s.diff_rows),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  error_msg = ?,
                  finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status.name(), errorMsg, jobId);
    }

    public Optional<ReconJob> findJob(long jobId) {
        List<ReconJob> rows = jdbc.query("SELECT " + JOB_COLUMNS + " FROM recon_job WHERE id = ?", jobMapper(), jobId);
        return rows.stream().findFirst();
    }

    public List<ReconJob> listJobs(int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM recon_job ORDER BY id DESC LIMIT ?", jobMapper(), limit);
    }

    // ---------------- 分片 ----------------

    public List<ReconShard> listShards(long jobId) {
        return jdbc.query("SELECT " + SHARD_COLUMNS + " FROM recon_shard WHERE job_id = ? ORDER BY shard_id", shardMapper(), jobId);
    }

    /** status 为 null 时返回全部；用于"只重跑失败分片"。 */
    public List<Integer> shardIds(long jobId, ShardStatus status) {
        if (status == null) {
            return jdbc.queryForList("SELECT shard_id FROM recon_shard WHERE job_id = ? ORDER BY shard_id", Integer.class, jobId);
        }
        return jdbc.queryForList("SELECT shard_id FROM recon_shard WHERE job_id = ? AND status = ? ORDER BY shard_id",
                Integer.class, jobId, status.name());
    }

    public void markShardRunning(long jobId, int shardId, int attempts) {
        jdbc.update("""
                UPDATE recon_shard SET status=?, attempts=?, error_msg=NULL, finished_at=NULL,
                       started_at=COALESCE(started_at, CURRENT_TIMESTAMP)
                WHERE job_id=? AND shard_id=?
                """, ShardStatus.RUNNING.name(), attempts, jobId, shardId);
    }

    public void markShardSuccess(long jobId, int shardId, MatchStats stats) {
        jdbc.update("""
                UPDATE recon_shard SET status=?, scanned_left=?, scanned_right=?, matched_rows=?, diff_rows=?,
                       error_msg=NULL, finished_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND shard_id=?
                """, ShardStatus.SUCCESS.name(), stats.scannedLeft(), stats.scannedRight(), stats.matchedRows(),
                stats.diffRows(), jobId, shardId);
    }

    public void markShardFailed(long jobId, int shardId, String errorMsg) {
        jdbc.update("UPDATE recon_shard SET status=?, error_msg=?, finished_at=CURRENT_TIMESTAMP WHERE job_id=? AND shard_id=?",
                ShardStatus.FAILED.name(), truncate(errorMsg, 1000), jobId, shardId);
    }

    // ---------------- 差异 ----------------

    /** 幂等基石：重跑某个分片前先删掉它上一次的差异，再重新写入。 */
    public void deleteShardDiffs(long jobId, int shardId) {
        jdbc.update("DELETE FROM recon_diff WHERE job_id=? AND shard_id=?", jobId, shardId);
    }

    @Override
    public void insertDiffs(List<DiffRecord> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, left_value,
                                        right_value, delta_value, delta_ratio, explanation)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, diffs, diffs.size(), (ps, diff) -> {
            ps.setLong(1, diff.jobId());
            ps.setInt(2, diff.shardId());
            ps.setString(3, diff.diffType());
            ps.setString(4, diff.severity() == null ? Severity.MEDIUM.name() : diff.severity().name());
            ps.setString(5, truncate(diff.bizKey(), 128));
            ps.setString(6, diff.field() == null ? "" : diff.field());
            ps.setString(7, truncate(diff.leftValue(), 512));
            ps.setString(8, truncate(diff.rightValue(), 512));
            ps.setBigDecimal(9, diff.deltaValue());
            ps.setBigDecimal(10, diff.deltaRatio());
            ps.setString(11, truncate(diff.explanation(), 1000));
        });
    }

    public long countDiffs(Long jobId, String diffType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM recon_diff WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (jobId != null) {
            sql.append(" AND job_id = ?");
            args.add(jobId);
        }
        if (diffType != null && !diffType.isBlank()) {
            sql.append(" AND diff_type = ?");
            args.add(diffType);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<DiffRecord> listDiffs(Long jobId, String diffType, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (jobId != null) {
            sql.append(" AND job_id = ?");
            args.add(jobId);
        }
        if (diffType != null && !diffType.isBlank()) {
            sql.append(" AND diff_type = ?");
            args.add(diffType);
        }
        sql.append(" ORDER BY severity DESC, id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), diffMapper(), args.toArray());
    }

    public Optional<DiffRecord> findDiff(long diffId) {
        return jdbc.query("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE id = ?", diffMapper(), diffId)
                .stream().findFirst();
    }

    public void updateExplanation(long diffId, String explanation) {
        jdbc.update("UPDATE recon_diff SET explanation = ? WHERE id = ?", truncate(explanation, 1000), diffId);
    }

    /** 还没写归因说明的差异（批量归因用）。 */
    public List<DiffRecord> listUnexplainedDiffs(long jobId, int limit) {
        return jdbc.query("SELECT " + DIFF_COLUMNS
                        + " FROM recon_diff WHERE job_id = ? AND (explanation IS NULL OR explanation = '') ORDER BY id LIMIT ?",
                diffMapper(), jobId, limit);
    }

    public Map<String, Long> summaryByType(Long jobId) {
        return countBy("diff_type", jobId);
    }

    public Map<String, Long> summaryBySeverity(Long jobId) {
        return countBy("severity", jobId);
    }

    private Map<String, Long> countBy(String column, Long jobId) {
        String sql = "SELECT " + column + " AS k, COUNT(*) AS c FROM recon_diff"
                + (jobId == null ? "" : " WHERE job_id = ?") + " GROUP BY " + column + " ORDER BY c DESC";
        Map<String, Long> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jobId == null
                ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql, jobId);
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("k")), ((Number) row.get("c")).longValue());
        }
        return Collections.unmodifiableMap(result);
    }

    // ---------------- RowMapper ----------------

    private RowMapper<ReconJob> jobMapper() {
        return (rs, rowNum) -> new ReconJob(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getString("batch_id"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("shard_count"),
                rs.getInt("shard_done"),
                rs.getLong("left_rows"),
                rs.getLong("right_rows"),
                rs.getLong("matched_rows"),
                rs.getLong("diff_rows"),
                rs.getString("remark"),
                rs.getString("error_msg"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    private RowMapper<ReconShard> shardMapper() {
        return (rs, rowNum) -> new ReconShard(
                rs.getLong("job_id"),
                rs.getInt("shard_id"),
                ShardStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getLong("scanned_left"),
                rs.getLong("scanned_right"),
                rs.getLong("matched_rows"),
                rs.getLong("diff_rows"),
                rs.getString("error_msg"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    private RowMapper<DiffRecord> diffMapper() {
        return (rs, rowNum) -> new DiffRecord(
                rs.getLong("id"),
                rs.getLong("job_id"),
                rs.getInt("shard_id"),
                rs.getString("diff_type"),
                Severity.from(rs.getString("severity")),
                rs.getString("biz_key"),
                rs.getString("field_name"),
                rs.getString("left_value"),
                rs.getString("right_value"),
                rs.getBigDecimal("delta_value"),
                rs.getBigDecimal("delta_ratio"),
                rs.getString("explanation"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
