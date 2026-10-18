package io.github.dolow.reconlens.agent;

import java.util.Set;

/**
 * 一次会话的目标（设计 §4.1）。
 *
 * <p>它有三个来源，区别只在 {@link #trigger()}：定时巡检、自然语言提问、结构化 API。
 * 三者的<b>输入形态差别很大但下游完全一样</b>，所以先收敛成这一个对象 ——
 * 编排层不该知道"这句话是人问的还是巡检问的"，否则每加一个入口就要改一遍状态机。
 *
 * @param rawInput   原始输入（自然语言 / 巡检摘要 / API 里的描述）
 * @param ruleCode   可空：空则由规划层决定用哪条或起草新的
 * @param batchId    可空：指定批次号跑对账
 * @param timeWindow 可空：关注的时间窗（自然语言里解析不出来就留空，别硬猜）
 * @param focusTypes 关注的 diffType；空集表示关注全部
 * @param trigger    谁发起的
 * @param intent     这次会话想达成什么：<b>只起草</b>，还是<b>真的跑一次对账</b>
 */
public record AgentGoal(String rawInput, String ruleCode, String batchId, String timeWindow,
                        Set<String> focusTypes, GoalTrigger trigger, Intent intent) {

    public enum GoalTrigger {
        SCHEDULED, NL_QUESTION, API
    }

    /**
     * 会话意图。<b>默认必须是 {@link #DRAFT_RULE}</b>：忘了传的时候，宁可少跑一次对账，
     * 也不能让"问一句需求"产生一条生产任务。
     *
     * <p>为什么这件事要单独建模：{@code recon_draft_rule} 的入参里有可选 {@code ruleCode}
     * （改现有规则时必须传）。如果规划层只按"这个 code 存不存在"分流，传一个已存在的 code
     * 就会走确定性路径并 {@code engine.run()} —— 一个叫"起草"的工具顺手跑了 PROD 对账，
     * 写 {@code recon_job}/{@code recon_diff}，还会进巡检环比基线。
     */
    public enum Intent {
        /** 只产出草稿：不执行任何对账任务。 */
        DRAFT_RULE,
        /** 显式要求跑一次对账（会产生 PROD 任务，必须有独立入口）。 */
        RUN_RECONCILIATION
    }

    public AgentGoal {
        focusTypes = focusTypes == null ? Set.of() : Set.copyOf(focusTypes);
        trigger = trigger == null ? GoalTrigger.API : trigger;
        intent = intent == null ? Intent.DRAFT_RULE : intent;
        if ((rawInput == null || rawInput.isBlank()) && (ruleCode == null || ruleCode.isBlank())) {
            throw new IllegalArgumentException("目标和规则编码不能同时为空：否则这次会话没有任何可执行的内容");
        }
    }

    /** 兼容旧调用：<b>默认只起草</b>（不会跑对账）。 */
    public AgentGoal(String rawInput, String ruleCode, String batchId, String timeWindow,
                     Set<String> focusTypes, GoalTrigger trigger) {
        this(rawInput, ruleCode, batchId, timeWindow, focusTypes, trigger, Intent.DRAFT_RULE);
    }

    /** API / MCP 入口的常用形态：给一句需求（或一个规则编码）。 */
    public static AgentGoal of(String rawInput, String ruleCode, String batchId) {
        return new AgentGoal(rawInput, ruleCode, batchId, null, Set.of(), GoalTrigger.API,
                Intent.DRAFT_RULE);
    }

    public boolean wantsReconciliation() {
        return intent == Intent.RUN_RECONCILIATION;
    }

    /** 落 {@code recon_agent_run.goal} 的文本：把结构化字段摊平成一行，便于按关键字搜。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (rawInput != null && !rawInput.isBlank()) {
            sb.append(rawInput.trim());
        }
        if (ruleCode != null && !ruleCode.isBlank()) {
            sb.append(sb.isEmpty() ? "" : "；").append("规则=").append(ruleCode);
        }
        if (batchId != null && !batchId.isBlank()) {
            sb.append(sb.isEmpty() ? "" : "；").append("批次=").append(batchId);
        }
        if (!focusTypes.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : "；").append("关注=").append(String.join("/", focusTypes));
        }
        if (wantsReconciliation()) {
            sb.append("；意图=跑一次对账");
        }
        return sb.toString();
    }
}
