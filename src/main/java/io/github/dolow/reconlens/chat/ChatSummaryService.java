package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.store.ChatSessionRepository;

import java.util.List;

/**
 * 什么时候压、压到哪（ADR-0001 D2）。
 *
 * <p><b>触发条件按窗口比例，不按轮数</b>：轮数触发看着简单，但它把"轮"当成了"信息量"。
 * 对账会话里一轮可以贴 137 条差异，也可以只是"嗯"；按轮数触发会出现"第 8 轮就爆窗口"
 * 和"第 20 轮还很空"两种都错的情况。所以判断只看一件事：<b>离窗口上限还有多少余量</b>。
 *
 * <p>三个条件同时满足才压：
 * <ol>
 *   <li>{@code need > budget}：确实装不下了，{@code budget = window × 0.55}。留 45% 给下一轮
 *       的工具输出与回答，压到刚好的结果是"压完立刻又满"。</li>
 *   <li>{@code turnCount >= 4}：下界保护。两三轮的会话压它没有收益，还会把"刚才那句"压丢。</li>
 *   <li>{@code recentTokens >= 2000}：单轮爆量（比如一次贴进来 50KB 差异）不该靠摘要救，
 *       那种情况要截断工具结果并给翻页出口（D7）。</li>
 * </ol>
 *
 * <p>另有一条比上面三条都重要的顺序：<b>工具结果截断优先于摘要</b>。摘要是"把过去说的话变短"，
 * 截断是"把这一次要放进来的东西变短"——后者便宜一个数量级，且保真得多。
 */
public class ChatSummaryService {

    /** 保守默认：多数 OpenAI 兼容模型的上下文窗口远大于此，取小值只会让压缩更早发生。 */
    private static final int DEFAULT_WINDOW_TOKENS = 8_000;
    private static final double BUDGET_RATIO = 0.55;
    private static final int MIN_TURNS = 4;
    private static final int MIN_RECENT_TOKENS = 2_000;
    /** 系统提示 + 12 个动作的工具清单，粗估一个常数；算不准只会让压缩早一点。 */
    private static final int SYSTEM_OVERHEAD_TOKENS = 800;
    private static final int SCAN_LIMIT = 400;

    private final ChatSessionRepository sessions;
    private final int windowTokens;
    /** 归纳层。可空：没有模型时整条链路退回纯截断，行为与 P1 一致。 */
    private final SummaryEnricher enricher;

    public ChatSummaryService(ChatSessionRepository sessions) {
        this(sessions, DEFAULT_WINDOW_TOKENS, null);
    }

    public ChatSummaryService(ChatSessionRepository sessions, int windowTokens) {
        this(sessions, windowTokens, null);
    }

    public ChatSummaryService(ChatSessionRepository sessions, int windowTokens, SummaryEnricher enricher) {
        this.sessions = sessions;
        this.windowTokens = windowTokens <= 0 ? DEFAULT_WINDOW_TOKENS : windowTokens;
        this.enricher = enricher;
    }

    /**
     * 到了就压，没到就什么也不做。
     *
     * <p>任何异常都不外抛：压缩是"让对话继续顺畅"的后台动作，它失败的正确降级是
     * "这一轮上下文长一点"，而不是让用户的提问返回 500。
     *
     * @return 压了返回新摘要，没压返回 {@code null}
     */
    public String maybeSummarize(SessionScope scope, long sessionId) {
        try {
            ChatSession session = sessions.find(scope, sessionId).orElse(null);
            if (session == null || session.turnCount() < MIN_TURNS) {
                return null;
            }
            List<ChatTurn> pending = sessions.turns(sessionId, Integer.MAX_VALUE, SCAN_LIMIT).stream()
                    .filter(t -> t.seq() > session.summaryUptoSeq())
                    .toList();
            if (pending.size() < MIN_TURNS) {
                return null;
            }
            int recent = 0;
            for (ChatTurn turn : pending) {
                recent += ChatSummarizer.estimateTokens(turn.content());
            }
            int need = SYSTEM_OVERHEAD_TOKENS
                    + ChatSummarizer.estimateTokens(session.summary())
                    + recent;
            int budget = (int) Math.floor(windowTokens * BUDGET_RATIO);
            if (need <= budget || recent < MIN_RECENT_TOKENS) {
                return null;
            }
            return summarize(scope, session, pending);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 手动压一次（运维/演示用）。
     *
     * <p>存在的理由不是"给接口留个按钮"，而是：出问题时需要一条能复现的路径——
     * 自动触发要聊到 2000 token 才发生，排查时等不起。
     */
    public String summarizeNow(SessionScope scope, long sessionId) {
        ChatSession session = sessions.find(scope, sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        List<ChatTurn> pending = sessions.turns(sessionId, Integer.MAX_VALUE, SCAN_LIMIT).stream()
                .filter(t -> t.seq() > session.summaryUptoSeq())
                .toList();
        if (pending.isEmpty()) {
            return session.summary();
        }
        return summarize(scope, session, pending);
    }

    private String summarize(SessionScope scope, ChatSession session, List<ChatTurn> pending) {
        ChatSummarizer.Result result =
                ChatSummarizer.summarize(session.summary(), session.summaryUptoSeq(), pending);
        if (result.isEmpty() || result.uptoSeq() <= session.summaryUptoSeq()) {
            return session.summary();
        }
        // 归纳层叠加在截断版前面：截断保底（可复现、无 key 可用），模型只补"结论/未决"。
        // 它失败或编造 id 时 enrich 会原样返回截断版——水位线照常推进，不因为归纳失败卡住压缩。
        String text = enricher == null ? result.text() : enricher.enrich(result.text(), pending);
        sessions.saveSummary(scope, session.id(), text, result.uptoSeq());
        return text;
    }
}
