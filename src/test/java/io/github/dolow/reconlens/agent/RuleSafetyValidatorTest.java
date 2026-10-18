package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.agent.SafetyViolation.ViolationCode;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 1：结构校验 + 业务安全。
 *
 * <p>重点不是"能不能拦住明显非法的规则"，而是<b>能不能拦住"合法但错误"的规则</b>：
 * 容差放大、严重级降级、比对字段被删 —— 这三类规则语法完全正确、跑起来一切正常，
 * 只是对账从此"全绿"而钱是错的。
 */
class RuleSafetyValidatorTest {

    private static final RuleParser PARSER = new RuleParser();

    private static RuleSafetyValidator validator;

    private static final String TWO_FIELDS = """
              - field: amount
                type: DECIMAL
                tolerance: 0.01
                diffType: AMOUNT_MISMATCH
                severity: HIGH
              - field: status
                type: STRING
                ignoreCase: true
                diffType: STATUS_MISMATCH
                severity: HIGH
            """;

    private static final String ONE_FIELD_LOOSE = """
              - field: amount
                type: DECIMAL
                tolerance: 10
                diffType: AMOUNT_MISMATCH
                severity: HIGH
            """;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:safety_validator;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
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
        validator = new RuleSafetyValidator(new SchemaCatalog(dataSource, properties),
                new io.github.dolow.reconlens.dsl.ShardSplitAnalyzer(), new BigDecimal("2.0"));
    }

    private static RuleSpec rule(String compareBlock, String shardBlock) {
        String yaml = """
                code: SAFETY_TEST
                name: 闸门 1 单测
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                """ + shardBlock + "\ncompare:\n" + compareBlock + """
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """;
        return PARSER.parse(new StringReader(yaml), "单测");
    }

    private static RuleSpec simple(String compareBlock) {
        return rule(compareBlock, "shard: {count: 1, parallelism: 1}\n");
    }

    private static RuleSpec withSql(String sql) {
        String yaml = """
                code: SAFETY_SQL_TEST
                name: SQL 规则
                left:
                  label: L
                  sql: "%s"
                  keyField: order_no
                  keyType: STRING
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(sql.replace("\"", "\\\""));
        return PARSER.parse(new StringReader(yaml), "单测");
    }

    private static RuleSpec withDimension(String expression) {
        String yaml = """
                code: SAFETY_DIM_TEST
                name: 表达式维度
                left:
                  label: L
                  table: bill_left
                  keyType: STRING
                  dimension: "%s"
                right:
                  label: R
                  table: bill_right
                  keyType: STRING
                  dimension: "%s"
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(expression, expression);
        return PARSER.parse(new StringReader(yaml), "单测");
    }

    private static List<ViolationCode> codes(List<SafetyViolation> violations) {
        return violations.stream().map(SafetyViolation::code).toList();
    }

    @Test
    void 合法规则没有违规() {
        assertThat(validator.validate(simple(TWO_FIELDS), null)).isEmpty();
    }

    @Test
    void 编造的表被拦下() {
        String yaml = """
                code: SAFETY_TABLE_TEST
                name: t
                left: {label: L, table: bill_secret, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """;
        List<SafetyViolation> violations = validator.validate(
                PARSER.parse(new StringReader(yaml), "单测"), null);

        assertThat(codes(violations)).contains(ViolationCode.UNKNOWN_TABLE);
        assertThat(violations).anySatisfy(v -> assertThat(v.location()).isEqualTo("left.table"));
    }

    @Test
    void 编造的列被拦下() {
        String compare = """
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                  - field: settlement_amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: SETTLE_MISMATCH
                    severity: HIGH
                """;

        List<SafetyViolation> violations = validator.validate(simple(compare), null);

        assertThat(codes(violations)).contains(ViolationCode.UNKNOWN_COLUMN);
        assertThat(violations).anySatisfy(v -> assertThat(v.location()).isEqualTo("compare[1].field"));
    }

    /** 表达式维度的列校验（M6）：静态抽取标识符太脆弱，探针让数据库判定。 */
    @Test
    void 表达式维度_合法表达式不误报() {
        List<SafetyViolation> violations = validator.validate(
                withDimension("CONCAT(order_no, '|', status)"), null);

        assertThat(codes(violations)).doesNotContain(ViolationCode.UNKNOWN_COLUMN);
    }

    @Test
    void 表达式维度_编造的列被探针拦下并给出可用列() {
        List<SafetyViolation> violations = validator.validate(
                withDimension("CONCAT(order_no, '#', region_type)"), null);

        assertThat(codes(violations)).contains(ViolationCode.UNKNOWN_COLUMN);
        assertThat(violations).anySatisfy(v -> {
            assertThat(v.location()).isEqualTo("left.dimension");
            assertThat(v.message()).contains("region_type");
            assertThat(v.suggestion()).contains("可用列");
        });
    }

    @Test
    void 表达式维度_子查询被探针拒绝() {
        List<SafetyViolation> violations = validator.validate(
                withDimension("CONCAT(order_no, '|', (SELECT status FROM bill_right LIMIT 1))"), null);

        assertThat(codes(violations)).contains(ViolationCode.UNKNOWN_COLUMN);
        assertThat(violations).anySatisfy(v -> assertThat(v.message()).contains("子查询"));
    }

    @Test
    void 多语句被拦下() {
        List<SafetyViolation> violations = validator.validate(
                withSql("SELECT order_no, amount FROM bill_left; DROP TABLE bill_left"), null);

        assertThat(codes(violations)).contains(ViolationCode.MULTI_STATEMENT);
    }

    @Test
    void sql必须以SELECT开头() {
        List<SafetyViolation> violations = validator.validate(
                withSql("UPDATE bill_left SET amount = 0"), null);

        assertThat(codes(violations)).contains(ViolationCode.SQL_NOT_READ_ONLY);
    }

    @Test
    void 字符串字面量里的关键字不算写操作() {
        RuleSpec spec = withSql("SELECT order_no, amount FROM bill_left WHERE status <> 'DELETED'");

        assertThat(codes(validator.validate(spec, null))).doesNotContain(ViolationCode.SQL_NOT_READ_ONLY);
    }

    @Test
    void where里出现分号被拦下() {
        String yaml = """
                code: SAFETY_WHERE_TEST
                name: t
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no,
                       where: "status <> 'VOID'; DROP TABLE bill_left"}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """;

        List<SafetyViolation> violations = validator.validate(PARSER.parse(new StringReader(yaml), "单测"), null);

        assertThat(codes(violations)).contains(ViolationCode.MULTI_STATEMENT);
    }

    @Test
    void 分片口径不一致是硬错误() {
        String shard = """
                shard:
                  count: 4
                  parallelism: 4
                  whereLeft: "MOD(order_no, {shards}) = {index}"
                  whereRight: "MOD(id, {shards}) = {index}"
                """;

        List<SafetyViolation> violations = validator.validate(rule(TWO_FIELDS, shard), null);

        assertThat(codes(violations)).containsExactly(ViolationCode.SHARD_SPLIT_KEY);
    }

    @Test
    void 抽不出切分口径不算错() {
        String shard = """
                shard:
                  count: 4
                  parallelism: 4
                  whereLeft: "amount > {index} AND {shards} = {shards}"
                  whereRight: "amount > {index} AND {shards} = {shards}"
                """;

        assertThat(codes(validator.validate(rule(TWO_FIELDS, shard), null)))
                .doesNotContain(ViolationCode.SHARD_SPLIT_KEY);
    }

    @Test
    void 容差放大超过基线两倍被拦下() {
        List<SafetyViolation> violations = validator.validate(
                simple(ONE_FIELD_LOOSE), simple(TWO_FIELDS));

        assertThat(codes(violations)).contains(ViolationCode.TOLERANCE_INFLATED);
        assertThat(violations).anySatisfy(v -> {
            assertThat(v.location()).isEqualTo("compare[0].tolerance");
            assertThat(v.message()).contains("0.01").contains("10");
        });
    }

    @Test
    void 容差在两倍以内放行_收紧更放行() {
        String withinLimit = """
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.02
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;
        String tighter = """
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.001
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;

        assertThat(codes(validator.validate(simple(withinLimit), simple(TWO_FIELDS))))
                .doesNotContain(ViolationCode.TOLERANCE_INFLATED);
        assertThat(codes(validator.validate(simple(tighter), simple(TWO_FIELDS))))
                .doesNotContain(ViolationCode.TOLERANCE_INFLATED);
    }

    /**
     * Agent 起草的<b>首版</b>规则没有基线，倍数校验（{@code baseline == null} 时整段跳过）对它不生效。
     * 没有绝对上限时，{@code tolerancePercent: 90} 是一路绿灯 —— 而它正好能让所有差异消失。
     */
    @Test
    void 首版规则的容差也有绝对上限() {
        String greedy = """
                  - field: amount
                    type: DECIMAL
                    tolerancePercent: 90
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;

        List<SafetyViolation> violations = validator.validate(simple(greedy), null);

        assertThat(codes(violations)).contains(ViolationCode.TOLERANCE_ABSOLUTE_LIMIT);
        assertThat(violations).anySatisfy(v -> assertThat(v.message()).contains("90").contains("5.0"));
    }

    @Test
    void 绝对值与倍数都合规的容差放行() {
        String reasonable = """
                  - field: amount
                    type: DECIMAL
                    tolerancePercent: 2
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;

        assertThat(codes(validator.validate(simple(reasonable), null)))
                .doesNotContain(ViolationCode.TOLERANCE_ABSOLUTE_LIMIT);
    }

    @Test
    void 倍数合规但超过绝对上限也要拦() {
        String baseFivePercent = """
                  - field: amount
                    type: DECIMAL
                    tolerancePercent: 5
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;
        String ninePercent = """
                  - field: amount
                    type: DECIMAL
                    tolerancePercent: 9.5
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;

        // 5% → 9.5% 只有 1.9 倍，倍数校验放行；但它已经越过"业务上还叫对账"的线
        List<SafetyViolation> violations = validator.validate(simple(ninePercent), simple(baseFivePercent));

        assertThat(codes(violations)).doesNotContain(ViolationCode.TOLERANCE_INFLATED);
        assertThat(codes(violations)).contains(ViolationCode.TOLERANCE_ABSOLUTE_LIMIT);
    }

    @Test
    void 从精确比对放宽成有容差也算放大() {        String exact = """
                  - field: amount
                    type: DECIMAL
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;

        assertThat(codes(validator.validate(simple(ONE_FIELD_LOOSE), simple(exact))))
                .contains(ViolationCode.TOLERANCE_INFLATED);
    }

    @Test
    void 严重级降级被拦下() {
        String downgraded = """
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: AMOUNT_MISMATCH
                    severity: LOW
                  - field: status
                    type: STRING
                    ignoreCase: true
                    diffType: STATUS_MISMATCH
                    severity: HIGH
                """;

        assertThat(codes(validator.validate(simple(downgraded), simple(TWO_FIELDS))))
                .contains(ViolationCode.SEVERITY_DOWNGRADED);
    }

    @Test
    void 删掉比对字段被拦下_人工豁免才放行() {
        String onlyAmount = """
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                """;
        RuleSpec candidate = simple(onlyAmount);

        List<SafetyViolation> violations = validator.validate(candidate, simple(TWO_FIELDS));
        assertThat(codes(violations)).contains(ViolationCode.FIELD_REMOVED);

        List<SafetyViolation> exempted = validator.validate(candidate, simple(TWO_FIELDS),
                new RuleSafetyValidator.Exemptions(Set.of("status")));
        assertThat(codes(exempted)).doesNotContain(ViolationCode.FIELD_REMOVED);
    }

    @Test
    void 新增字段不是放宽() {
        RuleSpec baseline = simple(ONE_FIELD_LOOSE.replace("tolerance: 10", "tolerance: 0.01"));

        assertThat(codes(validator.validate(simple(TWO_FIELDS), baseline)))
                .doesNotContain(ViolationCode.FIELD_REMOVED, ViolationCode.TOLERANCE_INFLATED);
    }
}
