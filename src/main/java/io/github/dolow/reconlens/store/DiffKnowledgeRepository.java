package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 归因知识库 {@code recon_diff_kb}：按 {@code cause_key} 存"这类差异的原因是什么、下一步做什么"。
 *
 * <p><b>它存在的唯一目的是让 LLM 调用次数随运行次数下降</b>（验收 A6）。所以这里
 * 没有向量检索、没有语义相似度 —— 初期数据量小、维度固定，结构化键够了（§14 决策记录）。
 * 语义检索真正需要的不是技术，是"数据够多到能证明键匹配漏了东西"，现在还没有。
 *
 * <p><b>K2 多候选</b>：{@code cause_key} 是普通索引不是唯一索引 —— 同形状差异可以有
 * 多种真实病因（9 月的税、10 月的手续费），一键一答会让 teach 覆盖掉上个月的正确知识。
 * 所以这里的写路径全是 <b>INSERT</b>（候选累加，永不 UPDATE 覆盖），
 * 读路径按 {@code (confidence DESC, hits DESC, id DESC)} 取 top-1 ——
 * 人工确认过的、被反复验证过的、最新的，排前面。
 *
 * <p><b>防堆重复</b>：多候选的代价是"同一个键下同一句废话写一百遍"。
 * 写入方（{@code remember}/{@code teach}）用 {@link #existsByCauseKeyAndCause} 去重 ——
 * 同键同措辞不落第二行，措辞不同才作为新候选累加。
 */
@Repository
public class DiffKnowledgeRepository {

    private static final String COLUMNS = "id, cause_key, rule_code, diff_type, field_name, feature_signature, "
            + "cause, action, confidence, source, hits, misses, created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public DiffKnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条归因。{@code cause} 是值不是键（评审 R3）。 */
    public record Entry(long id,
                        String causeKey,
                        String ruleCode,
                        String diffType,
                        String fieldName,
                        String featureSignature,
                        String cause,
                        String action,
                        BigDecimal confidence,
                        String source,
                        int hits,
                        int misses,
                        String createdBy,
                        Instant createdAt,
                        Instant updatedAt) {

        public static final String SOURCE_TEMPLATE = "TEMPLATE";
        public static final String SOURCE_LLM = "LLM";
        public static final String SOURCE_HUMAN = "HUMAN";

        public boolean humanConfirmed() {
            return SOURCE_HUMAN.equals(source);
        }

        /** 给审核人看的一行：原因 + 动作，动作可能为空。 */
        public String render() {
            if (action == null || action.isBlank()) {
                return cause;
            }
            return cause + " 建议动作：" + action;
        }
    }

    /**
     * 键下的最优候选：人工确认 > 被验证多 > 更新。
     *
     * <p>多候选检索的语义就是"最可信的那条答案"：{@code confidence} 让 HUMAN(0.9)
 * 压过 LLM(0.5)；{@code hits} 让被反复采用的老候选站住；{@code id} 兜底同分时取新。
     */
    public Optional<Entry> findTopByKey(String causeKey) {
        List<Entry> rows = jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb WHERE cause_key = ? "
                        + "ORDER BY confidence DESC, hits DESC, id DESC LIMIT 1",
                mapper(), causeKey);
        return rows.stream().findFirst();
    }

    /** 键下的完整候选集（按 top-1 同序），给 few-shot 注入与运营复核用。 */
    public List<Entry> findCandidates(String causeKey, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb WHERE cause_key = ? "
                        + "ORDER BY confidence DESC, hits DESC, id DESC LIMIT ?",
                mapper(), causeKey, limit);
    }

    /** 同键同措辞是否已存在 —— 多候选模式的去重闸，防止同一句废话堆一百行。 */
    public boolean existsByCauseKeyAndCause(String causeKey, String cause) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_diff_kb WHERE cause_key = ? AND cause = ?",
                Long.class, causeKey, cause);
        return count != null && count > 0;
    }

    public Optional<Entry> findById(long id) {
        List<Entry> rows = jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb WHERE id = ?", mapper(), id);
        return rows.stream().findFirst();
    }

    /**
     * 追加一条候选。<b>永不覆盖</b> —— 多候选模式下写路径只有 INSERT，
     * 人工确认过的高置信条目在结构上就不可能被模型冲掉（不再是靠"先查再插"的约定）。
     *
     * @return 是否真的写入了（调用方负责先 {@link #existsByCauseKeyAndCause} 去重）
     */
    public boolean insert(Entry entry) {
        return jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recon_diff_kb (cause_key, rule_code, diff_type, field_name, feature_signature,"
                            + " cause, action, confidence, source, hits, misses, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,0,0,?,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, entry.causeKey());
            ps.setString(2, entry.ruleCode());
            ps.setString(3, entry.diffType());
            ps.setString(4, entry.fieldName());
            ps.setString(5, entry.featureSignature());
            ps.setString(6, entry.cause());
            ps.setString(7, entry.action());
            ps.setBigDecimal(8, entry.confidence() == null ? new BigDecimal("0.500") : entry.confidence());
            ps.setString(9, entry.source());
            ps.setString(10, entry.createdBy());
            return ps;
        }, new GeneratedKeyHolder()) > 0;
    }

    /** 命中计数：只加一列，不参与任何判定 —— 它是观测值，不是状态机。 */
    public void bumpHits(long id) {
        jdbc.update("UPDATE recon_diff_kb SET hits = hits + 1 WHERE id = ?", id);
    }

    /**
     * 借过的次数。
     *
     * <p>注意语义边界：{@code misses} 记在"有行但没被采用"（置信度不足）。
     * <b>完全没有对应行时无处记</b> —— 这是把 misses 放在行上的结构性缺陷。
     * 所以命中率的分母由 {@code DiffKnowledgeBase} 的进程内计数器补齐，
     * 库里的 {@code misses} 只回答"这条已有归因被绕过几次"。
     */
    public void bumpMisses(long id) {
        jdbc.update("UPDATE recon_diff_kb SET misses = misses + 1 WHERE id = ?", id);
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb ORDER BY id DESC LIMIT ?", mapper(), limit);
    }

    /** 按来源计数，给"知识库在长还是在烂"这个问题一个可查的数。 */
    public Map<String, Long> countBySource() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT source, COUNT(*) AS n FROM recon_diff_kb GROUP BY source",
                rs -> {
                    counts.put(rs.getString("source"), rs.getLong("n"));
                });
        return counts;
    }

    private RowMapper<Entry> mapper() {
        return (ResultSet rs, int rowNum) -> new Entry(
                rs.getLong("id"),
                rs.getString("cause_key"),
                rs.getString("rule_code"),
                rs.getString("diff_type"),
                rs.getString("field_name"),
                rs.getString("feature_signature"),
                rs.getString("cause"),
                rs.getString("action"),
                rs.getBigDecimal("confidence"),
                rs.getString("source"),
                rs.getInt("hits"),
                rs.getInt("misses"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
