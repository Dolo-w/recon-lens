package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.Severity;

/** 同一业务键在一侧出现多条时的规则。 */
public record DuplicateSpec(String diffType, Severity severity) {
}
