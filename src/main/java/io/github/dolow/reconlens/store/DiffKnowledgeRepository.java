package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
 * <p><b>为什么是"先查再插"而不是 upsert</b>：{@code INSERT ... ON DUPLICATE KEY UPDATE}
 * 在 H2 与 MySQL 里语义不完全一致（H2 需要 MySQL 兼容模式），而本项目所有 SQL 都要两边都能跑。
 * 代价是多一次查询，在这里可以忽略 —— 一次归因本来就要查一次。
 *
 * <p><b>{@code source} 决定谁能覆盖谁</b>：{@code HUMAN} 的条目不会被 {@code LLM} 覆盖，
 * 否则运营手工修正过的归因会在下一轮被模型的另一次胡说冲掉 —— 人工修正就成了白干。
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

    public Optional<Entry> findByKey(String causeKey) {
        List<Entry> rows = jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb WHERE cause_key = ?",
                mapper(), causeKey);
        return rows.stream().findFirst();
    }

    public Optional<Entry> findById(long id) {
        List<Entry> rows = jdbc.query("SELECT " + COLUMNS + " FROM recon_diff_kb WHERE id = ?", mapper(), id);
        return rows.stream().findFirst();
    }

    /**
     * 没有才写。<b>已存在就什么都不做</b> —— 包括不更新 {@code cause}。
     *
     * <p>这条"不做"是刻意的：同一个键下已经有 {@code HUMAN} 修正过的高置信条目时，
     * 新一轮 LLM 的措辞不同但可能更差，覆盖它等于把人工成本反复烧掉。
     *
     * @return 新写入的 id；已存在时返回 {@code Optional.empty()}
     */
    public Optional<Long> insertIfAbsent(Entry entry) {
        if (findByKey(entry.causeKey()).isPresent()) {
            return Optional.empty();
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
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
        }, keys);
        return Optional.ofNullable(generatedId(keys));
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

    /**
     * 人工修正：覆盖原因/动作，置信度升到 0.9，来源改为 {@code HUMAN}。
     * 这是"归因回流"里唯一能提高置信度的入口 —— 模型自己不能给自己升置信度。
     */
    public boolean correctByHuman(String causeKey, String cause, String action, String correctedBy) {
        return jdbc.update("UPDATE recon_diff_kb SET cause = ?, action = ?, confidence = 0.900, source = 'HUMAN',"
                        + " created_by = ?, updated_at = CURRENT_TIMESTAMP WHERE cause_key = ?",
                cause, action, correctedBy, causeKey) > 0;
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

    /**
     * 按列名取主键。
     *
     * <p>不能直接用 {@link KeyHolder#getKey()}：H2 会把 {@code created_at DEFAULT CURRENT_TIMESTAMP}
     * 也当作生成列返回，{@code getKey()} 随即抛
     * {@code contains multiple keys} —— 这个坑在 {@code AgentRunRepository} 上已经踩过一次。
     */
    private static Long generatedId(KeyHolder keys) {
        Map<String, Object> map = keys.getKeys();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if ("ID".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        if (map.size() == 1) {
            Object only = map.values().iterator().next();
            if (only instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }
}
