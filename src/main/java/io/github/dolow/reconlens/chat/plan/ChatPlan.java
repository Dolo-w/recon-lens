package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntentRouter;
import java.util.List;

/**
 * 一句话的"理解结果"：要做哪个动作、带哪些参数、有多确定、为什么。
 *
 * <p><b>为什么要在 {@code Call} 外面再包一层</b>：{@code Call} 只回答"被理解成了什么"，
 * 而 P2 之后要回答的是"凭什么这么理解、有多确定、要不要反问"。把这三件事塞进 {@code Call}
 * 会让一个纯粹的执行计划承载解释性字段，表单入口（结构化参数，没有理解过程）也得被迫填。
 *
 * <p><b>{@code confidence} 不是概率，别拿它做决策</b>：模型给的数是"顺手写的一个数字"，
 * 没有校准过。决策只看 {@link Evidence} —— 它回答的是"这个判断能被证实吗"，
 * 而不是"这个判断有多大把握"。{@code confidence} 只用于展示与排序。
 */
public record ChatPlan(ChatIntentRouter.Call call, double confidence, String reason,
                       Source source, List<String> options, Evidence evidence) {

    /** 理解从哪来。写进 SSE 事件和审计，用户能看出这一句是词表认的还是模型认的。 */
    public enum Source {
        KEYWORD,
        LLM
    }

    /**
     * 这个判断有多少证据。四个档位对应四种处置，与置信度无关。
     *
     * <ul>
     *   <li>{@link #EXACT}：词表确定性命中 —— 直接执行；</li>
     *   <li>{@link #CONSISTENT}：模型给的参数都能在系统事实里对上 —— 可以执行；</li>
     *   <li>{@link #UNVERIFIED}：参数无法证实，或缺关键参数 —— 读操作可带回执执行，写操作必须问；</li>
     *   <li>{@link #CONFLICT}：参数与系统事实冲突（说了个不存在的规则/任务/表）—— 不许执行。</li>
     * </ul>
     */
    public enum Evidence {
        EXACT,
        CONSISTENT,
        UNVERIFIED,
        CONFLICT
    }

    public ChatPlan {
        options = options == null ? List.of() : List.copyOf(options);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason;
        evidence = evidence == null ? Evidence.UNVERIFIED : evidence;
    }

    public static ChatPlan keyword(ChatIntentRouter.Call call, double confidence, String reason) {
        return new ChatPlan(call, confidence, reason, Source.KEYWORD, List.of(), Evidence.EXACT);
    }

    public static ChatPlan model(ChatIntentRouter.Call call, double confidence, String reason,
                                 List<String> options, Evidence evidence) {
        return new ChatPlan(call, confidence, reason, Source.LLM, options, evidence);
    }

    /** 换一批候选（澄清时把"模型猜的"与"词表猜的"一起摆出来）。 */
    public ChatPlan withOptions(List<String> next) {
        return new ChatPlan(call, confidence, reason, source, next, evidence);
    }

    /** 确定到可以直接执行：只看证据，不看数字。 */
    public boolean confident() {
        return evidence == Evidence.EXACT || evidence == Evidence.CONSISTENT;
    }

    /** 是否需要先问一句再动手。 */
    public boolean needsClarification() {
        return evidence == Evidence.CONFLICT || (evidence == Evidence.UNVERIFIED && call.intent().isWrite());
    }

    /** 要不要把候选摆出来让用户挑。 */
    public boolean hasOptions() {
        return !options.isEmpty();
    }
}
