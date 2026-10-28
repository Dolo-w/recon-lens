package io.github.dolow.reconlens.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 会话里的「当前有效结论」：同一主题后写的覆盖先写的；同一操作人跨会话按权威等级读取。
 *
 * <p><b>为什么不是"再存一份摘要"</b>：摘要是按轮次追加的散文，它能让旧话变短，
 * 但它表达不了「第 3 轮说比金额、第 40 轮改成比状态」里哪一句还算数——压缩一次，
 * 旧结论要么被留着和新结论打架，要么被折掉只剩"提到过"的索引。这里按
 * {@code claim_key} 覆盖，读的时候只读最新一条：压缩掉的是过程，不是结论。
 *
 * <p><b>跨会话的同一张表</b>：会话内覆盖靠 {@code (session_id, claim_key)} 唯一键；
 * 跨会话读靠 {@code operator + scope + updated_at} 索引——同一操作人、同一作用域的
 * 多条结论都在，按「权威等级 → 时间」取最新的一条，历史不会被覆盖丢失。
 *
 * <p><b>为什么是普通 bean 而不是 {@code ChatConfiguration} 里的 {@code @Bean}</b>：
 * 审批通过要写结论，审批通道在 agent 关闭时也必须能起来；挂在 agent 的条件配置里，
 * 会让"没开 agent"的实例连审批都起不来。
 *
 * <p><b>为什么只记结构化主题</b>：口径、参数都能落到规则编码/列名/任务号上；
 * 自由文本的「以后都这样」没有可靠的覆盖判据，硬做只会把旧结论伪造成新结论。
 * 这一层先只收能确定归属的主题，未知主题宁可不记。
 */
