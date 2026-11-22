package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 业界知识库的哑存储：只做 CRUD，准入/涨落/降级的业务判断全在调用方
 * （IndustryKbService）——和 DiffKnowledgeRepository 同一套纪律。
 */
@Repository
public class IndustryKbRepository {

    private static final RowMapper<IndustryKnowledge> MAPPER = (rs, i) -> new IndustryKnowledge(
            rs.getLong("id"),
            rs.getString("entry_key"),
            IndustryKnowledge.AppliesWhen.fromJson(rs.getString("applies_when")),
            rs.getString("knowledge_text"),
            rs.getString("source"),
            IndustryKnowledge.Origin.valueOf(rs.getString("origin")),
            rs.getDouble("confidence"),
            IndustryKnowledge.Status.valueOf(rs.getString("status")),
            rs.getInt("used_count"),
            rs.getInt("rejected_count"),
            rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public IndustryKbRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IndustryKnowledge> findByKey(String entryKey) {
        List<IndustryKnowledge> rows = jdbc.query(
                "SELECT * FROM recon_industry_kb WHERE entry_key = ?", MAPPER, entryKey);
        return rows.stream().findFirst();
    }

    /** 全部 ACTIVE 条目，注入过滤在内存做（条目量是字典级，几百条封顶，不值得为谓词过滤写 SQL）。 */
    public List<IndustryKnowledge> findActive() {
        return jdbc.query(
                "SELECT * FROM recon_industry_kb WHERE status = 'ACTIVE' ORDER BY confidence DESC, id DESC",
                MAPPER);
    }

    public List<IndustryKnowledge> listAll(int limit) {
        return jdbc.query(
                "SELECT * FROM recon_industry_kb ORDER BY status, confidence DESC, id DESC LIMIT ?",
                MAPPER, limit);
    }

    /** 新条目落库；entry_key 唯一约束兜底并发重复。 */
    public IndustryKnowledge insert(IndustryKnowledge e) {
        jdbc.update("INSERT INTO recon_industry_kb (entry_key, applies_when, knowledge_text, source, "
                        + "origin, confidence, status) VALUES (?,?,?,?,?,?,?)",
                e.entryKey(), e.appliesWhen().toJson(), e.text(), e.source(),
                e.origin().name(), e.confidence(), e.status().name());
        return findByKey(e.entryKey()).orElseThrow();
    }

    /** seed/重复采集的 upsert：同 key 更新文本与来源（版本纪律由调用方管），置信度不动。 */
    public boolean upsert(IndustryKnowledge e) {
        return jdbc.update("UPDATE recon_industry_kb SET applies_when = ?, knowledge_text = ?, "
                        + "source = ?, origin = ?, updated_at = CURRENT_TIMESTAMP WHERE entry_key = ?",
                e.appliesWhen().toJson(), e.text(), e.source(), e.origin().name(), e.entryKey()) > 0;
    }

    public boolean updateConfidence(String entryKey, double confidence, IndustryKnowledge.Status status,
                                    int usedCount, int rejectedCount) {
        return jdbc.update("UPDATE recon_industry_kb SET confidence = ?, status = ?, used_count = ?, "
                        + "rejected_count = ?, updated_at = CURRENT_TIMESTAMP WHERE entry_key = ?",
                confidence, status.name(), usedCount, rejectedCount, entryKey) > 0;
    }

    public boolean updateUsed(int usedCount, String entryKey) {
        return jdbc.update("UPDATE recon_industry_kb SET used_count = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE entry_key = ?", usedCount, entryKey) > 0;
    }

    public long countActive() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_industry_kb WHERE status = 'ACTIVE'", Long.class);
        return n == null ? 0 : n;
    }
}
