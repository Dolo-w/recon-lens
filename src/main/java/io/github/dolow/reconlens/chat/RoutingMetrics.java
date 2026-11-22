package io.github.dolow.reconlens.chat;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 意图路由的评估闭环 —— 业界说的 per-intent accuracy / fallback rate / 负样本挖掘。
 *
 * <p><b>为什么要有它</b>：路由层此前是个开环。熔断计数器只知道"模型挂了几次"，
 * 知道不了"用户说了什么我们没听懂"。而「没听懂」的原话就是产品路线图——
 * 每一条 fallback 样本都是一条候选的词表新条目或新意图，不收集等于把
 * "越用越准"这句话只在归因层兑现、路由层兑现不了。
 *
 * <p><b>两级存储</b>：计数器是进程内的（重启归零，和知识层 ROI 一个口径，note 里写明）；
 * fallback 原语是<b>落库的</b>（{@code recon_chat_fallback}，append-only）——语料的价值
 * 恰恰在跨越重启积累，放内存等于每重启一次把产品路线图撕一页。
 *
 * <p>落库失败（老库没这张表）只告警一次，不挡路由：指标永远是旁路，不是主链路。
 */
public class RoutingMetrics {

    private static final Logger log = LoggerFactory.getLogger(RoutingMetrics.class);
    private static final int MAX_SAMPLES = 50;
    private static final int MAX_SAMPLE_CHARS = 120;

    private final JdbcTemplate jdbc;

    private final AtomicLong keywordPlans = new AtomicLong();
    private final AtomicLong modelPlans = new AtomicLong();
    private final AtomicLong toolPlans = new AtomicLong();
    private final AtomicLong replyPlans = new AtomicLong();
    private final AtomicLong clarifies = new AtomicLong();
    private final AtomicLong fallbacks = new AtomicLong();
    private final AtomicLong breakerTrips = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final Map<String, AtomicLong> byIntent = new ConcurrentHashMap<>();
    private final Deque<String> fallbackSamples = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile boolean persistWarned;

    public RoutingMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一轮规划出结果了：按来源归档。词表直出 / 模型工具 / 模型说话 / 模型意图。 */
    public void plan(io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        if (plan == null) {
            return;
        }
        if (plan.source() == io.github.dolow.reconlens.chat.plan.ChatPlan.Source.KEYWORD) {
            keywordPlans.incrementAndGet();
        } else if (plan.isToolCall()) {
            toolPlans.incrementAndGet();
        } else if (plan.isReply()) {
            replyPlans.incrementAndGet();
        } else {
            modelPlans.incrementAndGet();
        }
    }

    /** 这一轮真正执行的动作（含追问续接改写后的）。 */
    public void intent(ChatIntent executed) {
        if (executed != null) {
            byIntent.computeIfAbsent(executed.name(), k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** 澄清（把两个候选摆出来让用户挑）：模型和词表打架的那一类。 */
    public void clarify() {
        clarifies.incrementAndGet();
    }

    /**
     * 真没听懂：最终执行了 HELP、没有候选、模型也没说话。原话落库当负样本语料。
     */
    public void fallback(Long sessionId, String message) {
        fallbacks.incrementAndGet();
        if (message != null && !message.isBlank()) {
            String text = message.strip();
            String clipped = text.length() <= MAX_SAMPLE_CHARS ? text
                    : text.substring(0, MAX_SAMPLE_CHARS) + "…";
            fallbackSamples.addLast(clipped);
            while (fallbackSamples.size() > MAX_SAMPLES) {
                fallbackSamples.pollFirst();
            }
        }
        persist(sessionId, message);
    }

    public void breakerTrip() {
        breakerTrips.incrementAndGet();
    }

    public void cacheHit() {
        cacheHits.incrementAndGet();
    }

    private void persist(Long sessionId, String message) {
        if (jdbc == null || message == null || message.isBlank()) {
            return;
        }
        try {
            String text = message.strip();
            jdbc.update("INSERT INTO recon_chat_fallback (session_id, query_text) VALUES (?, ?)",
                    sessionId, text.length() <= 512 ? text : text.substring(0, 512));
        } catch (RuntimeException e) {
            if (!persistWarned) {
                persistWarned = true;
                log.warn("fallback 语料落库失败（老库缺 recon_chat_fallback 表？只告警这一次）", e);
            }
        }
    }

    /**
     * 出口。数字全部进程累计、重启归零——和 /api/knowledge/roi 同一个口径；
     * 跨重启的只有 fallback 语料（在表里）。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keywordPlans", keywordPlans.get());
        out.put("modelPlans", modelPlans.get());
        out.put("toolPlans", toolPlans.get());
        out.put("replyPlans", replyPlans.get());
        out.put("clarifies", clarifies.get());
        out.put("fallbacks", fallbacks.get());
        out.put("breakerTrips", breakerTrips.get());
        out.put("cacheHits", cacheHits.get());
        Map<String, Long> intents = new LinkedHashMap<>();
        byIntent.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> intents.put(e.getKey(), e.getValue().get()));
        out.put("byIntent", intents);
        out.put("fallbackSamples", new ArrayList<>(fallbackSamples));
        out.put("note", "路由计数为进程累计，重启归零；fallback 原语落库 recon_chat_fallback（跨重启积累）");
        return out;
    }
}
