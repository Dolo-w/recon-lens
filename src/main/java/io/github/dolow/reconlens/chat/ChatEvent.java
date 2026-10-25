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
        return of("plan", Map.of(
                "source", plan.source().name(),
                "confidence", String.format(java.util.Locale.ROOT, "%.2f", plan.confidence()),
                "reason", plan.reason(),
                "intent", plan.call().intent().name(),
                "options", plan.options()));
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

    public static ChatEvent error(String message) {
        return of("error", Map.of("message", message == null ? "未知错误" : message));
    }
}
