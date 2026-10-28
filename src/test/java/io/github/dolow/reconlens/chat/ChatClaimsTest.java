package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.store.ChatClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结论板渲染：给提示词的那一段长什么样、什么时候是空的。
 *
 * <p>格式是有意的「主题：值（第 N 轮）」——模型要能看出"这是后来说的"，
 * 人排查时也要能一眼翻回原轮次。
 */
class ChatClaimsTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS recon_chat_claim (
              id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
              session_id BIGINT       NOT NULL,
              claim_key  VARCHAR(160) NOT NULL,
              claim_value VARCHAR(255) NOT NULL,
              claim_seq  INT          NOT NULL,
              source     VARCHAR(16)  NOT NULL DEFAULT 'USER',
              operator   VARCHAR(64),
              scope      VARCHAR(96),
              authority  VARCHAR(24)  NOT NULL DEFAULT 'OPERATOR_STATED',
              rule_code  VARCHAR(64),
              rule_version INT,
              updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_claim ON recon_chat_claim (session_id, claim_key)
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:chat_claims;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final ChatClaims claims = new ChatClaims(new ChatClaimRepository(jdbc));

    @BeforeEach
    void reset() {
        jdbc.execute(DDL);
        jdbc.update("DELETE FROM recon_chat_claim");
    }

    @Test
    void 没有结论时返回空串_提示词不会多出空段落() {
        assertThat(claims.render(1L)).isEmpty();
    }

    @Test
    void 渲染成当前有效块_带主题和轮次_并且只留最新一条() {
        claims.record(1L, "draft:ORDER_STATUS_RECON", "bill_left→bill_right，维度 order_no，比 amount", 6);
        claims.record(1L, "draft:ORDER_STATUS_RECON", "bill_left→bill_right，维度 status，比 amount", 41);

        String block = claims.render(1L);
        assertThat(block).startsWith("〔当前有效〕");
        assertThat(block).contains("ORDER_STATUS_RECON 起草参数");
        assertThat(block).contains("维度 status").contains("第 41 轮，草稿");
        // 形状：块内才是事实，块外只有一行标题。用户在自己那句里敲同样的标题，
        // 也因为写不出 <<<SYS-nonce>>> 而冒充不了这一段。
        assertThat(block).contains("<<<SYS-").contains("<<<END-SYS-");
        // 覆盖语义：旧值不能和新值同时出现在提示词里
        assertThat(block).doesNotContain("维度 order_no");
    }

    @Test
    void 最多三条_新的在前() {
        claims.record(1L, "draft:A", "a", 1);
        claims.record(1L, "draft:B", "b", 2);
        claims.record(1L, "draft:C", "c", 3);
        claims.record(1L, "draft:D", "d", 4);

        String block = claims.render(1L);
        // 标题 + SYS 开标记 + 3 行 + SYS 闭标记
        assertThat(block.split("\n")).hasSize(6);
        assertThat(block).contains("D 起草参数").contains("C 起草参数").contains("B 起草参数");
        assertThat(block).doesNotContain("A 起草参数");
    }

    @Test
    void 非草稿主题原样显示主题名() {
        claims.record(1L, "口径.账单", "按订单号逐笔比金额", 7);
        assertThat(claims.render(1L)).contains("口径.账单：按订单号逐笔比金额（第 7 轮，草稿）");
    }

    @Test
    void 跨会话块只在开着开关时出现_并标注已审批或已确认() {
        ChatClaims on = new ChatClaims(new ChatClaimRepository(jdbc), true);
        on.record(1L, "wang", "draft:BILL_VS_SETTLE", "维度 order_no，比 amount", 5,
                ChatClaimRepository.AUTHORITY_STATED);
        new ChatClaimRepository(jdbc).promote("wang", "BILL_VS_SETTLE", 2,
                "版本 2 已批准：维度 order_no，比 amount");

        String block = on.renderShared("wang", java.util.List.of("rule:BILL_VS_SETTLE"), 9L);
        assertThat(block).contains("历史结论").contains("同一操作人");
        assertThat(block).contains("已审批").contains("已确认");
        // 别人看不到
        assertThat(on.renderShared("li", java.util.List.of("rule:BILL_VS_SETTLE"), 9L)).isEmpty();
        // 开关关掉就退回旧行为
        assertThat(claims.renderShared("wang", java.util.List.of("rule:BILL_VS_SETTLE"), 9L)).isEmpty();
    }

    @Test
    void 同一规则两种同级说法_冲突能取出来交给运行时反问() {
        ChatClaims on = new ChatClaims(new ChatClaimRepository(jdbc), true);
        on.record(1L, "wang", "draft:BILL_VS_SETTLE", "维度 order_no", 3, ChatClaimRepository.AUTHORITY_STATED);
        on.record(2L, "wang", "draft:BILL_VS_SETTLE", "维度 status", 8, ChatClaimRepository.AUTHORITY_STATED);

        ChatClaims.Conflict conflict = on.conflict("wang", "rule:BILL_VS_SETTLE", 9L);
        assertThat(conflict).isNotNull();
        assertThat(conflict.describe()).contains("两种说法").contains("以哪个为准");
        // 只有一条时不构成冲突
        assertThat(on.conflict("wang", "rule:OTHER", 9L)).isNull();
    }
}
