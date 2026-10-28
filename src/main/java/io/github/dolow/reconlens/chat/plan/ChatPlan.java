package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>P2 起多了三种出口</b>：{@code toolName} 非空表示"模型直接点了工具"（不再先判意图枚举），
 * {@code reply} 非空表示"模型这句是在说话/反问，不执行任何动作"，两者都为空才走老的意图分支。
 * {@code call.intent()} 仍然保留，供遥测、写操作回执与 P1 表单复用——工具调用会按工具名
 * 反推一个最接近的意图（{@code recon_run → RUN}），这样"这个动作要不要确认"只有一套判据。
 */
public record ChatPlan(ChatIntentRouter.Call call, double confidence, String reason,
                       Source source, List<String> options, Evidence evidence,
                       String toolName, Map<String, String> toolArgs, String reply) {

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
        toolName = toolName == null || toolName.isBlank() ? null : toolName.trim();
        Map<String, String> args = new LinkedHashMap<>();
        if (toolArgs != null) {
            toolArgs.forEach((key, value) -> {
                if (key != null && value != null) {
                    args.put(key, value);
                }
            });
        }
        toolArgs = Map.copyOf(args);
        reply = reply == null || reply.isBlank() ? null : reply;
    }

    public static ChatPlan keyword(ChatIntentRouter.Call call, double confidence, String reason) {
        return new ChatPlan(call, confidence, reason, Source.KEYWORD, List.of(), Evidence.EXACT,
                null, Map.of(), null);
    }

    public static ChatPlan model(ChatIntentRouter.Call call, double confidence, String reason,
                                 List<String> options, Evidence evidence) {
        return new ChatPlan(call, confidence, reason, Source.LLM, options, evidence,
                null, Map.of(), null);
    }

    /**
     * 模型直接选了工具（P2 的主路径）。
     *
     * <p>{@code args} 只收字符串：工具参数最后都会以 MCP 那种"原样 Map"的形式交给
     * {@code ToolSpec.invoke}，而模型给的 JSON 标量（数字/布尔）转成字符串不丢信息；
     * 真正的类型还原由工具自己的 {@code ToolArgs} 做，这里是"信封"不是"解析器"。
     */
    public static ChatPlan tool(String name, Map<String, String> args, double confidence) {
        return tool(new ChatIntentRouter.Call(ChatIntent.HELP, null, null, null, null, null, null),
                name, args, confidence, "模型直接选了一个动作", Evidence.CONSISTENT);
    }

    /** 带意图与证据的工具计划：意图只用于回执/遥测，执行看 {@link #toolName()}。 */
    public static ChatPlan tool(ChatIntentRouter.Call call, String name, Map<String, String> args,
                                double confidence, String reason, Evidence evidence) {
        return new ChatPlan(call, confidence, reason, Source.LLM, List.of(), evidence,
                name, args, null);
    }

    /**
     * 模型选择"先说话/反问"，不执行动作。
     *
     * <p>这不是"没听懂"：它是 P2 允许的一种正常出口——意图还没清楚时先把话说清楚，
     * 比硬猜一个动作然后跑错便宜得多。
     */
    public static ChatPlan reply(String text, double confidence) {
        return new ChatPlan(new ChatIntentRouter.Call(ChatIntent.HELP, null, null, null, null, null, null),
                confidence, "模型直接回复", Source.LLM, List.of(), Evidence.UNVERIFIED,
                null, Map.of(), text);
    }

    /** 换一批候选（澄清时把"模型猜的"与"词表猜的"一起摆出来）。 */
    public ChatPlan withOptions(List<String> next) {
        return new ChatPlan(call, confidence, reason, source, next, evidence, toolName, toolArgs, reply);
    }

    /** 确定到可以直接执行：只看证据，不看数字。 */
    public boolean confident() {
        return evidence == Evidence.EXACT || evidence == Evidence.CONSISTENT;
    }

    /** 这次动作带没带"指向某个具体对象"的参数（规则/任务/差异/表）。 */
    public boolean hasEntity() {
        ChatIntentRouter.Call c = call;
        return c.ruleCode() != null || c.jobId() != null || c.diffId() != null || c.table() != null;
    }

    /** 模型直接点了工具：执行走 {@code ToolRegistry}，不再按意图分支。 */
    public boolean isToolCall() {
        return toolName != null;
    }

    /** 模型只是说了句话（回答/反问），这一轮不执行任何动作。 */
    public boolean isReply() {
        return reply != null;
    }

    /**
     * 是否需要先问一句再动手。
     *
     * <p>原来的条件是「冲突 或（没核实 且 是写动作）」。但"没核实"从来不只发生在写动作上：
     * 让系统去解释一个不存在的任务号、查一个不存在的规则，同样是<b>拿用户的话当事实用</b>——
     * 而且读动作更隐蔽，它执行完只是报个错，不留任何痕迹，下次同样的话还会被当成真的。
     *
     * <p>所以判据改成「带实体参数就核实」：模型给出的 ruleCode/jobId/diffId/table 里只要有一个
     * 没法在系统事实里对上，就先问。不带实体的动作（看列表、看帮助、"为什么差这么多"）不核实，
     * 因为没有任何可编造的对象。
     */
    public boolean needsClarification() {
        if (evidence == Evidence.CONFLICT) {
            return true;
        }
        return evidence == Evidence.UNVERIFIED && hasEntity();
    }

    /** 要不要把候选摆出来让用户挑。 */
    public boolean hasOptions() {
        return !options.isEmpty();
    }
}
