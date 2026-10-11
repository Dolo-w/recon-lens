package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.dsl.FieldRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 容差与类型比较：这里错了，对账结果就是错的。 */
class ValueComparatorTest {

    private static FieldRule decimal(BigDecimal tolerance, BigDecimal tolerancePercent) {
        return new FieldRule("amount", FieldType.DECIMAL, false, tolerance, tolerancePercent,
                "AMOUNT_MISMATCH", Severity.HIGH);
    }

    @Test
    void absoluteToleranceAbsorbsCentLevelNoise() {
        CompareResult result = ValueComparator.compare(decimal(new BigDecimal("0.01"), null),
                new BigDecimal("100.00"), new BigDecimal("100.01"));

        assertThat(result.different()).isFalse();
    }

    @Test
    void beyondToleranceIsADifferenceWithDeltaAndRatio() {
        CompareResult result = ValueComparator.compare(decimal(new BigDecimal("0.01"), null),
                new BigDecimal("3000"), new BigDecimal("2999"));

        assertThat(result.different()).isTrue();
        assertThat(result.delta()).isEqualByComparingTo("1");
        assertThat(result.ratio()).isEqualByComparingTo("0.000333");
    }

    @Test
    void relativeToleranceHandlesSmallBaseAmounts() {
        CompareResult result = ValueComparator.compare(decimal(null, new BigDecimal("0.005")),
                new BigDecimal("0.02"), new BigDecimal("0.02005"));

        assertThat(result.different()).isFalse();
        assertThat(ValueComparator.compare(decimal(null, new BigDecimal("0.005")),
                new BigDecimal("0.02"), new BigDecimal("0.0202")).different()).isTrue();
    }

    @Test
    void withoutToleranceAnyCentCounts() {
        assertThat(ValueComparator.compare(decimal(null, null), "10.00", "10.01").different()).isTrue();
        assertThat(ValueComparator.compare(decimal(null, null), "10.00", "10.00").different()).isFalse();
    }

    @Test
    void zeroBaseAnyNonZeroDeltaIsADifference() {
        CompareResult result = ValueComparator.compare(decimal(new BigDecimal("0.01"), null),
                BigDecimal.ZERO, new BigDecimal("0.02"));

        assertThat(result.different()).isTrue();
    }

    @Test
    void nullOnOneSideIsAlwaysADifference() {
        assertThat(ValueComparator.compare(decimal(new BigDecimal("100"), null), null, "1").different()).isTrue();
        assertThat(ValueComparator.compare(decimal(new BigDecimal("100"), null), "1", null).different()).isTrue();
        assertThat(ValueComparator.compare(decimal(new BigDecimal("100"), null), null, null).different()).isFalse();
    }

    @Test
    void stringsRespectIgnoreCaseAndTrim() {
        FieldRule strict = new FieldRule("status", FieldType.STRING, false, null, null, "STATUS_MISMATCH", Severity.MEDIUM);
        FieldRule loose = new FieldRule("status", FieldType.STRING, true, null, null, "STATUS_MISMATCH", Severity.MEDIUM);

        assertThat(ValueComparator.compare(strict, "PAID", "paid").different()).isTrue();
        assertThat(ValueComparator.compare(loose, "PAID", " paid ").different()).isFalse();
        assertThat(ValueComparator.compare(loose, "PAID", "UNPAID").different()).isTrue();
    }

    @Test
    void keyComparisonFollowsDeclaredType() {
        // 业务键比较必须与 SQL 的 ORDER BY 完全一致，否则归并会错位。
        // 字符串按字典序：等宽的零填充键（ORD20260001…ORD20260020）字典序与数值序一致，这是推荐用法；
        // 一旦宽度不齐（如 ORD20260010 vs ORD20260002），字典序就和数值序分叉 —— 所以业务键要定宽。
        assertThat(ValueComparator.compareKeys(FieldType.STRING, "ORD20260001", "ORD20260002")).isNegative();
        assertThat(ValueComparator.compareKeys(FieldType.STRING, "ORD20260010", "ORD20260002")).isPositive();
        assertThat(ValueComparator.compareKeys(FieldType.LONG, 2L, 10L)).isNegative();
        assertThat(ValueComparator.compareKeys(FieldType.DECIMAL, "10.00", "10.0")).isZero();
    }

    @Test
    void parsesThousandsSeparatedStrings() {
        assertThat(ValueComparator.toDecimal("1,234.56")).isEqualByComparingTo("1234.56");
    }
}
