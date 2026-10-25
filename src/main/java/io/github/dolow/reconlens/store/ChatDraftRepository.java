package io.github.dolow.reconlens.store;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 会话草稿账本：这个会话起草过哪些规则版本。
 *
 * <p><b>为什么是一张表，而不是塞进 {@code state_json}</b>：账本是**事实**（发生过），
 * 槽位是**焦点**（会被覆盖）。把"我改过三版"这类事实放进槽位，压缩或覆盖一次就没了，
 * 而审批人恰恰要拿它判断"这一版比上一版改了什么"。
 *
 * <p><b>为什么只存 label 不存全文</b>：全文在 {@code recon_rule.spec_json} 里，
 * 这里只存一句"人话锚点"，给「那条按订单和状态对账的」这类指代用。两份全文只会吵架。
 */
public class ChatDraftRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChatDraftRepository.class);

    private final JdbcTemplate jdbc;

    public ChatDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条草稿记录：哪一版、叫什么、一句话是什么。 */
    public record ChatDraft(String ruleCode, int ruleVersion, String label, Instant createdAt) {
    }

    private static final RowMapper<ChatDraft> MAPPER = (rs, rowNum) -> new ChatDraft(
            rs.getString("rule_code"),
            rs.getInt("rule_version"),
            rs.getString("label"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant());

    /**
     * 记一条。同一 session 的同 (code, version) 重复记录是幂等的（重放/重试会撞），直接忽略。
     *
     * <p>不用 {@code MERGE}/{@code ON DUPLICATE KEY}：H2 与 MySQL 语法不同，双跑约定要求同一句 SQL 两边都能跑。
     */
    public void record(long sessionId, String ruleCode, int ruleVersion, String label) {
        try {
            jdbc.update("INSERT INTO recon_chat_draft (session_id, rule_code, rule_version, label) "
                    + "VALUES (?, ?, ?, ?)", sessionId, ruleCode, ruleVersion, label);
        } catch (DataAccessException e) {
            // 唯一键撞了是正常的（重放/重试）；其他原因必须留下痕迹——
            // 早先这里把"表不存在/列名错"也一起吞了，表现是账本永远是空的，且没有任何日志。
            String message = e.getMostSpecificCause().getMessage();
            boolean duplicate = message != null
                    && (message.contains("Unique index") || message.contains("Duplicate entry")
                        || message.contains("PRIMARY KEY"));
            if (duplicate) {
                log.debug("账本已存在，跳过 session={} {} v{}", sessionId, ruleCode, ruleVersion);
            } else {
                log.warn("账本写入失败 session={} {} v{}：{}", sessionId, ruleCode, ruleVersion, message);
            }
        }
    }

    /** 按创建顺序列出（"第一条/第二条"靠的就是这个顺序）。 */
    public List<ChatDraft> list(long sessionId) {
        return jdbc.query("SELECT rule_code, rule_version, label, created_at FROM recon_chat_draft "
                + "WHERE session_id = ? ORDER BY created_at, id", MAPPER, sessionId);
    }

    /** 最近一条草稿：用户说"刚才那条"时的兜底。 */
    public ChatDraft latest(long sessionId) {
        List<ChatDraft> all = list(sessionId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
