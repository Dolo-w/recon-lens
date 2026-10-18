package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialStateCodec;
import io.github.dolow.reconlens.rule.SpecHasher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 规则库的持久化：规则以"code + version"留档，运行时永远取最新的<b>生效</b>版本。
 * <p>插入只追加不覆盖 —— 改规则 = 落一个新版本，历史任务钉住的旧版本仍然可回放。
 *
 * <p><b>两个维度不要混</b>：{@code enabled} 是 code 级（一次下线该 code 的所有版本），
 * {@code lifecycle} 是 version 级（这一版是草稿 / 待审 / 生效 / 驳回）。
 * 因此所有"给运行时看"的查询都必须同时带 {@code enabled = TRUE AND lifecycle = 'ACTIVE'}。
 */
@Repository
public class RuleRepository {

    private static final String COLUMNS = "id, code, version, spec_json, enabled, lifecycle, origin, base_version,"
            + " draft_reason, spec_sha256, reviewed_spec_sha, reviewed_by, reviewed_at, created_by, created_at";

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条规则版本：specJson 是可原样回放的配置体（YAML 归一化后的 JSON）。 */
    public record RuleRow(long id, String code, int version, String specJson, boolean enabled,
                          RuleLifecycle lifecycle, RuleOrigin origin, Integer baseVersion, String draftReason,
                          String specSha256, String reviewedSpecSha, String reviewedBy, Instant reviewedAt,
                          String createdBy, Instant createdAt) {

        /** 运行时可见：code 没被下线，且这一版是生效版。 */
        public boolean runtimeVisible() {
            return enabled && lifecycle == RuleLifecycle.ACTIVE;
        }
    }

