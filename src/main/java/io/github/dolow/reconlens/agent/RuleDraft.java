package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.dsl.RuleSpec;

import java.util.List;
import java.util.Map;

/**
 * 一次起草的结果：<b>要么是一份过了闸门 1 的规则体，要么是一句"为什么没成"</b>。
 *
 * <p>它刻意不是一个 {@code Optional<RuleSpec>}。因为"没成"有三种完全不同的含义，
 * 混成一个 {@code empty} 之后，审核人看到的永远只有"生成失败"：
 * <ul>
 *   <li>{@link #drafted()} false + {@link #blockedReason()} "LLM 不可用" → 环境问题，重试可能好；</li>
 *   <li>{@link #drafted()} false + 违规列表非空 → <b>模型写不出合规的规则</b>，要靠人；</li>
 *   <li>构造器直接拒绝的非法组合 → 见下面的紧凑构造器断言。</li>
 * </ul>
 *
 * <p><b>{@code assumptions} 是这份草稿的一部分，不是附注</b>（设计 §4.2）：
 * 模型一定会补全它不知道的东西（"假设 settle_time 就是入账时间"），
 * 不把假设摊开给审核人看，他就只能对着一坨 YAML 猜模型想说什么 ——
 * 而审核的全部价值就在于"发现一个不该被假设的地方"。
 */
public record RuleDraft(String code,
                        RuleSpec spec,
                        Map<String, Object> ruleBody,
                        String reasoning,
                        List<String> assumptions,
                        List<Round> rounds,
                        String blockedReason) {

    /**
     * 一轮的结果。
     *
     * @param status     {@code PASS} / {@code INVALID_JSON} / {@code INVALID_RULE} / {@code SAFETY_VIOLATION} / {@code LLM_UNAVAILABLE}
     * @param violations 闸门 1 的违规列表（{@code SAFETY_VIOLATION} 那一轮非空）；其它轮为空
     */
    public record Round(int index, String status, List<String> violations, String detail) {

        public static Round pass(int index) {
            return new Round(index, "PASS", List.of(), null);
        }
    }

    public RuleDraft {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
        // 成稿必须有 spec + ruleBody；未成稿必须有 blockedReason。
        // 让这个约束在构造处成立，而不是靠调用方的 if —— 否则"成稿但 spec 为 null"会在
        // 落库那一刻变成 NPE，而那时已经消耗了一整轮 LLM 预算。
        if (spec != null && ruleBody == null) {
            throw new IllegalArgumentException("成稿必须带 ruleBody：落库要用它做快照");
        }
        if (spec == null && (blockedReason == null || blockedReason.isBlank())) {
            throw new IllegalArgumentException("未成稿必须写清 blockedReason：否则审核人只看到一句\"生成失败\"");
        }
    }

    public boolean drafted() {
        return spec != null;
    }

    /** 最后一轮闸门 1 的违规（成稿时为空）。转人工时这就是给审核人的"模型卡在哪"。 */
    public List<String> lastViolations() {
        for (int i = rounds.size() - 1; i >= 0; i--) {
            if (!rounds.get(i).violations().isEmpty()) {
                return rounds.get(i).violations();
            }
        }
        return List.of();
    }

    public static RuleDraft rejected(String reason, List<Round> rounds) {
        return new RuleDraft(null, null, null, null, List.of(), rounds, reason);
    }
}
