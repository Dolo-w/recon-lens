package io.github.dolow.reconlens.domain;

/** 差异严重级别，规则里可配，用于下游告警/工单分流。 */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH;

    public static Severity from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }
}
