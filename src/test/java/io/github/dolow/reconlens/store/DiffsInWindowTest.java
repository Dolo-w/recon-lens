package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.engine.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基线差异按采样窗口过滤时，必须按<b>业务键的真实类型</b>比较。
 *
 * <p>{@code recon_diff.biz_key} 是 VARCHAR，而窗口边界对数值键是不加引号的字面量。
 * 直接在 VARCHAR 上比，{@code "9"} 会被判成 {@code > "100"} ——
 * 数值键的基线差异会被窗口滤空，于是"消失的差异"是空集，T3 自动通过。
 * 这不是"少查了几行"，是<b>把闸门 2 的一个判据关掉了</b>。
 */
class DiffsInWindowTest {

    private static final String DDL_DIFF = """
            CREATE TABLE IF NOT EXISTS recon_diff (
              id          BIGINT AUTO_INCREMENT PRIMARY KEY,
              job_id      BIGINT       NOT NULL,
              shard_id    INT          NOT NULL,
              diff_type   VARCHAR(32)  NOT NULL,
              severity    VARCHAR(8)   NOT NULL,
              biz_key     VARCHAR(128) NOT NULL,
              field_name  VARCHAR(64)  NOT NULL DEFAULT '',
              left_value  VARCHAR(512),
              right_value VARCHAR(512),
              delta_value DECIMAL(20,4),
              delta_ratio DECIMAL(12,6),
              explanation VARCHAR(1000)
            )
            """;

    private static final long JOB = 7L;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:diff_window;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    private final ReconRepository repository = new ReconRepository(jdbc);

    @BeforeEach
    void setUp() {
        jdbc.execute(DDL_DIFF);
        jdbc.update("DELETE FROM recon_diff");
        insert("9");
        insert("10");
        insert("100");
        insert("1000");     // 窗口外
    }

    @Test
    void 数值键的窗口按数值大小过滤() {
        List<DiffRecord> rows = repository.diffsInWindow(JOB, "9", "100", FieldType.LONG);

        assertThat(rows).extracting(DiffRecord::bizKey)
                .containsExactlyInAnyOrder("9", "10", "100");
    }

    @Test
    void 字符串比较会把整批基线差异滤空_这正是要避开的() {
        List<DiffRecord> rows = repository.diffsInWindow(JOB, "9", "100");

        // 字典序下 "10" < "9"，"9" > "100"，区间 [9, 100] 里一行都不剩；
        // 基线差异为空 → T3 的"消失差异"也是空集 → "消失必须可解释"白送一个 PASS。
        assertThat(rows).isEmpty();
    }

    @Test
    void 字符串键仍按字典序_不能强行转数字() {
        List<DiffRecord> rows = repository.diffsInWindow(JOB, "10", "100", FieldType.STRING);

        assertThat(rows).extracting(DiffRecord::bizKey)
                .containsExactlyInAnyOrder("10", "100");
    }

    private void insert(String bizKey) {
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name,"
                        + " left_value, right_value) VALUES (?,?,?,?,?,?,?,?)",
                JOB, 0, "AMOUNT_MISMATCH", "HIGH", bizKey, "amount", "1", "2");
    }
}
