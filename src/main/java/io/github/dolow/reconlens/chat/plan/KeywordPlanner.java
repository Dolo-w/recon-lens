package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * P1 的词表路由，包成规划器。
 *
 * <p><b>保留它做第一层</b>：日常问法（"跑一下 X""看差异""有哪些规则"）本来就落在词表里，
 * 走这条路径是 0 延迟 0 成本，而且**没有 key 的部署也只有这一层**。
 * 模型只在它没命中或者并列时介入——不是"更准"的问题，是"不能让它成为依赖"的问题。
 *
 * <p>置信度怎么给的：命中具体动作 0.78（词表命中是确定性匹配，但不是语义理解，不许给 0.95）；
 * 落到 HELP 0.25（说不清就是说不清，硬凑一个 0.6 会让上层不敢用模型兜底）。
 */
public final class KeywordPlanner implements ChatPlanner {

    /** 命中具体动作。不是语义理解，所以不给 0.95。 */
    static final double HIT = 0.78;
    /**
     * 只认出"话题名词"时的置信度。
     *
     * <p><b>必须低于 {@code CompositePlanner} 的接受阈值</b>（默认 0.7）：否则
     * "出现'规则'两个字"和"跑一下 BILL_X"一样自信，模型连复核的机会都没有 ——
     * 这正是「起草个规则」被答成「当前库里有哪几条规则」的直接原因。
     * 降权不是"更准"，是**把判断权交还给第二个意见**。
     */
    static final double NOUN_ONLY = 0.5;
    /** 没命中任何动作：说不清就是说不清，硬凑一个 0.6 会让上层不敢用模型兜底。 */
    static final double MISS = 0.25;

    /** "要一份清单"的说法：有这些才算真的在问规则目录，而不是顺口提到"规则"。 */
    private static final Pattern LIST_ASK = Pattern.compile(
            "哪些|哪几条|几条例?规则|列表|列一下|列出来|清单|都有|有什么|什么样|几条|全部");
    /** 起草类动作词：与"规则/对账"同句出现，说明名词抢了动词。 */
    private static final Pattern DRAFTING = Pattern.compile(
            "起草|新建|新增|创建|加一条|加个|配一条|配个|做一条|建一条|建个|定义|搞一条");
    /** 对账域的对象词。 */
    private static final Pattern RULE_OBJECT = Pattern.compile("规则|对账|核对|口径|比对|匹配");

    private final ChatIntentRouter router;

    public KeywordPlanner(ChatIntentRouter router) {
        this.router = router;
    }

    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots, io.github.dolow.reconlens.chat.ContextFacts facts,
                                   List<String> history) {
        ChatIntentRouter.Call call = router.route(message);
        boolean hit = call.intent() != ChatIntent.HELP;
        double confidence = hit ? HIT : MISS;
        String reason = hit
                ? "词表命中：" + call.intent().form()
                : "词表没命中任何动作";
        if (hit && nounOnly(message, call)) {
            confidence = NOUN_ONLY;
            reason = "词表只认出话题词（" + call.intent().form() + "），没有明确动作";
        }
        return Optional.of(ChatPlan.keyword(call, confidence, reason));
    }

    /**
     * 命中的是"名词"还是"动作"。
     *
     * <p>两种情形都算名词命中，都要降权交给模型复核：
     * <ol>
     *   <li>判成"看规则"，但句子里没有要清单的说法 —— "规则"只是被提到，不是在问目录；</li>
     *   <li>句子里有起草类动词，却没判成起草 —— 说明别的档把它抢走了（词表漏了动词）。</li>
     * </ol>
     */
    private static boolean nounOnly(String message, ChatIntentRouter.Call call) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        if (call.intent() == ChatIntent.LIST_RULES && !LIST_ASK.matcher(text).find()) {
            return true;
        }
        return DRAFTING.matcher(text).find()
                && call.intent() != ChatIntent.DEFINE_RULE
                && RULE_OBJECT.matcher(text).find();
    }

    @Override
    public String describe() {
        return "词表路由（确定性，0 成本）";
    }
}
