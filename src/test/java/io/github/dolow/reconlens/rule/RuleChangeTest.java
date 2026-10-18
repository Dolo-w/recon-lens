package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "这次改动算不算触及安全语义"必须由代码判定（第四轮 9.1-③）。
 *
 * <p>如果把这个判断交给提草稿的人自述，"跳过试算"开关立刻退化成"写个理由就能绕过闸门 2"。
 * 所以这里钉死：只有 {@code name} / {@code description} 的变化才是非语义变更。
 */
class RuleChangeTest {

    private static final RuleParser PARSER = new RuleParser();

    private static RuleSpec spec(String name, String description, String tolerance, String statusSeverity) {
        String yaml = """
                code: CHANGE_TEST
                name: %s
                description: %s
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: %s, diffType: AMOUNT_MISMATCH, severity: HIGH}
                  - {field: status, type: STRING, ignoreCase: true, diffType: STATUS_MISMATCH, severity: %s}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(name, description, tolerance, statusSeverity);
        return PARSER.parse(new StringReader(yaml), "单测");
    }

    @Test
    void 只改名字与描述不算语义变更() {
        RuleSpec baseline = spec("旧名字", "旧描述", "0.01", "MEDIUM");
        RuleSpec candidate = spec("新名字", "新描述", "0.01", "MEDIUM");

        RuleChange change = RuleChange.between(baseline, candidate);

        assertThat(change.semantic()).isFalse();
        assertThat(change.skippable()).isTrue();
        assertThat(change.changedPaths()).isEmpty();
    }

    @Test
    void 容差变化是语义变更_且能指出具体位置() {
        RuleChange change = RuleChange.between(
                spec("n", "d", "0.01", "MEDIUM"),
                spec("n", "d", "0.5", "MEDIUM"));

        assertThat(change.semantic()).isTrue();
        assertThat(change.changedPaths()).contains("compare.AMOUNT.tolerance");
    }

    @Test
    void 严重级变化是语义变更() {
        RuleChange change = RuleChange.between(
                spec("n", "d", "0.01", "HIGH"),
                spec("n", "d", "0.01", "LOW"));

        assertThat(change.semantic()).isTrue();
        assertThat(change.changedPaths()).contains("compare.STATUS.severity");
    }

    @Test
    void 容差0点10与0点1视为同一个值() {
        RuleChange change = RuleChange.between(
                spec("n", "d", "0.1", "MEDIUM"),
                spec("n", "d", "0.10", "MEDIUM"));

        assertThat(change.semantic()).isFalse();
    }

    @Test
    void 新规则没有基线时不算放大_但也不是可跳过() {
        RuleChange change = RuleChange.between(null, spec("n", "d", "0.01", "MEDIUM"));

        assertThat(change.semantic()).isTrue();
        assertThat(change.skippable()).isFalse();
    }
}
