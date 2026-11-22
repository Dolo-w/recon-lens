package io.github.dolow.reconlens.domain;

/**
 * 一条告警（清单 P0-6）。
 *
 * <p><b>状态机刻意只有三态</b>：{@code OPEN}（还活着）→ {@code RESOLVED}（条件不再满足）。
 * 没有 ACKED：这套系统的告警是给监控/值班看的"事实陈述"，不是工单。
 * 需要认领流程时应该接外部系统，而不是在这里发明一套半成品工单。
 *
 * @param status OPEN / RESOLVED
 */
public record Alert(
        Long id,
        String alertKey,
        String severity,
        String message,
        double metricValue,
        double thresholdValue,
        String status,
        boolean notified,
        String createdAt,
        String resolvedAt) {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_WARNING = "WARNING";

    public boolean isOpen() {
        return OPEN.equals(status);
    }
}
