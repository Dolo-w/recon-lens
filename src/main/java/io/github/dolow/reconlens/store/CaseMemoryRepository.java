package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.recall.CaseMemory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 案例记忆的读写。全部 SQL 都写在这里，服务层不拼 SQL。
 *
 * <p>不加 {@code @Repository}：与其它仓库一致，由配置类手工 {@code @Bean} 装配
 * （见 {@code recall/RecallConfiguration}），这样"有没有这个功能"在装配处一眼可见。
 */
public class CaseMemoryRepository {

    private static final String COLUMNS = "id, rule_code, diff_type, cause_key, resolution, source, confidence,"
            + " operator, diff_id, job_id, dataset_version, hits, last_hit_at, created_at";

    private final JdbcTemplate jdbc;

    public CaseMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String ruleCode, String diffType, String causeKey, String resolution,
                       String source, double confidence, String operator, Long diffId, Long jobId,
                       String datasetVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recon_case_memory (rule_code, diff_type, cause_key, resolution, source,"
                            + " confidence, operator, diff_id, job_id, dataset_version, hits)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, ruleCode);
            ps.setString(2, diffType);
            ps.setString(3, causeKey);
            ps.setString(4, resolution);
            ps.setString(5, source);
            ps.setDouble(6, confidence);
            ps.setString(7, operator);
            ps.setObject(8, diffId);
            ps.setObject(9, jobId);
            ps.setString(10, datasetVersion);
            return ps;
        }, keyHolder);
        return generatedId(keyHolder);
    }

    /** 同一规则同一 cause_key 的最近一条人工案例：命中就能直接复用，不用再问人。 */
    public Optional<CaseMemory> latestHuman(String ruleCode, String causeKey) {
        List<CaseMemory> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM recon_case_memory WHERE rule_code = ? AND cause_key = ?"
                        + " AND source = 'HUMAN' ORDER BY created_at DESC LIMIT 1",
                this::map, ruleCode, causeKey);
        return rows.stream().findFirst();
    }

    /** 候选集：同规则同差异类型，取最近若干条，再交给服务层按时间衰减重排。 */
    public List<CaseMemory> candidates(String ruleCode, String diffType, int limit) {
        return candidates(ruleCode, diffType, null, limit);
    }

    /**
     * 同上，但按数据版本收窄（ADR-0002）。
     *
     * <p>{@code datasetVersion} 非空时只取「同版本」与「没记版本」的案例。保留没记版本的两条理由：
     * 那些是这一列存在之前写下的经验，对同一规则仍有参考价值；而"只取同版本"会把历史整段丢掉——
     * 口径判断恰恰最依赖历史，宁可多召回一条并标出它的版本，也不要假装没有。
     */
    public List<CaseMemory> candidates(String ruleCode, String diffType, String datasetVersion, int limit) {
        if (datasetVersion == null || datasetVersion.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM recon_case_memory WHERE rule_code = ?"
                            + " AND diff_type = ? ORDER BY created_at DESC LIMIT ?",
                    this::map, ruleCode, diffType, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_case_memory WHERE rule_code = ?"
                        + " AND diff_type = ? AND (dataset_version = ? OR dataset_version IS NULL)"
                        + " ORDER BY created_at DESC LIMIT ?",
                this::map, ruleCode, diffType, datasetVersion, limit);
    }

    public List<CaseMemory> list(String ruleCode, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_case_memory WHERE rule_code = ?"
                + " ORDER BY created_at DESC LIMIT ?", this::map, ruleCode, limit);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_case_memory", Integer.class);
        return n == null ? 0 : n;
    }

    /** 被检索命中时累加次数：用于"这条案例被反复验证过"的加权，不做假精度。 */
    public void touch(long id) {
        jdbc.update("UPDATE recon_case_memory SET hits = hits + 1, last_hit_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    private CaseMemory map(ResultSet rs, int rowNum) throws SQLException {
        return new CaseMemory(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getString("diff_type"),
                rs.getString("cause_key"),
                rs.getString("resolution"),
                rs.getString("source"),
                rs.getBigDecimal("confidence") == null ? 0 : rs.getBigDecimal("confidence").doubleValue(),
                rs.getString("operator"),
                (Long) rs.getObject("diff_id", Long.class),
                (Long) rs.getObject("job_id", Long.class),
                rs.getString("dataset_version"),
                rs.getInt("hits"),
                timestamp(rs.getTimestamp("last_hit_at")),
                timestamp(rs.getTimestamp("created_at")));
    }

    private static Instant timestamp(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static long generatedId(KeyHolder keyHolder) {
        // H2 在表里有 DEFAULT 列（created_at）时会返回多个键，getKey() 直接抛异常。
        // 所以先遍历 keys 找 id 列，再退回"只有一个键"的情形，最后才报错。
        var keys = keyHolder.getKeys();
        if (keys != null) {
            for (var entry : keys.entrySet()) {
                if ("id".equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() instanceof Number n) {
                    return n.longValue();
                }
            }
            if (keys.size() == 1 && keys.values().iterator().next() instanceof Number single) {
                return single.longValue();
            }
        }
        throw new IllegalStateException("案例写入后拿不到主键");
    }
}