    public int nextVersion(String code) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM recon_rule WHERE code = ?",
                Integer.class, code);
        return (max == null ? 0 : max) + 1;
    }

    /** 人工 / seed 直通生效版本的既有路径（保持引入 Agent 之前的行为）。 */
    public long insert(String code, int version, String specJson, String createdBy) {
        return insert(code, version, specJson, createdBy, RuleLifecycle.ACTIVE, RuleOrigin.HUMAN,
                null, null, SpecHasher.sha256(specJson));
    }

    public long insert(String code, int version, String specJson, String createdBy,
                       RuleLifecycle lifecycle, RuleOrigin origin, Integer baseVersion, String draftReason,
                       String specSha256) {
        jdbc.update("INSERT INTO recon_rule (code, version, spec_json, enabled, lifecycle, origin, base_version,"
                        + " draft_reason, spec_sha256, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                code, version, specJson, true, lifecycle.name(), origin.name(), baseVersion,
                draftReason, specSha256, createdBy);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM recon_rule", Long.class);
        return id == null ? -1L : id;
    }

    /** 运行时可用的最新版本：<b>必须同时带 enabled 与 lifecycle</b>。 */
    public Optional<RuleRow> findLatest(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule"
                        + " WHERE code = ? AND enabled = TRUE AND lifecycle = 'ACTIVE' ORDER BY version DESC",
                mapper(), code).stream().findFirst();
    }

    /** 任意状态的最新版本（生命周期管理用，含草稿）。 */
    public Optional<RuleRow> findLatestAnyState(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE code = ? ORDER BY version DESC",
                mapper(), code).stream().findFirst();
    }

    /**
     * 按版本取：<b>不按 lifecycle 过滤</b> —— 历史任务要能回放它当年钉住的那一版。
     */
    public Optional<RuleRow> findByVersion(String code, int version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE code = ? AND version = ?",
                mapper(), code, version).stream().findFirst();
    }

    /**
     * 轻量守卫行：只取"审批后有没有被改过"这件事需要的三列。
     *
     * <p>为什么不直接复用 {@link #findLatest}：解析规则体（{@code RuleParser}）比读一行贵一个量级，
     * 而缓存命中的场景只需要"version 还是不是那一版 + 内容哈希对不对"。
     * 这里两条 SQL 都落在 {@code recon_rule (code, version)} 索引上，代价是单行主键级读取。
     */
    public record RuleGuard(int version, String specJson, String reviewedSpecSha) {
    }

    /** 最新生效版本的守卫行（与 {@link #findLatest} 同一过滤条件）。 */
    public Optional<RuleGuard> findGuard(String code) {
        return jdbc.query("SELECT version, spec_json, reviewed_spec_sha FROM recon_rule"
                        + " WHERE code = ? AND enabled = TRUE AND lifecycle = 'ACTIVE'"
                        + " ORDER BY version DESC LIMIT 1",
                guardMapper(), code).stream().findFirst();
    }

    /** 指定版本的守卫行（回放历史任务时用）。 */
    public Optional<RuleGuard> findGuard(String code, int version) {
        return jdbc.query("SELECT version, spec_json, reviewed_spec_sha FROM recon_rule"
                        + " WHERE code = ? AND version = ?",
                guardMapper(), code, version).stream().findFirst();
    }

    private static RowMapper<RuleGuard> guardMapper() {
        return (rs, rowNum) -> new RuleGuard(rs.getInt("version"), rs.getString("spec_json"),
                rs.getString("reviewed_spec_sha"));
    }


    /**
     * 每个 code 的最新<b>生效</b>版本（规则列表用）。
     *
     * <p>⚠️ 内层子查询的 {@code lifecycle} 条件不能省：只在外层过滤时，内层会选出"最新但仍是 DRAFT 的版本"，
     * 外层再按 ACTIVE 一滤 → 一条规则有草稿时整个 code 从列表里消失（表现为"规则凭空不见了"）。
     */
    public List<RuleRow> listLatest() {
        return jdbc.query("SELECT r." + COLUMNS.replace(", ", ", r.") + " FROM recon_rule r "
                        + "JOIN (SELECT code, MAX(version) AS version FROM recon_rule"
                        + " WHERE enabled = TRUE AND lifecycle = 'ACTIVE' GROUP BY code) m "
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

    /**
     * 状态迁移，带 {@code from} 做乐观并发控制：返回 0 说明这一版已经不是你以为的状态
     * （两个人同时在审 / Agent 又提交了一版），调用方必须当作冲突处理，不能当成功。
     */
    public int transition(String code, int version, RuleLifecycle from, RuleLifecycle to) {
        return jdbc.update("UPDATE recon_rule SET lifecycle = ? WHERE code = ? AND version = ? AND lifecycle = ?",
                to.name(), code, version, from.name());
    }

    /** 审批落账：把"审的是哪一份内容"钉在 {@code reviewed_spec_sha} 上。 */
    public int recordReview(String code, int version, String reviewer, String reviewedSpecSha) {
        return jdbc.update("UPDATE recon_rule SET reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,"
                        + " reviewed_spec_sha = ? WHERE code = ? AND version = ?",
                reviewer, reviewedSpecSha, code, version);
    }

    /** 启动回填：迁移后的历史行没有 spec_sha256。 */
    public List<RuleRow> listMissingSpecSha() {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_rule WHERE spec_sha256 IS NULL", mapper());
    }

    /**
     * 一版规则的试算结论。<b>六列是一个整体</b>：{@code verdict} 为空表示"未跑"，
     * {@code SKIPPED} 必须配 {@code skip_reason}，{@code Trialed} 必须配 {@code coverage} ——
     * 复原成 {@link TrialState} 的规则只在 {@link TrialStateCodec} 里写一次。
     */
    public record TrialRecord(String verdict, String coverage, String coverageReason, String skipReason,
                              String digest, String report) {

        public TrialState state() {
            return TrialStateCodec.fromColumns(verdict, coverage, coverageReason, skipReason);
        }
    }

    public Optional<TrialRecord> findTrial(String code, int version) {
        List<TrialRecord> rows = jdbc.query(
                "SELECT trial_verdict, trial_coverage, trial_coverage_reason, skip_reason, trial_digest, trial_report"
                        + " FROM recon_rule WHERE code = ? AND version = ?",
                (rs, rowNum) -> new TrialRecord(
                        rs.getString("trial_verdict"),
                        rs.getString("trial_coverage"),
                        rs.getString("trial_coverage_reason"),
                        rs.getString("skip_reason"),
                        rs.getString("trial_digest"),
                        rs.getString("trial_report")),
                code, version);
        return rows.stream().findFirst();
    }

    /**
     * 落试算结论。写的是<b>推导结果</b>，不是调用方手填的值：
     * {@code coverage} 来自 {@code TrialVerdictResolver}，这里只负责投影成列（见 {@link TrialStateCodec}）。
     *
     * @param trialSpecSha <b>试算跑的那份内容</b>的哈希（不是落库这一刻的）。必须由调用方按
     *                     它当时快照的 {@code spec_json} 算出来 —— 在仓储里现读现算会把
     *                     "试算之后才改的内容"绑到旧结论上，正好是这条约束要防的方向。
     */
    public int saveTrial(String code, int version, TrialState state, String digest, String reportJson,
                         String trialSpecSha) {
        TrialStateCodec.Columns columns = TrialStateCodec.toColumns(state);
        return jdbc.update("UPDATE recon_rule SET trial_verdict = ?, trial_coverage = ?,"
                        + " trial_coverage_reason = ?, skip_reason = ?, trial_digest = ?, trial_report = ?,"
                        + " trial_spec_sha = ? WHERE code = ? AND version = ?",
                columns.verdict(), columns.coverage(), columns.reasons(), columns.skipReason(),
                digest, reportJson, trialSpecSha, code, version);
    }

    /**
     * 试算绑定的内容哈希。审批侧用它回答"我批的是不是试算过的那份"。
     * 与 {@link #findTrial} 分开取：老的 {@code TrialRecord} 不加字段，调用方只在需要判定时多读一列。
     */
    public Optional<String> findTrialSpecSha(String code, int version) {
        List<String> rows = jdbc.query(
                "SELECT trial_spec_sha FROM recon_rule WHERE code = ? AND version = ?",
                (rs, rowNum) -> rs.getString("trial_spec_sha"), code, version);
        return rows.stream().findFirst().filter(sha -> sha != null && !sha.isBlank());
    }

    public void updateSpecSha(String code, int version, String specSha256) {
        jdbc.update("UPDATE recon_rule SET spec_sha256 = ? WHERE code = ? AND version = ?",
                specSha256, code, version);
    }

    private RowMapper<RuleRow> mapper() {
        return (rs, rowNum) -> new RuleRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getInt("version"),
                rs.getString("spec_json"),
                rs.getBoolean("enabled"),
                RuleLifecycle.from(rs.getString("lifecycle")),
                RuleOrigin.from(rs.getString("origin")),
                (Integer) rs.getObject("base_version"),
                rs.getString("draft_reason"),
                rs.getString("spec_sha256"),
                rs.getString("reviewed_spec_sha"),
                rs.getString("reviewed_by"),
                toInstant(rs.getTimestamp("reviewed_at")),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
