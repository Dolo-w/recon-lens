package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.dsl.RuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 人工审批的一次性确认码。
 *
 * <p>存在的理由只有一个：把"用户确实点过确认"从模型的自述变成一个<b>可校验的事实</b>。
 * 确认码由人从 REST 领取（{@code POST /api/rule-reviews/{code}/{version}/confirmation}），
 * MCP 的 {@code recon_submit_approval} 必须带上它才能推进审批 —— 模型拿不到码就发不出请求，
 * 而不是靠提示词"请先征得用户同意"。
 *
 * <p>三条约束缺一不可：<b>一次性</b>（{@code used_at} 只允许从 NULL 变一次）、
 * <b>有时效</b>（默认 5 分钟）、<b>绑定对象</b>（code + version + decision 全对才认）。
 * 用条件 UPDATE 而不是"先查再改"：并发下两个请求同时带同一个码，只有一个能把它用掉。
 */
@Repository
public class ApprovalConfirmationRepository {

    /** 确认码有效期：够用户看清"批的是哪一版"并回一句，又不至于留到明天还能用。 */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** 去掉了 0/O/1/I/U —— 这串码会被人在对话里肉眼转述，别让"零"和"欧"变成一次误批。 */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApprovalConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Confirmation(String code, String ruleCode, int ruleVersion, String decision,
                               String createdBy, Instant createdAt, Instant expiresAt,
                               Instant usedAt, String usedBy) {

        public boolean used() {
            return usedAt != null;
        }

        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    /** 签发一个新码。调用方负责校验"这一版确实需要审批"（状态、decision 合法性）。 */
    public Confirmation issue(String ruleCode, int version, String decision, String createdBy) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL);
        String code = randomCode();
        jdbc.update("""
                INSERT INTO recon_approval_confirmation
                    (code, rule_code, rule_version, decision, created_by, created_at, expires_at)
                VALUES (?,?,?,?,?,?,?)""",
                code, ruleCode, version, decision, createdBy, Timestamp.from(now), Timestamp.from(expiresAt));
        Confirmation issued = new Confirmation(code, ruleCode, version, decision, createdBy, now, expiresAt, null, null);
        return issued;
    }

    /**
     * 用掉一个码：条件 UPDATE 保证"只能成功一次"，随后把这一行读回来做审计。
     *
     * <p>失败时不区分"不存在 / 已用过 / 过期 / 绑定的对象不对" —— 这几种情况对本工具的调用者
     * 是同一个动作：回去让用户重新确认。分开报只会给试探者更多信息。
     */
    public Confirmation consume(String code, String ruleCode, int version, String decision, String usedBy) {
        String normalized = normalize(code);
        int updated = jdbc.update("""
                UPDATE recon_approval_confirmation
                   SET used_at = CURRENT_TIMESTAMP, used_by = ?
                 WHERE code = ?
                   AND rule_code = ?
                   AND rule_version = ?
                   AND decision = ?
                   AND used_at IS NULL
                   AND expires_at > CURRENT_TIMESTAMP""",
                usedBy, normalized, ruleCode, version, decision);
        if (updated == 0) {
            throw new RuleException("确认码无效：" + invalidReason(normalized, ruleCode, version, decision));
        }
        return find(normalized).orElseThrow(() -> new RuleException("确认码刚被消费却读不到，请重试"));
    }

    public Optional<Confirmation> find(String code) {
        List<Confirmation> rows = jdbc.query("SELECT * FROM recon_approval_confirmation WHERE code = ?",
                (rs, i) -> map(rs), normalize(code));
        return rows.stream().findFirst();
    }

    /** 清理过期且未使用的码：只服务运维视角，不参与审批判定。 */
    /**
     * 审批留痕清单（审计导出用）。
     *
     * <p>为什么按 ruleCode 可空：查"某一版到底谁批的"要按规则收窄；而月度审计要的是全量，
     * 少给一条就变成"账外审批"。limit 仍然要有上界，否则一次导出能把内存吃光。
     */
    public List<Confirmation> list(String ruleCode, int limit) {
        int size = limit <= 0 ? 200 : Math.min(limit, 2000);
        if (ruleCode == null || ruleCode.isBlank()) {
            return jdbc.query("SELECT * FROM recon_approval_confirmation ORDER BY created_at DESC LIMIT ?",
                    (rs, rowNum) -> map(rs), size);
        }
        return jdbc.query("SELECT * FROM recon_approval_confirmation WHERE rule_code = ?"
                        + " ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> map(rs), ruleCode, size);
    }

    public int purgeExpired() {
        return jdbc.update("DELETE FROM recon_approval_confirmation WHERE used_at IS NULL AND expires_at <= CURRENT_TIMESTAMP");
    }

    private String invalidReason(String code, String ruleCode, int version, String decision) {
        return find(code).map(row -> {
            if (row.used()) {
                return "这串确认码已经被用过（" + row.usedAt() + "），一次确认只能办一件事，请让用户重新确认";
            }
            if (row.expired(Instant.now())) {
                return "这串确认码已过期（有效期 " + TTL.toMinutes() + " 分钟），请让用户重新确认";
            }
            if (!row.ruleCode().equals(ruleCode) || row.ruleVersion() != version
                    || !row.decision().equals(decision)) {
                return "这串确认码绑定的不是 " + ruleCode + " v" + version + "/" + decision
                        + "（它绑定的是 " + row.ruleCode() + " v" + row.ruleVersion() + "/" + row.decision()
                        + "），不能挪用到别的规则或决策上";
            }
            return "这串确认码不可用，请让用户重新确认";
        }).orElse("没有这串确认码（" + code.length() + " 位），它必须由用户从 REST 领取后转述给你");
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static Confirmation map(ResultSet rs) throws SQLException {
        Timestamp usedAt = rs.getTimestamp("used_at");
        return new Confirmation(
                rs.getString("code"),
                rs.getString("rule_code"),
                rs.getInt("rule_version"),
                rs.getString("decision"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                usedAt == null ? null : usedAt.toInstant(),
                rs.getString("used_by"));
    }
}
