package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.agent.RuleSafetyValidator.Exemptions;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片数的上界（M14）。
 *
 * <p>分片是<b>重跑单位</b>，不是性能旋钮：把一个任务切成 10000 片不会让它更快，
 * 只会让"某一片失败"的可观测性、失败重跑的边际收益和线程池的排队全部失控；
 * 更糟的是每片一次 DELETE 旧差异，片数一多，重跑一个分片反而比跑全量还贵。
 *
 * <p>修复前 {@code checkShard} 只看"分片表列能不能解析成键"，
 * 规则里写 {@code count: 10000} 一样过闸门。这里钉住三件事：
 * 默认上界（256）生效、上界内的分片数不误报、上界可配置且可判"刚好等于上限"。
 */
class ShardCountLimitTest {

    private static final RuleParser PARSER = new RuleParser();
    private static final BigDecimal TWO = new BigDecimal("2.0");

    private static SchemaCatalog schema;
    private static RuleSafetyValidator defaultValidator;
    private static RuleSafetyValidator strictValidator;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:shard_count_limit;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        DataSource dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_left (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_right (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2), status VARCHAR(16))");

        AgentProperties properties = new AgentProperties();
        properties.getSchema().setTables(List.of("bill_left", "bill_right"));
        schema = new SchemaCatalog(dataSource, properties);
        defaultValidator = new RuleSafetyValidator(schema, new ShardSplitAnalyzer(), TWO);
        strictValidator = new RuleSafetyValidator(schema, new ShardSplitAnalyzer(), TWO,
                RuleSafetyValidator.DEFAULT_MAX_TOLERANCE_PERCENT, 8);
    }

    @Test
    void 分片数超过默认上限_闸门一拒绝() {
        List<SafetyViolation> violations = defaultValidator.validate(rule(300), null, Exemptions.none());

        assertThat(violations)
                .extracting(SafetyViolation::code)
                .contains(SafetyViolation.ViolationCode.SHARD_COUNT);
        assertThat(violations)
                .filteredOn(v -> v.code() == SafetyViolation.ViolationCode.SHARD_COUNT)
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.location()).isEqualTo("shard.count");
                    assertThat(v.message()).contains("300").contains("256");
                });
    }

    @Test
    void 上限内的分片数不误报() {
        List<SafetyViolation> violations = defaultValidator.validate(rule(256), null, Exemptions.none());

        assertThat(violations)
                .extracting(SafetyViolation::code)
                .doesNotContain(SafetyViolation.ViolationCode.SHARD_COUNT);
    }

    @Test
    void 上限可配置_且边界值算通过() {
        assertThat(strictValidator.validate(rule(8), null, Exemptions.none()))
                .extracting(SafetyViolation::code)
                .doesNotContain(SafetyViolation.ViolationCode.SHARD_COUNT);
        // 8 是上限本身，9 才越界
        assertThat(strictValidator.validate(rule(9), null, Exemptions.none()))
                .extracting(SafetyViolation::code)
                .contains(SafetyViolation.ViolationCode.SHARD_COUNT);
    }

    private static RuleSpec rule(int shardCount) {
        String yaml = """
                code: SHARD_COUNT_TEST
                name: 分片上界
                left:
                  label: L
                  table: bill_left
                  keyField: order_no
                  keyType: STRING
                  orderBy: order_no
                right:
                  label: R
                  table: bill_right
                  keyField: order_no
                  keyType: STRING
                  orderBy: order_no
                shard:
                  count: %d
                  parallelism: 2
                  whereLeft: "MOD(CRC32(order_no), {shards}) = {index}"
                  whereRight: "MOD(CRC32(order_no), {shards}) = {index}"
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(shardCount);
        return PARSER.parse(new StringReader(yaml), "单测");
    }
}
