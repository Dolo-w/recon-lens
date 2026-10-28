package io.github.dolow.reconlens.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 会话级 token 账本：这一轮花了多少、这个会话累计花了多少。
 *
 * <p><b>为什么记在内存而不是 {@code ChatSession} 上</b>：会话状态是一段 JSON，加字段要走
 * 仓储/服务/迁移三处，而它换来的只是"重启后累计值归零"——累计值本来就是"这个连接聊了多久"
 * 的近似，不是账务数据。内存 Map 与 SSE 连接同生命周期，够用且零侵入。
 *
 * <p><b>它管什么</b>：只决定"预算档要不要收紧"。{@link TurnBudget.Phase#UNDERSTANDING}
 * 每轮给 12000，一直开着会在长会话里把窗口吃穿；累计过线之后降到
 * {@link TurnBudget.Phase#ACTING}，这才是"先理解清楚意图再谈预算"的落地方式——
 * 理解阶段的额度足够大，只有在会话确实很长时才退回省电档。
 */
@Component
public class TurnLedger {

    /**
     * 累计过线之后收紧到 {@code ACTING} 档。
     *
     * <p>取 10 万：按理解档每轮 ~1 万估，这是"连续聊十几轮"的量级。低于它就不该为省 token
     * 去冒"没理解清楚"的险；高于它窗口本来就快满了，收紧是必要的而不是选择。
     */
    static final int TIGHT_AT = 100_000;

    private final Map<Long, AtomicInteger> used = new ConcurrentHashMap<>();

    /** 记一轮，返回这个会话的累计值。 */
    public int add(long sessionId, int tokens) {
        if (sessionId <= 0 || tokens <= 0) {
            return used(sessionId);
        }
        return used.computeIfAbsent(sessionId, key -> new AtomicInteger()).addAndGet(tokens);
    }

    /** 这个会话累计用了多少（估算值）。 */
    public int used(long sessionId) {
        AtomicInteger counter = used.get(sessionId);
        return counter == null ? 0 : counter.get();
    }

    /** 下一轮该用哪一档：没说过线就用理解档。 */
    public TurnBudget.Phase phaseOf(long sessionId) {
        return used(sessionId) < TIGHT_AT ? TurnBudget.Phase.UNDERSTANDING : TurnBudget.Phase.ACTING;
    }

    /** 会话被删/换人时清账，免得 id 复用后继承前一个会话的累计值。 */
    public void forget(long sessionId) {
        used.remove(sessionId);
    }
}
