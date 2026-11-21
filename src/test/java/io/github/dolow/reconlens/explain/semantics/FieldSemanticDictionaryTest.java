package io.github.dolow.reconlens.explain.semantics;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段语义字典（K3）：加载、查询、口径冲突判定，三条降级边界。
 *
 * <p>判定的核心立场：<b>两侧都登记了才比，只有一边知道不构成冲突证据</b> ——
 * "不知道"不能当"不一样"用（fail 保守）。
 */
class FieldSemanticDictionaryTest {

    @TempDir
    Path tempDir;

    private ReconProperties properties() {
        return new ReconProperties();
    }

    private static RuleSpec spec(String leftTable, String rightTable, String field) {
        String yaml = """
                code: T
                name: 测试
                left:
                  label: 左
                  table: %s
                  keyType: STRING
                  dimension: [order_no]
                right:
                  label: 右
                  table: %s
                  keyType: STRING
                  dimension: [order_no]
                compare:
                  - field: %s
                    type: DECIMAL
                    diffType: AMOUNT_MISMATCH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                  severity: HIGH
                """.formatted(leftTable, rightTable, field);
        return new RuleParser().parse(new java.io.StringReader(yaml), "测试规则");
    }

    @Test
    void builtinDictionaryLoadsDemoTables() {
        FieldSemanticDictionary dictionary = new FieldSemanticDictionary(properties());

        assertThat(dictionary.size()).isGreaterThanOrEqualTo(4);
        assertThat(dictionary.find("bill_left", "amount")).isPresent();
        assertThat(dictionary.find("bill_left", "amount").orElseThrow().caliber()).isEqualTo("不含税");
        assertThat(dictionary.find("BILL_LEFT", "AMOUNT")).as("表/列名大小写归一").isPresent();
        assertThat(dictionary.find("bill_left", "not_there")).isEmpty();
    }

    @Test
    void sameCaliberProducesNoConflict() {
        CaliberConflictChecker checker = new CaliberConflictChecker(new FieldSemanticDictionary(properties()));

        // bill_left.amount 与 bill_right.amount 都是 不含税/元 —— 演示规则不该误报
        assertThat(checker.conflicts(spec("bill_left", "bill_right", "amount"))).isEmpty();
    }

    @Test
    void caliberMismatchIsReportedAsWarn() throws Exception {
        Files.writeString(tempDir.resolve("lago.yml"), """
                tables:
                  lago_fees:
                    columns:
                      amount:
                        semantic: Lago 费用明细
                        caliber: 含税
                        unit: 元
                """);
        ReconProperties properties = properties();
        properties.getExplain().getSemantics().setDir(tempDir.toString());
        FieldSemanticDictionary dictionary = new FieldSemanticDictionary(properties);
        CaliberConflictChecker checker = new CaliberConflictChecker(dictionary);

        List<String> conflicts = checker.conflicts(spec("bill_left", "lago_fees", "amount"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).contains("口径冲突").contains("不含税").contains("含税");
    }

    @Test
    void unitMismatchIsReportedEvenWhenCaliberAgrees() throws Exception {
        Files.writeString(tempDir.resolve("unit.yml"), """
                tables:
                  partner_fees:
                    columns:
                      amount:
                        semantic: 合作方金额
                        caliber: 不含税
                        unit: 分
                """);
        ReconProperties properties = properties();
        properties.getExplain().getSemantics().setDir(tempDir.toString());
        CaliberConflictChecker checker = new CaliberConflictChecker(new FieldSemanticDictionary(properties));

        List<String> conflicts = checker.conflicts(spec("bill_left", "partner_fees", "amount"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).contains("单位冲突").contains("元").contains("分");
    }

    /** 只有单侧登记了口径 = 证据不足，不判冲突（"不知道"不能当"不一样"用）。 */
    @Test
    void oneSidedAnnotationIsNotEvidenceOfConflict() {
        CaliberConflictChecker checker = new CaliberConflictChecker(new FieldSemanticDictionary(properties()));

        // bill_left.status 有语义，bill_right.status 没登记 unit/caliber —— 不比对
        assertThat(checker.conflicts(spec("bill_left", "bill_right", "status"))).isEmpty();
        // 字典完全没覆盖的表/列：跳过，不报也不崩
        assertThat(checker.conflicts(spec("unknown_a", "unknown_b", "amount"))).isEmpty();
    }

    @Test
    void disabledDictionaryDegradesToSilent() {
        ReconProperties properties = properties();
        properties.getExplain().getSemantics().setEnabled(false);
        FieldSemanticDictionary dictionary = new FieldSemanticDictionary(properties);
        CaliberConflictChecker checker = new CaliberConflictChecker(dictionary);

        assertThat(dictionary.enabled()).isFalse();
        assertThat(dictionary.find("bill_left", "amount")).isEmpty();
        assertThat(checker.conflicts(spec("bill_left", "bill_right", "amount"))).isEmpty();
    }

    /** 外部覆盖内置：同 (table, column) 外部赢。 */
    @Test
    void externalOverridesBuiltinByTableAndColumn() throws Exception {
        Files.writeString(tempDir.resolve("override.yml"), """
                tables:
                  bill_left:
                    columns:
                      amount:
                        semantic: 客户侧修正口径
                        caliber: 含税
                        unit: 元
                """);
        ReconProperties properties = properties();
        properties.getExplain().getSemantics().setDir(tempDir.toString());
        FieldSemanticDictionary dictionary = new FieldSemanticDictionary(properties);

        assertThat(dictionary.find("bill_left", "amount").orElseThrow().caliber()).isEqualTo("含税");
    }

    /** 坏文件跳过，内置照常 —— 坏知识不能挡主链路。 */
    @Test
    void corruptExternalFileIsSkipped() throws Exception {
        Files.writeString(tempDir.resolve("broken.yml"), "tables: [ {{{{ 坏的");
        ReconProperties properties = properties();
        properties.getExplain().getSemantics().setDir(tempDir.toString());
        FieldSemanticDictionary dictionary = new FieldSemanticDictionary(properties());

        assertThat(dictionary.find("bill_left", "amount")).isPresent();
    }
}
