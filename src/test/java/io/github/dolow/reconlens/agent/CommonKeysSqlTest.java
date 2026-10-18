package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.dsl.DuplicateSpec;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.MissingSpec;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.ShardSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import io.github.dolow.reconlens.domain.Severity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5-b 分母（两侧共同业务键数）的 SQL。
 *
 * <p>这条 SQL 原来用的是 {@code INTERSECT}，它在 H2 上完全正常，在目标库 MySQL 8.0.29 上是
 * {@code ERROR 1064}（{@code INTERSECT} 从 8.0.31 才有）。后果不是"报错很明显"，而是
 * 被上层 catch 成 {@code INCONCLUSIVE} —— 最关键的"分片切错"判定在生产上从未真正跑过，
 * 而所有单元测试都是绿的。所以这里既断言"能用"，也断言"不再含 INTERSECT"。
 */
class CommonKeysSqlTest {

    private static DataSource dataSource;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:common_keys;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS sample_left (order_no VARCHAR(32))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sample_right (order_no VARCHAR(32))");
        jdbc.execute("DELETE FROM sample_left");
        jdbc.execute("DELETE FROM sample_right");
        // 左侧：A 重复两次、B、C、NULL；右侧：A、B、D、NULL
        jdbc.update("INSERT INTO sample_left (order_no) VALUES ('A'),('A'),('B'),('C'),(NULL)");
        jdbc.update("INSERT INTO sample_right (order_no) VALUES ('A'),('B'),('D'),(NULL)");

        // 表达式键单独一套数据：不含 NULL，避免 CONCAT 遇 NULL 的数据库差异干扰断言
        jdbc.execute("CREATE TABLE IF NOT EXISTS expr_left (order_no VARCHAR(32))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS expr_right (order_no VARCHAR(32))");
        jdbc.execute("DELETE FROM expr_left");
        jdbc.execute("DELETE FROM expr_right");
        jdbc.update("INSERT INTO expr_left (order_no) VALUES ('A'),('A'),('B')");
        jdbc.update("INSERT INTO expr_right (order_no) VALUES ('A'),('C')");
    }

    private static int countCommonKeys(RuleSpec rule, String left, String right) {
        String sql = ShadowTrialService.commonKeysSql(rule,
                new SampleMaterializer.Materialized("test-run", left, right));
        Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class);
        return count == null ? -1 : count;
    }

    @Test
    void 共同键按去重计数_且不使用INTERSECT() {
        RuleSpec rule = rule("sample_left", "sample_right", "order_no", "order_no", null, null);

        String sql = ShadowTrialService.commonKeysSql(rule,
                new SampleMaterializer.Materialized("test-run", "sample_left", "sample_right"));

        assertThat(sql).doesNotContainIgnoringCase("INTERSECT");
        // A、B 两个；A 在左侧重复不算两次；NULL 不计入
        assertThat(countCommonKeys(rule, "sample_left", "sample_right")).isEqualTo(2);
    }

    /**
     * 业务键是表达式时，不能给表达式加表别名前缀（{@code r.CONCAT(...)} 不是合法 SQL）。
     * 这里用 {@code dimension} 造一个表达式键，正面验证 SQL 形状对表达式也成立。
     */
    @Test
    void 业务键是表达式时同样可用() {
        String expr = "CONCAT(order_no, '|')";
        RuleSpec rule = rule("expr_left", "expr_right", "order_no", "order_no", expr, expr);

        // 左 A| A| B|  vs 右 A| C| → 只有 A| 是共同的（左侧重复只算一次）
        assertThat(countCommonKeys(rule, "expr_left", "expr_right")).isEqualTo(1);
    }

    private static RuleSpec rule(String tableLeft, String tableRight, String leftKey, String rightKey,
                                 String leftDimension, String rightDimension) {
        return new RuleSpec("COMMON_KEYS_TEST", "共同键 SQL", "单测",
                new SideSpec("L", tableLeft, null, leftKey, FieldType.STRING, leftKey,
                        leftDimension, List.of(), SideSpec.DEFAULT_DIMENSION_SEPARATOR, Map.of(), null),
                new SideSpec("R", tableRight, null, rightKey, FieldType.STRING, rightKey,
                        rightDimension, List.of(), SideSpec.DEFAULT_DIMENSION_SEPARATOR, Map.of(), null),
                new ShardSpec(1, 1, null, null),
                List.of(new FieldRule("order_no", FieldType.STRING, false, BigDecimal.ZERO, null,
                        "AMOUNT_MISMATCH", Severity.HIGH)),
                new MissingSpec("MISSING_LEFT", "MISSING_RIGHT", Severity.HIGH),
                new DuplicateSpec("DUPLICATE_KEY", Severity.MEDIUM),
                2);
    }
}
