package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sql:} 自带截断的识别。
 *
 * <p>这个类的误报与漏报<b>代价不对称</b>：
 * <ul>
 *   <li>漏报 → 试算在截断样本上"通过"，报告里一个字都不提，生产全量却完全不同（最坏情况）；</li>
 *   <li>误报 → 一条本来完整的规则被降成 {@code SAMPLE_PARTIAL}，要人工签字（麻烦，但安全）。</li>
 * </ul>
 * 所以宁可偏保守。但"保守"不等于"什么词都算" —— {@code GROUP BY} 那一条就是把边界钉死的用例：
 * 它一旦被当成截断，所有 {@code measures} 规则永远拿不到 {@code FULL}，降级签字会变成常态，
 * 而"常态化的降级"等于没有降级。
 */
class SqlTruncationDetectorTest {

    @Test
    void limitIsDetected() {
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left ORDER BY id LIMIT 1000"))
                .containsExactly("LIMIT");
    }

    @Test
    void mysqlOffsetCommaFormIsDetected() {
        // LIMIT offset, rows —— 深分页本身还会扫过前 offset 行，但这里只关心"截断"
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left LIMIT 10, 1000"))
                .containsExactly("LIMIT");
    }

    @Test
    void limitWithOffsetKeywordIsDetected() {
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left ORDER BY id LIMIT 1000 OFFSET 20"))
                .containsExactly("LIMIT");
    }

    @Test
    void placeholderLimitIsDetected() {
        // 参数化写法照样截断，不能因为"没有数字"就跳过
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left ORDER BY id LIMIT ?"))
                .containsExactly("LIMIT");
    }

    @Test
    void limitInsideSubqueryIsDetected() {
        // 子查询先截断，外层再筛 —— 截断发生得越深，样本越只是"原查询的一角"
        assertThat(SqlTruncationDetector.detect(
                "SELECT * FROM (SELECT * FROM bill_left ORDER BY id LIMIT 10) x"))
                .containsExactly("LIMIT");
    }

    @Test
    void standardFetchFirstIsDetected() {
        assertThat(SqlTruncationDetector.detect(
                "SELECT * FROM bill_left ORDER BY id OFFSET 20 ROWS FETCH FIRST 10 ROWS ONLY"))
                .containsExactly("FETCH");
    }

    @Test
    void sqlServerTopIsDetected() {
        assertThat(SqlTruncationDetector.detect("SELECT TOP 100 * FROM bill_left")).containsExactly("TOP");
        assertThat(SqlTruncationDetector.detect("SELECT TOP(100) * FROM bill_left")).containsExactly("TOP");
    }

    @Test
    void literalContainingLimitIsNotTruncation() {
        // 值里出现 LIMIT 不是语法
        assertThat(SqlTruncationDetector.detect(
                "SELECT * FROM bill_left WHERE note = 'LIMIT 100'")).isEmpty();
    }

    @Test
    void commentContainingLimitIsNotTruncation() {
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left -- LIMIT 1000\nORDER BY id"))
                .isEmpty();
        assertThat(SqlTruncationDetector.detect("SELECT * FROM bill_left /* LIMIT 1000 */ ORDER BY id"))
                .isEmpty();
    }

    @Test
    void columnNamedLimitIsNotTruncation() {
        // `limit` 是一个**叫 limit 的列**。\b 会把反引号当边界，于是列名就能让整条规则被判成样本残缺
        assertThat(SqlTruncationDetector.detect("SELECT `limit`, order_no FROM bill_left")).isEmpty();
    }

    @Test
    void columnLikeLimitAmountIsNotTruncation() {
        assertThat(SqlTruncationDetector.detect("SELECT limit_amount FROM bill_left")).isEmpty();
    }

    @Test
    void groupByIsNotTruncation() {
        // A23 的后半条：measures 聚合法本来就靠 GROUP BY。把它当截断 = 所有聚合规则永久降级
        assertThat(SqlTruncationDetector.detect(
                "SELECT status, SUM(amount) AS total FROM bill_left GROUP BY status")).isEmpty();
    }

    @Test
    void describeNamesTheOffendingSide() {
        // 只有一侧被截断时，差异会表现成"单边缺失"，而审核人第一眼会怀疑数据
        RuleSpec rule = ruleWithSql(
                "SELECT * FROM bill_left ORDER BY id LIMIT 1000",
                "SELECT * FROM bill_right ORDER BY id");

        assertThat(SqlTruncationDetector.describe(rule)).containsExactly("left: LIMIT");
        assertThat(SqlTruncationDetector.truncated(rule)).isTrue();
    }

    @Test
    void bothSidesCanBeReported() {
        RuleSpec rule = ruleWithSql(
                "SELECT * FROM bill_left LIMIT 100",
                "SELECT TOP 50 * FROM bill_right");

        assertThat(SqlTruncationDetector.describe(rule)).containsExactly("left: LIMIT", "right: TOP");
    }

    @Test
    void tableSideIsIgnored() {
        // table: 形态的 where 是条件片段，不含分句 —— 即使里面出现 LIMIT 字样也不该命中
        RuleSpec base = TestRules.bill();
        SideSpec left = new SideSpec("账单", "bill_left", null, "order_no", FieldType.STRING, "order_no",
                null, java.util.List.of(), SideSpec.DEFAULT_DIMENSION_SEPARATOR, java.util.Map.of(),
                "note <> 'LIMIT 100'");
        RuleSpec rule = new RuleSpec(base.code(), base.name(), base.description(), left, base.right(),
                base.shard(), base.compare(), base.missing(), base.duplicate(), base.maxAttempts());

        assertThat(SqlTruncationDetector.describe(rule)).isEmpty();
    }

    @Test
    void plainTableRuleHasNoTruncation() {
        assertThat(SqlTruncationDetector.describe(TestRules.bill())).isEmpty();
    }

    private static RuleSpec ruleWithSql(String leftSql, String rightSql) {
        RuleSpec base = TestRules.bill();
        SideSpec left = new SideSpec("账单", null, leftSql, "order_no", FieldType.STRING, "order_no");
        SideSpec right = new SideSpec("结算", null, rightSql, "order_no", FieldType.STRING, "order_no");
        return new RuleSpec(base.code(), base.name(), base.description(), left, right,
                base.shard(), base.compare(), base.missing(), base.duplicate(), base.maxAttempts());
    }
}