@org.springframework.stereotype.Repository
public class ChatClaimRepository {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChatClaimRepository.class);

    private static final int MAX_KEY = 160;
    private static final int MAX_VALUE = 255;

    /** 权威等级：审批过的人话结论 > 会话里确认过的 > 草稿里的。 */
    public static final String AUTHORITY_APPROVED = "HUMAN_APPROVED";
    public static final String AUTHORITY_STATED = "OPERATOR_STATED";
    public static final String AUTHORITY_DRAFT = "DRAFT_PENDING";

    private final JdbcTemplate jdbc;

    public ChatClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一条结论：主题、内容、第几轮说的、谁说的、以及<b>权威等级</b>。
     *
     * <p>authority 原来只存在于跨会话视图（{@code SharedClaim}），会话内视图拿不到，
     * 于是提示词里"已审批的口径"和"用户随口一句"看起来一样硬。
     */
    public record ChatClaim(String key, String value, int seq, String source, String authority, Instant updatedAt) {
    }

    /** 跨会话读到的一条：带来源会话与权威等级。 */
    public record SharedClaim(String operator, String scope, String key, String value,
                              long sessionId, int seq, String authority, Instant updatedAt) {
    }

    private static final RowMapper<ChatClaim> MAPPER = (rs, rowNum) -> new ChatClaim(
            rs.getString("claim_key"),
            rs.getString("claim_value"),
            rs.getInt("claim_seq"),
            rs.getString("source"),
            rs.getString("authority"),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<SharedClaim> SHARED_MAPPER = (rs, rowNum) -> new SharedClaim(
            rs.getString("operator"),
            rs.getString("scope"),
            rs.getString("claim_key"),
            rs.getString("claim_value"),
            rs.getLong("session_id"),
            rs.getInt("claim_seq"),
            rs.getString("authority"),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());

    /** 主题 → 跨会话作用域：{@code draft:X} 属于 {@code rule:X}，其余归 {@code global}。 */
    public static String scopeOf(String topic) {
        if (topic == null || topic.isBlank()) {
            return "global";
        }
        if (topic.startsWith("draft:")) {
            return "rule:" + topic.substring("draft:".length());
        }
        return "global";
    }

    /**
     * 记一条结论；同一 {@code sessionId + key} 后写覆盖先写。
     *
     * <p>不用 {@code MERGE}/{@code ON DUPLICATE KEY}：H2 与 MySQL 语法不同，双跑约定要求
     * 同一句 SQL 两边都能跑。先 UPDATE、没命中再 INSERT；并发下 INSERT 撞唯一键就退回 UPDATE。
     */
    public void record(long sessionId, String operator, String topic, String value, int seq, String authority) {
        String k = clip(topic, MAX_KEY);
        String v = clip(value, MAX_VALUE);
        String op = operator == null || operator.isBlank() ? null : clip(operator, 64);
        String scope = scopeOf(topic);
        String auth = authority == null || authority.isBlank() ? AUTHORITY_STATED : authority;
        int updated = jdbc.update("UPDATE recon_chat_claim SET claim_value = ?, claim_seq = ?, source = ?, "
                + "operator = ?, scope = ?, authority = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE session_id = ? AND claim_key = ?",
                v, seq, "USER", op, scope, auth, sessionId, k);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO recon_chat_claim (session_id, claim_key, claim_value, claim_seq, source, "
                    + "operator, scope, authority) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionId, k, v, seq, "USER", op, scope, auth);
        } catch (DataAccessException e) {
            // 唯一的正常原因：另一个请求先插了同一条。它是"后写覆盖先写"的一种，更新即可。
            int retried = jdbc.update("UPDATE recon_chat_claim SET claim_value = ?, claim_seq = ?, source = ?, "
                    + "operator = ?, scope = ?, authority = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE session_id = ? AND claim_key = ?",
                    v, seq, "USER", op, scope, auth, sessionId, k);
            if (retried == 0) {
                // 表不存在/列名错这类问题不能静默：没有这张表时功能会"看起来正常但永远为空"。
                log.warn("结论写入失败 session={} key={}：{}", sessionId, k, e.getMostSpecificCause().getMessage());
            }
        }
    }

    /** 兼容旧调用：不带操作人（会话内视图本来就只按 session 读）。 */
    public void record(long sessionId, String topic, String value, int seq, String source) {
        record(sessionId, null, topic, value, seq, AUTHORITY_STATED);
    }

    /**
     * 审批通过后写入一条系统结论：{@code HUMAN_APPROVED}，作用域 {@code rule:code}。
     *
     * <p>用 {@code session_id = 0} 占位：它不属于任何会话，只属于规则版本；
     * key 带版本号，所以每批准一版都留一条，历史可查，读时按时间取最新。
     */
    public void promote(String operator, String code, int version, String value) {
        String key = "approved:v" + version;
        String op = operator == null || operator.isBlank() ? null : clip(operator, 64);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_chat_claim WHERE session_id = 0 AND claim_key = ? AND scope = ?",
                Integer.class, key, "rule:" + clip(code, 64));
        if (exists != null && exists > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO recon_chat_claim (session_id, claim_key, claim_value, claim_seq, source, "
                    + "operator, scope, authority, rule_code, rule_version) VALUES (0, ?, ?, ?, 'SYSTEM', ?, ?, ?, ?, ?)",
                    key, clip(value, MAX_VALUE), version, op, "rule:" + clip(code, 64),
                    AUTHORITY_APPROVED, clip(code, 64), version);
        } catch (DataAccessException e) {
            log.warn("审批结论写入失败 code={} v{}：{}", code, version, e.getMostSpecificCause().getMessage());
        }
    }

    /** 最近更新的几条结论（新的在前），会话内视图用。 */
    public List<ChatClaim> recent(long sessionId, int limit) {
        return jdbc.query("SELECT claim_key, claim_value, claim_seq, source, authority, updated_at FROM recon_chat_claim "
                + "WHERE session_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?",
                MAPPER, sessionId, Math.max(1, limit));
    }

    /**
     * 同一操作人、若干作用域里的历史结论，按「权威等级 → 时间」倒序。
     *
     * <p>排除当前会话：它自己的结论走会话内视图，重复注入只会挤掉预算。
     */
    public List<SharedClaim> shared(String operator, Collection<String> scopes, long excludeSessionId, int limit) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(scopes.size(), "?"));
        String sql = "SELECT operator, scope, claim_key, claim_value, session_id, claim_seq, authority, updated_at "
                + "FROM recon_chat_claim WHERE operator = ? AND scope IN (" + in + ") AND session_id <> ? "
                + "ORDER BY CASE authority WHEN '" + AUTHORITY_APPROVED + "' THEN 0 "
                + "WHEN '" + AUTHORITY_STATED + "' THEN 1 ELSE 2 END, updated_at DESC, id DESC LIMIT ?";
        Object[] args = new Object[2 + scopes.size() + 1];
        args[0] = operator;
        int i = 1;
        for (String scope : scopes) {
            args[i++] = scope;
        }
        args[i++] = excludeSessionId;
        args[i] = Math.max(1, limit);
        return jdbc.query(sql, SHARED_MAPPER, args);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
