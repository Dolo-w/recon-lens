package io.github.dolow.reconlens.recall;

import java.time.Duration;
import java.time.Instant;

/**
 * 一条案例记忆：某类差异最后是怎么处理的、谁定的、有多可信。
 *
 * <p>与 {@code recon_diff_kb} 的分工要说清楚：那张表是"cause_key → 原因"，
 * 是结论级、键匹配、一次性写死；这张表是"具体这条差异 → 处理动作"，
 * 是案例级、会随时间衰减、同一条可以有多个案例。
 *
 * <p>{@code confidence} 只有两个合法来源：人确认（0.9）、模型自述（0.5）。
 * 中间值不是"更精确"，是"没人负责"——所以这里不做插值。
 */
public record CaseMemory(
        long id,
        String ruleCode,
        String diffType,
        String causeKey,
        String resolution,
        String source,
        double confidence,
        String operator,
        Long diffId,
        Long jobId,
        String datasetVersion,
        int hits,
        Instant lastHitAt,
        Instant createdAt) {

    public static final double HUMAN_CONFIDENCE = 0.9;
    public static final double LLM_CONFIDENCE = 0.5;

    /** 用于排序的"还有多新鲜"：半年衰减一半，命中次数只做小幅加成。 */
    public double freshness(Instant now) {
        Instant anchor = lastHitAt != null ? lastHitAt : createdAt;
        long days = Math.max(0, Duration.between(anchor, now).toDays());
        double decay = Math.pow(0.5, days / 180.0);
        // 封顶 1.0：命中次数多了以后 log 项会把分数推过 1，而调用方（REST / 前端）把它当
        // "可信度"展示——一个大于 1 的可信度会让人怀疑整套打分是不是拍脑袋定的。
        return Math.min(1.0, confidence * decay + 0.05 * Math.log1p(hits));
    }

    public boolean byHuman() {
        return "HUMAN".equals(source);
    }
}
