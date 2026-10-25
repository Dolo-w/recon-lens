package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结论板本身的语义：同主题覆盖、按更新时间倒序、条数上限。
 *
 * <p>不测"什么时候该记"——那是 {@code ChatClaimsTest} 与 {@code ChatRuntimeTest} 的事，
 * 这里只证明一条 SQL 能正确地"后写赢"。
 */
class ChatClaimRepositoryTest {

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
            "jdbc:h2:mem:chat_claim;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final ChatClaimRepository claims = new ChatClaimRepository(jdbc);

    @BeforeEach
    void reset() {
        jdbc.execute(DDL);
        jdbc.update("DELETE FROM recon_chat_claim");
    }

    @Test
    void 同一主题第二次写入覆盖第一次_而不是并排两条() {
        claims.record(1L, "draft:A", "比 amount", 3, "USER");
        claims.record(1L, "draft:A", "比 status", 40, "USER");

        List<ChatClaimRepository.ChatClaim> all = claims.recent(1L, 10);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).value()).isEqualTo("比 status");
        assertThat(all.get(0).seq()).isEqualTo(40);
    }

    @Test
    void 不同主题各留一条_按更新时间倒序() {
        claims.record(1L, "draft:A", "x", 2, "USER");
        claims.record(1L, "draft:B", "y", 3, "USER");

        List<ChatClaimRepository.ChatClaim> all = claims.recent(1L, 10);
        assertThat(all).extracting(ChatClaimRepository.ChatClaim::key).containsExactly("draft:B", "draft:A");
    }

    @Test
    void 会话之间互不串() {
        claims.record(1L, "draft:A", "one", 1, "USER");
        claims.record(2L, "draft:A", "two", 1, "USER");

        assertThat(claims.recent(1L, 10)).extracting(ChatClaimRepository.ChatClaim::value).containsExactly("one");
        assertThat(claims.recent(2L, 10)).extracting(ChatClaimRepository.ChatClaim::value).containsExactly("two");
    }

    @Test
    void 超长内容会截断_不会写爆列宽() {
        claims.record(1L, "draft:A", "长".repeat(400), 1, "USER");
        String value = claims.recent(1L, 1).get(0).value();
        assertThat(value).hasSizeLessThanOrEqualTo(255);
        assertThat(value).endsWith("…");
    }

    @Test
    void 跨会话只取同操作人_同一作用域内新的在前() {
        claims.record(1L, "wang", "draft:BILL_VS_SETTLE", "维度 order_no，比 amount", 5, "OPERATOR_STATED");
        claims.record(2L, "wang", "draft:BILL_VS_SETTLE", "维度 status，比 amount", 9, "OPERATOR_STATED");
        claims.record(3L, "li", "draft:BILL_VS_SETTLE", "别人的说法", 2, "OPERATOR_STATED");
        claims.record(4L, "wang", "draft:OTHER_RULE", "别的规则", 1, "OPERATOR_STATED");

        List<ChatClaimRepository.SharedClaim> rows =
                claims.shared("wang", List.of("rule:BILL_VS_SETTLE"), 0L, 10);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).sessionId()).isEqualTo(2L);
        assertThat(rows.get(0).value()).contains("维度 status");
        assertThat(rows).extracting(ChatClaimRepository.SharedClaim::sessionId).doesNotContain(3L, 4L);
    }

    @Test
    void 审批通过的结论权威最高_排在同级说法前面() {
        claims.record(5L, "li", "draft:BILL_VS_SETTLE", "维度 order_no，比 amount", 3, "OPERATOR_STATED");
        claims.promote("li", "BILL_VS_SETTLE", 2, "版本 2 已批准：维度 order_no，按订单号逐笔比 amount");

        List<ChatClaimRepository.SharedClaim> rows =
                claims.shared("li", List.of("rule:BILL_VS_SETTLE"), -1L, 10);

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).authority()).isEqualTo(ChatClaimRepository.AUTHORITY_APPROVED);
        assertThat(rows.get(0).value()).contains("已批准");
    }
}
