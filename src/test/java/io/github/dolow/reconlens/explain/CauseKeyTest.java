package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.Severity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 归因键的语义。核心只有一条：<b>键里不能有具体的业务值</b>。
 *
 * <p>评审 R3 已经把 {@code cause} 从键里踢出去了（拿答案查索引，命中率恒 0）。
 * 但同一个错误在下一层还藏着一个变体 —— 把金额 / 业务键 / 时间戳塞进
 * {@code feature_signature}，结构上一模一样：每条差异一个键，知识库只进不出。
 * 这个错误在代码里看不出来，要跑一周数据才暴露，所以用测试钉死。
 */
class CauseKeyTest {

    private static DiffRecord mismatch(String bizKey, String left, String right, String ratio) {
        return DiffRecord.of(1L, 0, "AMOUNT_MISMATCH", Severity.HIGH, bizKey, "amount",
                left, right, new BigDecimal("10"), ratio == null ? null : new BigDecimal(ratio));
    }

    @Test
    void sameShapeDifferentValuesShareOneKey() {
        CauseKey a = CauseKey.of("BILL_VS_SETTLE", mismatch("ORD-1", "100.00", "100.50", "0.005"));
        CauseKey b = CauseKey.of("BILL_VS_SETTLE", mismatch("ORD-2", "888.00", "889.10", "0.008"));

        assertThat(a.digest())
                .as("只是金额不同，业务原因大概率相同 —— 必须复用同一条归因")
                .isEqualTo(b.digest());
    }

    @Test
    void featureSignatureNeverContainsTheActualValues() {
        CauseKey key = CauseKey.of("BILL_VS_SETTLE", mismatch("ORD-2026-0001", "123456.78", "123457.00", "0.001"));

        assertThat(key.featureSignature())
                .doesNotContain("ORD-2026-0001")
                .doesNotContain("123456.78")
                .doesNotContain("123457");
        assertThat(key.describe())
                .doesNotContain("123456.78");
    }

    @Test
    void differentMagnitudeBandsGetDifferentKeys() {
        CauseKey tiny = CauseKey.of("R", mismatch("ORD-1", "100", "100.50", "0.005"));
        CauseKey huge = CauseKey.of("R", mismatch("ORD-1", "100", "180", "0.8"));

        assertThat(tiny.digest())
                .as("0.5%% 的偏差与 80%% 的偏差不是同一种病，混在一起会让归因含糊")
                .isNotEqualTo(huge.digest());
    }

    @Test
    void recordLevelDiffsNormaliseMissingFieldInsteadOfLiteralNull() {
        DiffRecord left = DiffRecord.of(1L, 0, "MISSING_LEFT", Severity.HIGH, "ORD-1", null, null, "100", null, null);
        DiffRecord right = DiffRecord.of(1L, 0, "MISSING_RIGHT", Severity.HIGH, "ORD-2", null, "100", null, null, null);

        assertThat(CauseKey.of("R", left).fieldName()).isEmpty();
        assertThat(CauseKey.of("R", left).digest())
                .as("同一个规则下 MISSING_LEFT 与 MISSING_RIGHT 方向不同，键必须不同")
                .isNotEqualTo(CauseKey.of("R", right).digest());
    }

    @Test
    void ruleCodeIsPartOfTheKey() {
        DiffRecord diff = mismatch("ORD-1", "100", "100.50", "0.005");

        assertThat(CauseKey.of("RULE_A", diff).digest())
                .isNotEqualTo(CauseKey.of("RULE_B", diff).digest());
    }

    @Test
    void codeAndFieldAreCaseAndSpaceInsensitive() {
        DiffRecord diff = mismatch("ORD-1", "100", "100.50", "0.005");

        assertThat(CauseKey.of("rule_a ", diff).digest())
                .as("配置里多一个空格不该变成另一条归因")
                .isEqualTo(CauseKey.of("RULE_A", diff).digest());
    }

    @Test
    void digestIsLinearlyBoundedForStorage() {
        String longCode = "X".repeat(200);
        CauseKey key = CauseKey.of(longCode, mismatch("ORD-1", "1", "2", "0.5"));

        assertThat(key.ruleCode()).hasSize(64);
        assertThat(key.digest()).hasSize(64).matches("[0-9a-f]{64}");
    }
}
