package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.chat.plan.ChatPlan;
import java.util.Map;

/**
 * 会话里往外推的一条事件。
 *
 * <p>为什么不是"一次请求一个 JSON"：对账的一次动作（尤其是跑一次）要几十秒，
 * 中间如果不给任何反馈，用户在浏览器上看到的就是一个转圈。分事件推出去，
 * 至少能立刻看到「识别成了什么动作」「正在调哪个工具」——这也顺带回答了
 * "它到底听懂没有"，比事后一句"已完成"有用得多。
 *
 * <p>事件类型是固定的五种 + 一种错误，前端按类型渲染，不解析正文。
 */
public record ChatEvent(String type, Map<String, Object> data) {

    public static ChatEvent of(String type, Map<String, Object> data) {
        return new ChatEvent(type, data == null ? Map.of() : Map.copyOf(data));
    }

    /**
     * "怎么理解的"：来源（词表/模型）、置信度、一句依据、以及需要澄清时的候选。
     *
     * <p>单独一条事件而不是塞进 intent：它要在正文之前到达，前端可以立刻显示
     * 「模型理解：RUN（0.92）· 用户说了规则名」。理解错了的时候，用户看到的第一句话
     * 就是错的依据，而不是等到整段答非所问才发现。
     */
    public static ChatEvent plan(ChatPlan plan) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("source", plan.source().name());
        data.put("confidence", String.format(java.util.Locale.ROOT, "%.2f", plan.confidence()));
        data.put("reason", plan.reason());
        data.put("intent", plan.call().intent().name());
        data.put("options", plan.options());
        // 决策式（P2）之后，真正被执行的可能不是 intent 而是这个工具名。
        // 轨迹里必须看得见"模型自己选了哪个工具"，否则回执对了也解释不清为什么。
        if (plan.isToolCall()) {
            data.put("tool", plan.toolName());
            data.put("args", plan.toolArgs());
        }
        if (plan.isReply()) {
            data.put("reply", true);
        }
        return of("plan", data);
    }

    /** 识别到的动作：{@code intent} 是枚举名，{@code label} 是中文动作名。 */
    public static ChatEvent intent(ChatIntent intent) {
        return of("intent", Map.of("intent", intent.name(), "label", intent.form()));
    }

    /** 调用了哪个工具、返回摘要。这条是"可审计"的抓手：出问题时先看它。 */
    public static ChatEvent tool(String name, String summary) {
        return of("tool", Map.of("name", name, "summary", summary == null ? "" : summary));
    }

    /** 正文分片。 */
    public static ChatEvent delta(String text) {
        return of("delta", Map.of("text", text));
    }

    /** 更新后的槽位。 */
    public static ChatEvent slot(ChatSlots slots) {
        return of("slot", Map.of(
                "ruleCode", slots.ruleCode() == null ? "" : slots.ruleCode(),
                "jobId", slots.jobId() == null ? "" : slots.jobId(),
                "diffId", slots.diffId() == null ? "" : slots.diffId(),
                "lastIntent", slots.lastIntent() == null ? "" : slots.lastIntent()));
    }

    public static ChatEvent done(long sessionId) {
        return of("done", Map.of("sessionId", sessionId));
    }

    /**
     * 这一轮花了多少：{@code turnTokens} 本轮、{@code tokensUsed} 本会话累计、{@code phase} 当前预算档。
     *
     * <p>为什么单独一条而不是塞进 {@code done}：预算的解释力在"为什么这一轮它忘了上一句"，
     * 那条线索必须在正文之前就能看见；塞进结尾事件，用户已经读完那段答非所问了。
     */
    public static ChatEvent budget(int turnTokens, int tokensUsed, String phase) {
        return of("budget", Map.of(
                "turnTokens", turnTokens,
                "tokensUsed", tokensUsed,
                "phase", phase == null ? "" : phase));
    }

    public static ChatEvent error(String message) {
        return of("error", Map.of("message", message == null ? "未知错误" : message));
    }
}
