package io.github.dolow.reconlens.connector;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * canonical 金额归一（连接器层第一块基石）：账单导出里的脏金额写法逐条钉死。
 * 每条用例对应 {@link AmountNormalizer} 类注释里的一条判定规则 —— 规则改了测试必须跟着改，
 * 这是故意的：分隔符裁决是连接器最容易"悄悄判错"的地方。
 */
class AmountNormalizerTest {

    private static BigDecimal val(String raw) {
        AmountNormalizer.ParsedAmount p = AmountNormalizer.parse(raw);
        return p == null ? null : p.value();
    }

    private static BigDecimal val(String raw, boolean euGroups) {
        AmountNormalizer.ParsedAmount p = AmountNormalizer.parse(raw, euGroups);
        return p == null ? null : p.value();
    }

    // ---------------- 纯数字：归一器不该碰 ----------------

    @Test
    void 纯数字走BigDecimal原路_不脏() {
        assertThat(AmountNormalizer.isDirtyAmount("1234.50")).isFalse();
        assertThat(AmountNormalizer.isDirtyAmount("-5")).isFalse();
        assertThat(val("1234.50")).isEqualByComparingTo("1234.50");
    }

    // ---------------- 货币符号与 ISO 码 ----------------

    @Test
    void 人民币符号与千分位() {
        assertThat(val("¥1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(val("￥1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(val("¥ -5.2")).isEqualByComparingTo("-5.2");
    }

    @Test
    void 美元前后缀与币种提示() {
        AmountNormalizer.ParsedAmount p = AmountNormalizer.parse("$1,234.50");
        assertThat(p.value()).isEqualByComparingTo("1234.50");
        assertThat(p.currency()).isNull(); // 符号不带币种语义，只有 ISO 码才算

        AmountNormalizer.ParsedAmount suffix = AmountNormalizer.parse("12.3 USD");
        assertThat(suffix.value()).isEqualByComparingTo("12.3");
        assertThat(suffix.currency()).isEqualTo("USD");

        AmountNormalizer.ParsedAmount lead = AmountNormalizer.parse("CNY 0.01");
        assertThat(lead.value()).isEqualByComparingTo("0.01");
        assertThat(lead.currency()).isEqualTo("CNY");
    }

    @Test
    void 其他符号_欧元英镑韩元() {
        assertThat(val("€1.234,50")).isEqualByComparingTo("1234.50");
        assertThat(val("£999")).isEqualByComparingTo("999");
        assertThat(val("₩120000")).isEqualByComparingTo("120000");
    }

    // ---------------- 会计负数与显式负号 ----------------

    @Test
    void 会计负数_括号() {
        assertThat(val("(1,234.50)")).isEqualByComparingTo("-1234.50");
        assertThat(val("($99.9)")).isEqualByComparingTo("-99.9");
        AmountNormalizer.ParsedAmount p = AmountNormalizer.parse("(12.3 USD)");
        assertThat(p.value()).isEqualByComparingTo("-12.3");
        assertThat(p.currency()).isEqualTo("USD");
    }

    @Test
    void 显式负号加千分位() {
        assertThat(val("-1,234.50")).isEqualByComparingTo("-1234.50");
        assertThat(val("¥-1,234.50")).isEqualByComparingTo("-1234.50");
    }

    // ---------------- 分隔符判定 ----------------

    @Test
    void 混用分隔符_靠右的是小数点() {
        assertThat(val("1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(val("1.234.567,89")).isEqualByComparingTo("1234567.89");
        assertThat(val("1,234,567.89")).isEqualByComparingTo("1234567.89");
        // 分组不合法直接拒
        assertThat(val("1,23.50")).isNull();
        assertThat(val("1.23.4,50")).isNull();
    }

    @Test
    void 同一分隔符出现多次_必是分组() {
        assertThat(val("1,234,567")).isEqualByComparingTo("1234567");
        assertThat(val("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(val("1,23,456")).isNull(); // 分组不齐 → 不是金额
    }

    @Test
    void 单分隔符跟1到2位数字_是小数() {
        assertThat(val("12,5")).isEqualByComparingTo("12.5");
        assertThat(val("12.5")).isEqualByComparingTo("12.5");
        assertThat(val("1,23")).isEqualByComparingTo("1.23");
    }

    @Test
    void 唯一歧义_美式默认() {
        // 无列级证据：逗号+3位尾数=分组；点+3位尾数=小数
        assertThat(val("1,234")).isEqualByComparingTo("1234");
        assertThat(val("1.234")).isEqualByComparingTo("1.234");
        assertThat(val("12,345")).isEqualByComparingTo("12345");
    }

    @Test
    void 唯一歧义_欧式证据反转() {
        assertThat(val("1.234", true)).isEqualByComparingTo("1234");
        assertThat(val("1,234", true)).isEqualByComparingTo("1.234");
    }

    @Test
    void 歧义时头必须像分组() {
        assertThat(val(",234")).isNull();   // 分隔符前没有数字
        assertThat(val("1234,234")).isNull(); // 头 4 位，不是分组写法
    }

    @Test
    void 长小数_汇率单价常见() {
        assertThat(val("1.234567")).isEqualByComparingTo("1.234567");
        assertThat(val("1,234567", true)).isEqualByComparingTo("1.234567"); // 欧式长逗号小数
        assertThat(val("1,234567")).isNull();     // 美式下逗号不能是长小数
        assertThat(val("1.234567", true)).isNull(); // 欧式下点只能是 3 位分组
        assertThat(val("1.23456789")).isNull();   // 小数超 6 位，超 DECIMAL(24,6) → 拒
    }

    // ---------------- 空格分组（法式） ----------------

    @Test
    void 空格分组() {
        assertThat(val("1 234,56")).isEqualByComparingTo("1234.56");
        assertThat(val("1\u00A0234.56")).isEqualByComparingTo("1234.56");
        assertThat(val("1\u202F234,56")).isEqualByComparingTo("1234.56");
    }

    // ---------------- 拒绝项 ----------------

    @Test
    void 不是金额的东西一律拒绝() {
        assertThat(val("")).isNull();
        assertThat(val("   ")).isNull();
        assertThat(val("abc")).isNull();
        assertThat(val("¥")).isNull();
        assertThat(val("N/A")).isNull();
        assertThat(val("1,2.3,4")).isNull();
        assertThat(val("12345678901234567890")).isNull(); // 总位超限
        assertThat(AmountNormalizer.parse(null)).isNull();
    }

    // ---------------- 列级欧式证据 ----------------

    @Test
    void 欧式证据探测() {
        assertThat(AmountNormalizer.hasEuEvidence(List.of("1.234,56", "2.345,67"))).isTrue();
        assertThat(AmountNormalizer.hasEuEvidence(List.of("(1.234,56)", "999"))).isTrue();
        assertThat(AmountNormalizer.hasEuEvidence(List.of("1,234.56", "999"))).isFalse();
        assertThat(AmountNormalizer.hasEuEvidence(List.of("", "12,5"))).isFalse();
        assertThat(AmountNormalizer.hasEuEvidence(List.of())).isFalse();
    }
}
