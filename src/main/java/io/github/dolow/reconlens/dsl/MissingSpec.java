package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.Severity;

/** 单边缺失的规则：左侧缺失 / 右侧缺失分别用哪个 diffType 表达。 */
public record MissingSpec(String leftMissingDiffType,
                          String rightMissingDiffType,
                          Severity severity) {
}
