package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 规则库的持久化：规则以"code + version"留档，运行时永远取最新的启用版本。
 * <p>插入只追加不覆盖 —— 改规则 = 落一个新版本，历史任务钉住的旧版本仍然可回放。
 */
@Repository
public class RuleRepository {

    private static final String COLUMNS = "id, code, version, spec_json, enabled, created_by, created_at";

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条规则版本：specJson 是可原样回放的配置体（YAML 归一化后的 JSON）。 */
    public record RuleRow(long id, String code, int version, String specJson, boolean enabled,
                          String createdBy, Instant createdAt) {
    }

    public int nextVersion(String code) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM recon_rule WHERE code = ?",
                Integer.class, code);
        return (max == null ? 0 : max) + 1;
    }

    public long insert(String code, int version, String specJson, String createdBy) {
        jdbc.update("INSERT INTO recon_rule (code, version, spec_json, enabled, created_by) VALUES (?,?,?,?,?)",
                code, version, specJson, true, createdBy);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM recon_rule", Long.class);
        return id == null ? -1L : id;
    }

    public Optional<RuleRow> findLatest(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE code = ? AND enabled = TRUE ORDER BY version DESC",
                mapper(), code).stream().findFirst();
    }

    public Optional<RuleRow> findByVersion(String code, int version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE code = ? AND version = ?",
                mapper(), code, version).stream().findFirst();
    }

    /** 每个 code 的最新启用版本（规则列表用）。 */
    public List<RuleRow> listLatest() {
        return jdbc.query("SELECT r." + COLUMNS.replace(", ", ", r.") + " FROM recon_rule r "
                        + "JOIN (SELECT code, MAX(version) AS version FROM recon_rule WHERE enabled = TRUE GROUP BY code) m "
                        + "ON m.code = r.code AND m.version = r.version ORDER BY r.code",
                mapper());
    }

    public List<RuleRow> listVersions(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE code = ? ORDER BY version DESC",
                mapper(), code);
    }

    /** 下线一个规则的全部版本（规则列表里不再出现）。 */
    public int disable(String code) {
        return jdbc.update("UPDATE recon_rule SET enabled = FALSE WHERE code = ?", code);
    }

    private RowMapper<RuleRow> mapper() {
        return (rs, rowNum) -> new RuleRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getInt("version"),
                rs.getString("spec_json"),
                rs.getBoolean("enabled"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
