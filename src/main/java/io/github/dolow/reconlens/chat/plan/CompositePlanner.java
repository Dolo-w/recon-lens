package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>词表剩下什么</b>：零参数、零歧义的短读问句（「有哪些规则」这种五个字没人会误解的话）。
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

    private final KeywordPlanner keyword;
    private final LlmPlanner llm;
    private final double keywordAccept;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

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
        ChatPlan keywordPlan = keyword.plan(message, slots, facts, history)
                .orElseThrow(() -> new IllegalStateException("词表规划器必须总能给出结果"));
        if (fastPath(message, keywordPlan, slots) || !llmReady()) {
            return Optional.of(keywordPlan);
        }
        // 摘要只喂模型层：词表是确定性匹配，它不需要"更早聊过什么"
        Optional<ChatPlan> modelPlan = llm.plan(message, slots, facts, history, summary);
        if (modelPlan.isEmpty()) {
            onFailure();
            return Optional.of(keywordPlan);
        }
        onSuccess();
        ChatPlan plan = modelPlan.get();
        // 1) 参数与系统事实冲突（说了个不存在的规则/任务/表）：不许执行，先问清楚。
        //    这一条是"看起来合理但答非所问"最便宜的拦截点——它不靠概率，靠库里有没有这个东西。
        if (plan.evidence() == ChatPlan.Evidence.CONFLICT) {
            return Optional.of(clarify(plan, keywordPlan, "你说的那个我在库里没找到"));
        }
        // 2) 模型明确说"没听懂"（HELP、无候选）而词表确定命中时，保留词表。
        //    这只防模型抽风，不构成"词表抢答"：模型一旦给出理解，就以模型为准。
        if (plan.call().intent() == ChatIntent.HELP && !plan.hasOptions() && certainHit(keywordPlan)) {
            return Optional.of(keywordPlan);
        }
        return Optional.of(plan);
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
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        return text.length() <= FAST_PATH_CHARS;
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
        long until = openUntil.get();
        return until == 0 || System.currentTimeMillis() >= until;
    }

    private void onFailure() {
        int count = failures.incrementAndGet();
        if (count >= FAILURES_BEFORE_OPEN && openUntil.get() == 0) {
            openUntil.set(System.currentTimeMillis() + COOL_DOWN_MS);
            log.warn("模型规划器连续失败 {} 次，熔断 5 分钟，期间只用词表", count);
        }
    }

    private void onSuccess() {
        failures.set(0);
        openUntil.set(0);
    }

    /** 给测试和运维看：现在是通着还是熔断了。 */
    public String state() {
        if (llm == null || !llm.configured()) {
            return "未配置模型，只用词表";
        }
        long until = openUntil.get();
        if (until > System.currentTimeMillis()) {
            return "熔断中，还剩 " + ((until - System.currentTimeMillis()) / 1000) + " 秒";
        }
        return "正常";
    }

    @Override
    public String describe() {
        return keyword.describe() + " → " + (llm == null ? "无模型" : llm.describe()) + "（" + state() + "）";
    }
}
