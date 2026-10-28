package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型优先理解，词表只留快路径和降级，并且**会自己熔断**。
 *
 * <p><b>顺序为什么是模型在前</b>：词表认的是字面，理解的是人。同一句「把订单和状态对一下」，
 * 词表只能认出"对"和名词，模型能认出"这是要起草一条按订单号+状态的对账规则"。
 * 之前反过来——词表命中就执行、命中不了才问模型——结果是模型永远在给词表的错判擦屁股，
 * 而大部分日常说法恰好能被词表"命中"成某个似是而非的动作。
 *
 * <p><b>词表剩下什么</b>：零参数、零歧义的短命令（「有哪些规则」这种五个字没人会误解的话）。
 * 问句不算命令：「这几张表什么关系」也是八个字，但它要的是解释，不是一张表清单。
 * 这既是省一次网络往返，也是给模型挂掉时留一条确定性的路。
 *
 * <p><b>为什么要熔断，而不是每次都试</b>：key 过期、网关挂掉、余额不足，这三种失败会持续
 * 好几天。没有熔断的话，每一次会话都要先等一个超时才退回词表——用户会以为系统坏了，
 * 而实际上词表那一层是好的。连续失败 {@value #FAILURES_BEFORE_OPEN} 次就静默五分钟，
 * 到期自动再试一次（半开）。
 */
public final class CompositePlanner implements ChatPlanner {

    private static final Logger log = LoggerFactory.getLogger(CompositePlanner.class);

    static final int FAILURES_BEFORE_OPEN = 3;
    static final long COOL_DOWN_MS = 5 * 60 * 1000L;

    /** 词表快路径的字数上限：超过它，句子里就可能藏着指代、条件或口径。 */
    static final int FAST_PATH_CHARS = 12;

    /** 句中的疑问词：有它就不是"让我做事"，而是"我想知道点什么"。 */
    private static final java.util.regex.Pattern QUESTION_WORDS = java.util.regex.Pattern.compile(
            "什么|怎么|为什么|为啥|多少|是不是|能不能|可不可以|关系|如何|区别");

    private final KeywordPlanner keyword;
    private final LlmPlanner llm;
    private final double keywordAccept;
    /** 熔断语义搬到了 llm 包：路由 / 归因 / 起草各记一笔，共用同一个类与同一套阈值。 */
    private final io.github.dolow.reconlens.llm.LlmCircuitBreaker breaker =
            new io.github.dolow.reconlens.llm.LlmCircuitBreaker("模型规划器", FAILURES_BEFORE_OPEN, COOL_DOWN_MS);

    public CompositePlanner(KeywordPlanner keyword, LlmPlanner llm) {
        this(keyword, llm, 0.7);
    }

    public CompositePlanner(KeywordPlanner keyword, LlmPlanner llm, double keywordAccept) {
        this.keyword = keyword;
        this.llm = llm;
        this.keywordAccept = keywordAccept;
    }

    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots, io.github.dolow.reconlens.chat.ContextFacts facts,
                                   List<String> history) {
        return plan(message, slots, facts, history, null);
    }

    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots, io.github.dolow.reconlens.chat.ContextFacts facts,
                                   List<String> history, String summary) {
        return decide(message, slots, facts, history, summary,
                io.github.dolow.reconlens.chat.TurnBudget.Phase.UNDERSTANDING);
    }

    /**
     * 带预算档的版本：档位透传给模型层，词表层（确定性匹配）不需要。
     *
     * <p>快路径和熔断判定与档位无关：一句十二字以内的读问句，无论预算多紧都是零成本确定的。
     */
    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots, io.github.dolow.reconlens.chat.ContextFacts facts,
                                   List<String> history, String summary,
                                   io.github.dolow.reconlens.chat.TurnBudget.Phase phase) {
        return decide(message, slots, facts, history, summary, phase);
    }

    private Optional<ChatPlan> decide(String message, ChatSlots slots,
                                      io.github.dolow.reconlens.chat.ContextFacts facts,
                                      List<String> history, String summary,
                                      io.github.dolow.reconlens.chat.TurnBudget.Phase phase) {
        ChatPlan keywordPlan = keyword.plan(message, slots, facts, history)
                .orElseThrow(() -> new IllegalStateException("词表规划器必须总能给出结果"));
        if (fastPath(message, keywordPlan, slots) || !llmReady()) {
            return Optional.of(keywordPlan);
        }
        // 摘要只喂模型层：词表是确定性匹配，它不需要"更早聊过什么"
        Optional<ChatPlan> modelPlan = llm.plan(message, slots, facts, history, summary, phase);
        if (modelPlan.isEmpty()) {
            onFailure();
            return Optional.of(keywordPlan);
        }
        onSuccess();
        ChatPlan plan = modelPlan.get();
        // 1) 闸门判定"不能直接执行"：说了个库里没有的规则/任务/表（CONFLICT），
        //    或者带着实体参数但事实核实不了（UNVERIFIED + 有实体）。这一条是
        //    "看起来合理但答非所问"最便宜的拦截点——它不靠概率，靠库里有没有这个东西。
        //
        //    写动作另走一道闸：运行时会先给回执、等用户说"确认"。选项清单对写动作没意义
        //    （用户要确认的是"要不要执行这句"，不是"从三个动作里挑一个"），所以这里放它过去。
        //
        //    工具调用也不进这道闸：模型已经把 name+args 写清楚了，参数对不对由工具自己判定
        //    （它给出的错误比"我在库里没找到"精确得多）。在这里拦，等于把一次合法调用
        //    变成一句反问，用户还得重新说一遍。
        if (!plan.isToolCall() && plan.needsClarification()) {
            if (plan.evidence() == ChatPlan.Evidence.CONFLICT || !plan.call().intent().isWrite()) {
                return Optional.of(clarify(plan, keywordPlan,
                        plan.evidence() == ChatPlan.Evidence.CONFLICT
                                ? "你说的那个我在库里没找到"
                                : "这个我没法核实，先确认一下"));
            }
        }
        // 2) 模型明确说"没听懂"（HELP、无候选）而词表确定命中时，保留词表。
        //    这只防模型抽风，不构成"词表抢答"：模型一旦给出理解，就以模型为准。
        //    工具调用同样豁免：有些工具本来就映射到 HELP（比如 recon_diagnose），
        //    看 intent 会把"模型选了工具"误判成"模型没听懂"。
        //    主动开口（reply）也豁免：那是模型在解释或反问，用户要的正是这句话。
        if (!plan.isToolCall() && !plan.isReply() && plan.call().intent() == ChatIntent.HELP
                && !plan.hasOptions() && certainHit(keywordPlan)) {
            return Optional.of(keywordPlan);
        }
        return Optional.of(plan);
    }

    /**
     * 第二步只问模型层，且**不参与熔断计数**（理由见 {@link LlmPlanner#continuePlan}）。
     *
     * <p>词表层不掺和：它的本事是"把确定的话对上号"，不是"看完结果再决定"。
     * 熔断中（{@link #llmReady()} 为 false）也不试——此时主路由已经走词表了，
     * 再为一次可有可无的第二跳去敲已经挂掉的模型，只会让这一轮变慢。
     */
    @Override
    public Optional<ChatPlan> continuePlan(String message, ChatSlots slots,
                                           io.github.dolow.reconlens.chat.ContextFacts facts,
                                           ChatPlan done, String resultText) {
        if (!llmReady()) {
            return Optional.empty();
        }
        return llm.continuePlan(message, slots, facts, done, resultText);
    }

    /**
     * 快路径：只有「没有挂起、零参数、句子短到没有歧义余地」的读动作才交给词表。
     *
     * <p>「有哪些规则」五个字，词表是零成本且确定；「帮我把账单和结算这两边的数据核对一下」
     * 十八个字，词表只能认出"核对"这个动词，必须问模型。挂起状态下哪怕只有三个字
     * （「就这个」）也可能是在回答上一问，一律交给模型。
     */
    private boolean fastPath(String message, ChatPlan keywordPlan, ChatSlots slots) {
        if (slots != null && slots.hasPending()) {
            return false;
        }
        if (!certainHit(keywordPlan)) {
            return false;
        }
        ChatIntentRouter.Call call = keywordPlan.call();
        if (call.intent().isWrite() || call.ruleCode() != null || call.jobId() != null
                || call.diffId() != null || call.table() != null || call.ruleBody() != null) {
            return false;
        }
        // 问句不是命令。「这几张表什么关系」八个字，词表只认得出"看数据"——照快路径走
        // 就会把一句"它们怎么关联"答成一张表清单。短不等于没有歧义。
        if (looksLikeQuestion(message)) {
            return false;
        }
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return text.length() <= FAST_PATH_CHARS;
    }

    /**
     * 这句是在问，还是在让我做事。
     *
     * <p>只做最省事的判断：句尾是疑问语气，或者句中有疑问词。「看下差异」不算问句，
     * 「这几张表什么关系」算——差别不在长度，在"用户是不是想知道点什么"。
     */
    private static boolean looksLikeQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.strip();
        if (text.endsWith("吗") || text.endsWith("呢") || text.endsWith("？") || text.endsWith("?")) {
            return true;
        }
        return QUESTION_WORDS.matcher(text).find();
    }

    /** 词表这次是不是"确定命中"：不是 HELP、够可信、关键参数齐。 */
    private boolean certainHit(ChatPlan keywordPlan) {
        return keywordPlan.call().intent() != ChatIntent.HELP
                && keywordPlan.confidence() >= keywordAccept
                && !missingRequired(keywordPlan.call());
    }

    /**
     * 把"模型猜的"和"词表猜的"一起摆出来，请用户挑一个。
     *
     * <p>不再拿置信度互相压制：两边的数字没有可比性（一个是规则匹配，一个是语言模型的自述）。
     * 澄清把选择权交回用户，比"选一个更像的然后执行"便宜得多。
     */
    private static ChatPlan clarify(ChatPlan model, ChatPlan keyword, String why) {
        java.util.List<String> options = new java.util.ArrayList<>();
        if (model.call().intent() != ChatIntent.HELP) {
            options.add(model.call().intent().name());
        }
        if (keyword.call().intent() != ChatIntent.HELP && keyword.call().intent() != model.call().intent()) {
            options.add(keyword.call().intent().name());
        }
        String reason = why + (model.reason().isBlank() ? "" : "（模型：" + model.reason() + "）");
        return ChatPlan.model(
                new io.github.dolow.reconlens.chat.ChatIntentRouter.Call(ChatIntent.HELP, null, null, null, null, null, null),
                0.3, reason, options, ChatPlan.Evidence.UNVERIFIED);
    }

    /**
     * 词表命中了，但**写动作缺关键参数**——这种情况值得让模型再看一眼。
     *
     * <p>「跑一下那个账单对账」词表能给 {@code RUN}，但给不出规则编码；而模型手里有事实卡片，
     * 能把它对到 {@code BILL_VS_SETTLE} 上。不让它试一次，用户就得自己报一遍编码。
     * 只对写动作这么做：读动作缺参数时反问更便宜，没必要多花一次网络往返。
     */
    private static boolean missingRequired(ChatIntentRouter.Call call) {
        return switch (call.intent()) {
            case RUN, BUILD_MODEL -> call.ruleCode() == null;
            case RETRY -> call.jobId() == null;
            default -> false;
        };
    }

    private boolean llmReady() {
        if (llm == null || !llm.configured()) {
            return false;
        }
        return breaker.allow();
    }

    private void onFailure() {
        breaker.onFailure();
    }

    private void onSuccess() {
        breaker.onSuccess();
    }

    /** 给测试和运维看：现在是通着还是熔断了。 */
    public String state() {
        if (llm == null || !llm.configured()) {
            return "未配置模型，只用词表";
        }
        long left = breaker.remainingMs();
        if (left > 0) {
            return "熔断中，还剩 " + (left / 1000) + " 秒";
        }
        return "正常";
    }

    @Override
    public String describe() {
        return keyword.describe() + " → " + (llm == null ? "无模型" : llm.describe()) + "（" + state() + "）";
    }
}
