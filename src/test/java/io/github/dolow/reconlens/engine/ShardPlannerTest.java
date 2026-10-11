package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分片规划：占位符替换与取数 SQL 的正确性。 */
class ShardPlannerTest {

    private final RuleSpec rule = TestRules.bill();

    @Test
    void createsOnePlanPerShardWithPlaceholdersReplaced() {
        List<ShardPlan> plans = ShardPlanner.plan(rule);

        assertThat(plans).hasSize(4);
        assertThat(plans).extracting(ShardPlan::shardId).containsExactly(0, 1, 2, 3);
        assertThat(plans.get(0).whereLeft())
                .isEqualTo("MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), 4) = 0");
        assertThat(plans.get(3).whereRight())
                .isEqualTo("MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), 4) = 3");
        assertThat(plans.get(0).whereLeft()).doesNotContain("{shards}", "{index}");
    }

    @Test
    void unsplitRuleHasNoWhereClause() {
        RuleSpec single = new io.github.dolow.reconlens.dsl.RuleParser().parse(
                new java.io.ByteArrayInputStream(TestRules.YAML
                        .replace("  count: 4", "  count: 1")
                        .replaceAll("(?m)^\\s*where(Left|Right):.*\\R", "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "single.yml");

        List<ShardPlan> plans = ShardPlanner.plan(single);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).whereLeft()).isNull();
        assertThat(plans.get(0).whereRight()).isNull();
        assertThat(ShardPlanner.selectSql(single.left(), plans.get(0).whereLeft()))
                .isEqualTo("SELECT * FROM (SELECT * FROM bill_left) src ORDER BY order_no");
    }

    @Test
    void tableRuleSelectsSortedAndWrapped() {
        String sql = ShardPlanner.selectSql(rule.left(), "MOD(id, 4) = 1");

        assertThat(sql).isEqualTo("SELECT * FROM (SELECT * FROM bill_left) src WHERE MOD(id, 4) = 1 ORDER BY order_no");
    }
    @Test
    void customSqlRuleIsWrappedNotAppended() {
        String sql = ShardPlanner.selectSql(rule.left(), null);

        assertThat(sql).startsWith("SELECT * FROM (SELECT * FROM bill_left) src");
        assertThat(sql).endsWith("ORDER BY order_no");
        assertThat(sql).doesNotContain("WHERE");
    }

    @Test
    void derivedDimensionProjectsTheGeneratedKeyColumn() {
        String sql = ShardPlanner.selectSql(dimension("customer_id", java.util.Map.of()), null);

        assertThat(sql).isEqualTo("SELECT customer_id AS __biz_key, src.* FROM (SELECT * FROM bill_left) src"
                + " ORDER BY __biz_key");
    }

    @Test
    void shardConditionStillAppliesToTheRawRows() {
        String sql = ShardPlanner.selectSql(dimension("customer_id", java.util.Map.of()), "MOD(id, 4) = 1");

        assertThat(sql).isEqualTo("SELECT customer_id AS __biz_key, src.*"
                + " FROM (SELECT * FROM (SELECT * FROM bill_left) src WHERE MOD(id, 4) = 1) src"
                + " ORDER BY __biz_key");
    }

    @Test
    void aggregatedDimensionGroupsByTheDimensionExpression() {
        String sql = ShardPlanner.selectSql(dimension("customer_id", java.util.Map.of("amount", "SUM(amount)")),
                "LENGTH(customer_id) = 4");

        assertThat(sql).isEqualTo("SELECT customer_id AS __biz_key, SUM(amount) AS amount"
                + " FROM (SELECT * FROM (SELECT * FROM bill_left) src WHERE LENGTH(customer_id) = 4) src"
                + " GROUP BY customer_id ORDER BY __biz_key");
    }

    @Test
    void sideFilterIsAndedWithTheShardCondition() {
        SideSpec filtered = new SideSpec("账单", "bill_left", null, "order_no", FieldType.STRING, "order_no",
                null, java.util.List.of(), "|", java.util.Map.of(), "state <> 'DELETED'");

        assertThat(ShardPlanner.selectSql(filtered, "MOD(id, 4) = 1"))
                .isEqualTo("SELECT * FROM (SELECT * FROM bill_left) src"
                        + " WHERE (MOD(id, 4) = 1) AND (state <> 'DELETED') ORDER BY order_no");
        assertThat(ShardPlanner.selectSql(filtered, null))
                .isEqualTo("SELECT * FROM (SELECT * FROM bill_left) src WHERE state <> 'DELETED' ORDER BY order_no");
    }

    @Test
    void sideFilterAlsoAppliesToDerivedDimensions() {
        SideSpec filtered = new SideSpec("账单", "bill_left", null, SideSpec.KEY_ALIAS, FieldType.STRING, SideSpec.KEY_ALIAS,
                "customer_id", java.util.List.of("customer_id"), "|", java.util.Map.of(), "status = 'PAID'");

        assertThat(ShardPlanner.selectSql(filtered, "MOD(id, 4) = 1"))
                .isEqualTo("SELECT customer_id AS __biz_key, src.*"
                        + " FROM (SELECT * FROM (SELECT * FROM bill_left) src"
                        + " WHERE (MOD(id, 4) = 1) AND (status = 'PAID')) src ORDER BY __biz_key");
    }

    private static SideSpec dimension(String expression, java.util.Map<String, String> measures) {
        return new SideSpec("账单", "bill_left", null, SideSpec.KEY_ALIAS, FieldType.STRING, SideSpec.KEY_ALIAS,
                expression, java.util.List.of(expression), "|", measures, null);
    }
}
