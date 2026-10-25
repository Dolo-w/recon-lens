package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 把工具/引擎的输出"说成人话"。
 *
 * <p><b>为什么需要这一层</b>：引擎的返回是为机器与审计设计的——它有总数、偏移、分片明细、
 * 差异分类计数。这些东西一个不少地贴给用户，读的人要先在脑子里做一次归并，才知道"所以呢"。
 * 这一层只做一件事：<b>先给一句结论，再给最关键的 1-2 个数字</b>，明细原地保留在下面。
 *
 * <p><b>三条硬约束</b>（写进提示词，也写在这里，因为它们是这个类的定义）：
 * <ol>
 *   <li>只用材料里出现过的数字与事实，不新增、不推测原因——模型的总结不能变成第二个真相来源；</li>
 *   <li>失败就退回原文：这一层是"可选的读法"，不是"必过的环节"，没有 key 的部署行为完全不变；</li>
 *   <li>只对"读结果"的动作生效（列表、明细、归因、跑账），对话式的回复（反问、澄清、帮助）不重写。</li>
 * </ol>
 */
public final class ChatNarrator {

    /** 这些动作的输出是"一堆结构化事实"，值得先给一句结论。 */
    private static final Set<ChatIntent> APPLIES = EnumSet.of(
            ChatIntent.LIST_RULES, ChatIntent.LIST_JOBS, ChatIntent.LIST_DATA,
            ChatIntent.DESCRIBE_TABLE, ChatIntent.JOB_SUMMARY, ChatIntent.LIST_DIFFS,
            ChatIntent.EXPLAIN_JOB, ChatIntent.EXPLAIN_DIFF, ChatIntent.RUN,
            ChatIntent.RETRY, ChatIntent.BUILD_MODEL, ChatIntent.TRIAL_STATUS);

    /** 喂给模型的原文上限：再长就截断，总结的价值不随长度增长，但延迟和费用会。 */
    private static final int MAX_INPUT = 2400;
    private static final int MAX_TOKENS = 260;

    private static final String SYSTEM = """
            你是对账系统 recon-lens 的会话助手。把系统刚查到的结果改写成 2-3 句中文口语。
            规则：
            1. 先给结论，再给最关键的 1-2 个数字。不要罗列全部字段。
            2. 只用材料里出现的数字和事实，绝不新增、不推测原因、不承诺没做过的事。
            3. 不要 markdown、不要编号列表、不要"好的/当然/根据您的要求"这类客套。
            4. 材料本身是反问或提示时，照原意说一句就够，不要扩写。
            5. 不出现工具名（recon_ 开头）、接口路径（/api/…）或参数名；不要建议用户去调用任何东西。
            """;

    private final LlmChatClient client;
    private final boolean enabled;

    public ChatNarrator(LlmChatClient client, boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    /** 没配 key 或显式关掉时，这一层完全不介入。 */
    public boolean active() {
        return enabled && client != null && client.configured();
    }

    public String describe() {
        return active() ? "人话总结：开（" + client.describe() + "）" : "人话总结：关";
    }

    /**
     * 返回"总结 + 明细"；任何一步不成立都原样返回，调用方不必判断。
     *
     * @param intent      这一轮的动作（决定值不值得总结）
     * @param userMessage 用户原话，给模型一点"他到底在问什么"的背景
     * @param raw         系统原本要给用户的那段文字
     */
    public String decorate(ChatIntent intent, String userMessage, String raw) {
        raw = ChatSpeech.stripAgentTail(raw);
        if (!active() || raw == null || raw.isBlank() || intent == null || !APPLIES.contains(intent)) {
            return raw;
        }
        String clipped = raw.length() > MAX_INPUT ? raw.substring(0, MAX_INPUT) : raw;
        Optional<String> summary = client.completeText(SYSTEM, prompt(userMessage, clipped), MAX_TOKENS);
        if (summary.isEmpty()) {
            return raw;
        }
        String text = summary.get().trim();
        // 总结比原文还长，说明这一轮没什么可压缩的——那就别加噪音
        if (text.isEmpty() || text.length() >= raw.length()) {
            return raw;
        }
        return text + "\n\n—— 明细 ——\n" + raw;
    }

    /**
     * 写操作的"第一段"：把要说的事说成一句人话，请用户确认。
     *
     * <p>为什么要模型再说一遍，而不是拿模板拼：模板只会说「跑一次 BILL_VS_SETTLE」，
     * 而用户说的是「那个账单对账」——他要看到的是**自己的话被怎么理解了**，
     * 才判断得出对不对。模板做不到这层对照，模型可以做（它看得到用户原话与理解结果）。
     *
     * <p>失败就退回模板：确认这一步不能因为模型挂了而消失，它是安全边界的一部分。
     */
    public String confirm(ChatIntent intent, String summary, String userMessage) {
        String fallback = "我理解你要" + summary + "，确认执行吗？（回「确认」或「取消」）";
        if (!active()) {
            return fallback;
        }
        String prompt = "用户说：" + (userMessage == null ? "" : userMessage.trim())
                + "\n系统理解成：" + summary;
        Optional<String> text = client.completeText(CONFIRM_SYSTEM, prompt, 160);
        String trimmed = text.map(String::trim).orElse("");
        if (trimmed.isEmpty() || trimmed.length() > 160) {
            return fallback;
        }
        return trimmed + "（回「确认」或「取消」）";
    }

    private static final String CONFIRM_SYSTEM = """
            你是对账系统的会话助手。系统把用户刚才那句话理解成了一个"会改数据"的操作，
            现在要用一句中文向用户确认。要求：
            1. 用"我理解你要……，确认执行吗？"这种句式，一句话说完。
            2. 只说给定的事实，不要新增数字、不要预测结果、不要承诺"马上就好"。
            3. 不要 markdown、不要编号、不要客套。
            """;

    private static String prompt(String userMessage, String raw) {
        String question = userMessage == null || userMessage.isBlank() ? "（用户没有留下原话）" : userMessage.trim();
        return "用户问：" + question + "\n\n系统输出：\n" + raw;
    }
}
