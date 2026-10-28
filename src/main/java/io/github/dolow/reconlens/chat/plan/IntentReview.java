package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntent;

/**
 * 意图的"事实复核"层：在真正执行之前，用一句确定性检查挡掉最贵的一类误判。
 *
 * <p>为什么要有这一层，而不是把规则写进词表或提示词：词表和模型都只看到"这句话像什么"，
 * 看不到"这句话跟上一轮的结论对不上"。{@code 「不是三个维度吗」} 在它们眼里就是一个 DEFINE_RULE
 * 或 DESCRIBE_TABLE；而人一看就知道他在**质疑**，不是在**下指令**。
 *
 * <p>这一层不新增判断能力，只做一件事：<b>把"这是问我、还是要我做"这一步从模型手里拿回来</b>。
 * 判定全是字符串规则，零成本、可解释、可测。
 */
public final class IntentReview {

    private IntentReview() {
    }

    /**
     * 复核这句要不要先问一句再执行。
     *
     * <p>返回非 {@code null} 表示"别执行，先把这句反问回去"。
     *
     * <p>只拦一种句式：带「不是/难道」且以问号或「吗/呢」结尾的反问。
     * 不放宽是因为 {@code 「为什么差这么多」}、{@code 「能批吗」} 都是正常指令，
     * 放宽会把它们也拦下来——那等于把可用性换成"更谨慎"。
     */
    public static String question(String message, ChatIntent intent) {
        if (message == null || intent == null) {
            return null;
        }
        String text = message.strip();
        if (text.isEmpty()) {
            return null;
        }
        boolean rhetorical = (text.contains("不是") || text.contains("难道"))
                && (text.endsWith("吗") || text.endsWith("呢") || text.endsWith("？") || text.endsWith("?"));
        if (!rhetorical) {
            return null;
        }
        return "你这句是在问「" + brief(text) + "」，不是要我执行「" + intent.form() + "」。\n"
                + "我先不动手：你想问的是什么，直接说；要执行就说清楚对象，比如「按 bill_left / bill_right 起草」。";
    }

    private static String brief(String text) {
        String t = text.replaceAll("\\s+", " ");
        return t.length() <= 30 ? t : t.substring(0, 30) + "…";
    }
}
