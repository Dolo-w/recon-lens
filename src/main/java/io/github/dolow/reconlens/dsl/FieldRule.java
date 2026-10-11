package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.engine.FieldType;

import java.math.BigDecimal;

/**
 * 一个比对字段的规则。容差有两种表达方式：
 * <ul>
 *   <li>tolerance：绝对容差（金额分位抖动）</li>
 *   <li>tolerancePercent：相对容差（汇率、手续费抖动）</li>
 * </ul>
 * 两者都配时，任一超限即判为差异。
 */
public record FieldRule(String field,
                        FieldType type,
                        boolean ignoreCase,
                        BigDecimal tolerance,
                        BigDecimal tolerancePercent,
                        String diffType,
                        Severity severity) {

    public boolean hasTolerance() {
        return tolerance != null || tolerancePercent != null;
    }
}
