package io.github.dolow.reconlens.llm;

/**
 * 调模型用途的常量集（指标与账本的 tag 值）。
 *
 * <p><b>必须是代码常量而不是自由字符串</b>：{@code purpose} 会变成 micrometer 的 tag，
 * 一旦有人把用户输入或文件名传进来，标签基数会爆炸、指标系统被打爆。
 * 这里集中定义，好处是 review 时一眼能看出"有没有人塞了动态值"。
 */
public final class LlmPurpose {

    /** 差异归因说明。 */
    public static final String EXPLAIN = "explain";
    /** 会话意图路由。 */
    public static final String CHAT_ROUTE = "chat-route";
    /** 会话结果叙述（把结构化结果说成人话）。 */
    public static final String CHAT_NARRATE = "chat-narrate";
    /** 规则/知识起草。 */
    public static final String DRAFT = "draft";
    /** 业界知识蒸馏与摄取。 */
    public static final String HARVEST = "harvest";
    /** 未标注用途（兼容老调用点）。 */
    public static final String OTHER = "other";

    private LlmPurpose() {
    }
}
