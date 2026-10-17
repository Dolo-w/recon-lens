package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.DiffKind;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.engine.FieldType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 规则 DSL：能解析的、以及必须被拦住的错误配置。 */
class RuleParserTest {

    private final RuleParser parser = new RuleParser();

    private static final String VALID = """
            code: T_BILL
            name: 测试规则
            left:
              label: 账单
              table: bill_left
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            right:
              label: 结算
              table: bill_right
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            shard:
              count: 2
              parallelism: 2
              whereLeft: "MOD(id, {shards}) = {index}"
              whereRight: "MOD(id, {shards}) = {index}"
            compare:
              - field: amount
                type: DECIMAL
                tolerance: 0.01
                diffType: AMOUNT_MISMATCH
                severity: HIGH
              - field: status
                type: STRING
                ignoreCase: true
                diffType: STATUS_MISMATCH
                severity: MEDIUM
            missing:
              leftMissingDiffType: MISSING_LEFT
              rightMissingDiffType: MISSING_RIGHT
              severity: HIGH
            duplicate:
              diffType: DUPLICATE_KEY
              severity: MEDIUM
            options:
              maxAttempts: 2
            """;

    private RuleSpec parse(String yaml) {
        return parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    /** 单分片版本：去掉了 count &gt; 1 的切分配置，用例再按需把 where 加回去。 */
    private static String singleShard() {
        return VALID.replace("  count: 2\n  parallelism: 2\n", "  count: 1\n")
                .replace("  whereLeft: \"MOD(id, {shards}) = {index}\"\n", "")
                .replace("  whereRight: \"MOD(id, {shards}) = {index}\"\n", "");
    }

    @Test
    void parsesEverySection() {
        RuleSpec rule = parse(VALID);

        assertThat(rule.code()).isEqualTo("T_BILL");
        assertThat(rule.left().table()).isEqualTo("bill_left");
        assertThat(rule.left().keyType()).isEqualTo(FieldType.STRING);
        assertThat(rule.shard().count()).isEqualTo(2);
        assertThat(rule.shard().effectiveParallelism()).isEqualTo(2);
        assertThat(rule.maxAttempts()).isEqualTo(2);
        assertThat(rule.compare()).hasSize(2);

        FieldRule amount = rule.field("amount").orElseThrow();
        assertThat(amount.type()).isEqualTo(FieldType.DECIMAL);
        assertThat(amount.tolerance()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(amount.hasTolerance()).isTrue();

        FieldRule status = rule.field("STATUS").orElseThrow();
        assertThat(status.ignoreCase()).isTrue();
        assertThat(status.hasTolerance()).isFalse();
    }

    @Test
    void classifiesDiffTypesIntoEngineKinds() {
        RuleSpec rule = parse(VALID);

        assertThat(rule.classify("AMOUNT_MISMATCH")).isEqualTo(DiffKind.VALUE_MISMATCH);
        assertThat(rule.classify("MISSING_LEFT")).isEqualTo(DiffKind.MISSING_LEFT);
        assertThat(rule.classify("MISSING_RIGHT")).isEqualTo(DiffKind.MISSING_RIGHT);
        assertThat(rule.classify("DUPLICATE_KEY")).isEqualTo(DiffKind.DUPLICATE_KEY);
        assertThat(rule.severityOf("AMOUNT_MISMATCH")).isEqualTo(Severity.HIGH);
        assertThat(rule.severityOf("STATUS_MISMATCH")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void bundledRulesAreValid() throws Exception {
        for (String name : new String[]{"/rules/bill-recon.yml", "/rules/customer-sum-recon.yml",
                "/rules/resource-recon.yml", "/rules/customer-status-recon.yml"}) {
            try (InputStream in = getClass().getResourceAsStream(name)) {
                assertThat(in).as("规则文件存在: %s", name).isNotNull();
                RuleSpec rule = parser.parse(in, name);
                assertThat(rule.code()).isNotBlank();
                assertThat(rule.compare()).isNotEmpty();
            }
        }
    }

    @Test
    void keepsThePerSideStaticFilterAndRejectsPlaceholdersInIt() {
        RuleSpec filtered = parse(VALID.replace("  orderBy: order_no\nright:",
                "  orderBy: order_no\n  where: \"state <> 'DELETED'\"\nright:"));

        assertThat(filtered.left().where()).isEqualTo("state <> 'DELETED'");
        assertThat(filtered.right().where()).isNull();

        String withPlaceholder = VALID.replace("  orderBy: order_no\nright:",
                "  orderBy: order_no\n  where: \"MOD(id, {shards}) = {index}\"\nright:");
        assertThatThrownBy(() -> parse(withPlaceholder))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("不支持 {index}/{shards} 占位符");
    }

    @Test
    void rejectsOrderByThatBreaksMergeJoin() {
        String yaml = VALID.replace("  orderBy: order_no\nright:", "  orderBy: id\nright:");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("orderBy 必须等于 keyField");
    }

    @Test
    void rejectsShardedRuleWithoutWhere() {
        String yaml = VALID.replace("  whereLeft: \"MOD(id, {shards}) = {index}\"\n", "");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("必须配置 shard.whereLeft");
    }

    @Test
    void rejectsWhereWithoutPlaceholders() {
        String yaml = VALID.replace("whereLeft: \"MOD(id, {shards}) = {index}\"", "whereLeft: \"id < 100\"");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("{index}");
    }

    @Test
    void rejectsSingleShardWithWhereLeft() {
        String yaml = singleShard().replace("  count: 1\n", "  count: 1\n  whereLeft: \"id < 100\"\n");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("shard.count = 1 时不应配置 shard.whereLeft");
    }

    @Test
    void rejectsSingleShardWithWhereRight() {
        String yaml = singleShard().replace("  count: 1\n", "  count: 1\n  whereRight: \"id < 100\"\n");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("shard.count = 1 时不应配置 shard.whereRight");
    }

    @Test
    void acceptsSingleShardWithoutWhere() {
        RuleSpec rule = parse(singleShard());

        assertThat(rule.shard().count()).isEqualTo(1);
        assertThat(rule.shard().effectiveParallelism()).isEqualTo(1);
        assertThat(rule.shard().whereLeft()).isNull();
        assertThat(rule.shard().whereRight()).isNull();
    }

    @Test
    void rejectsDuplicateDiffTypes() {
        String yaml = VALID.replace("diffType: STATUS_MISMATCH", "diffType: AMOUNT_MISMATCH");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("diffType");
    }

    @Test
    void rejectsToleranceOnStringField() {
        String yaml = VALID.replace("    type: STRING\n    ignoreCase: true",
                "    type: STRING\n    tolerancePercent: 0.01\n    ignoreCase: true");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("只对 DECIMAL/LONG 字段有意义");
    }

    @Test
    void rejectsMissingSectionSoDiffsAreNotSilentlyDropped() {
        String yaml = VALID.substring(0, VALID.indexOf("missing:"));
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("缺少 missing 段");
    }

    @Test
    void rejectsNonUppercaseCode() {
        String yaml = VALID.replace("code: T_BILL", "code: t_bill");
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("code 只能是大写字母");
    }

    @Test
    void singleColumnDimensionUsesTheColumnItself() {
        RuleSpec scalar = parse(dimensionRule("  keyType: STRING\n  dimension: order_no\n"));
        RuleSpec listed = parse(dimensionRule("  keyType: STRING\n  dimension: [order_no]\n"));

        for (RuleSpec rule : List.of(scalar, listed)) {
            assertThat(rule.left().derivedKey()).isTrue();
            assertThat(rule.left().keyField()).isEqualTo(SideSpec.KEY_ALIAS);
            assertThat(rule.left().keyExpression()).isEqualTo("order_no");
            assertThat(rule.left().orderBy()).isEqualTo(SideSpec.KEY_ALIAS);
            assertThat(rule.left().aggregated()).isFalse();
        }
        assertThat(listed.left().dimensionColumns()).containsExactly("order_no");
    }

    @Test
    void compositeDimensionIsConcatenatedWithTheSeparator() {
        RuleSpec rule = parse(dimensionRule("  keyType: STRING\n  dimension: [customer_id, status]\n"));
        assertThat(rule.left().keyExpression()).isEqualTo("CONCAT(customer_id, '|', status)");
        assertThat(rule.left().dimensionColumns()).containsExactly("customer_id", "status");

        RuleSpec custom = parse(dimensionRule("  keyType: STRING\n  dimension: [customer_id, status]\n"
                + "  dimensionSeparator: \"/\"\n"));
        assertThat(custom.left().keyExpression()).isEqualTo("CONCAT(customer_id, '/', status)");
    }

    @Test
    void measuresRequireADimension() {
        String yaml = dimensionRule("  keyType: STRING\n  keyField: order_no\n  orderBy: order_no\n  measures:\n    amount: SUM(amount)\n");

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("measures 需要配合 dimension 使用");
    }

    @Test
    void dimensionAndKeyFieldAreMutuallyExclusive() {
        String yaml = dimensionRule("  keyType: STRING\n  keyField: order_no\n  dimension: order_no\n");

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("只能配置 keyField 或 dimension 之一");
    }

    @Test
    void derivedDimensionDoesNotAcceptOrderBy() {
        String yaml = dimensionRule("  keyType: STRING\n  dimension: order_no\n  orderBy: order_no\n");

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("orderBy 不需要配置");
    }

    @Test
    void aggregatedDimensionMustProduceEveryCompareField() {
        String yaml = dimensionRule("  keyType: STRING\n  dimension: customer_id\n  measures:\n    amount: SUM(amount)\n");

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("比对字段 status 必须出现在 measures 里");
    }

    /** 把 VALID 左侧的 keyField 写法换成声明式维度写法，其余段落保持原样。 */
    private String dimensionRule(String leftLines) {
        return VALID.replace("  keyField: order_no\n  keyType: STRING\n  orderBy: order_no\nright:", leftLines + "right:");
    }
}
