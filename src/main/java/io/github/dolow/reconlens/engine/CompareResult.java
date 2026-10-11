package io.github.dolow.reconlens.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** 字段比较结果：是否超容差 + 绝对差 + 相对差。 */
public record CompareResult(boolean different, BigDecimal delta, BigDecimal ratio) {

    public static CompareResult same() {
        return new CompareResult(false, null, null);
    }
}
