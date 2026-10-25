package io.github.dolow.reconlens.chat.plan;

import io.github.dolow.reconlens.chat.ChatSlots;
import io.github.dolow.reconlens.chat.ContextFacts;
import java.util.List;
import java.util.Optional;

/**
 * 一句话 + 会话焦点 + 系统事实 + 最近几轮原话 → 一个（可能是猜测的）计划。
 *
 * <p>接口刻意返回 {@link Optional}：**"没听懂"是一个合法结果**，不是异常。
 * P1 的做法是"没命中就掉帮助"，真正的毛病不在帮助写得好不好，而在于系统没有"我不知道"这个状态。
 *
 * <p>{@code facts} 是只读的系统事实（规则目录、最近任务、表名），<b>不含任何数据行</b>。
 * 模型看不到库，所以要么给它这份索引，要么它就只能按动作表猜——猜出来的东西再合法也可能错。
 *
 * <p>{@code history} 是最近几轮用户原话（不含工具结果）：用来判断"它/那单/刚才"指什么。
 */
public interface ChatPlanner {

    Optional<ChatPlan> plan(String message, ChatSlots slots, ContextFacts facts, List<String> history);

    /**
     * 带摘要的版本：更早轮次已被压缩成一段，模型靠它回答"之前那个任务""上次说的那个方案"。
     *
     * <p>摘要只进 P2（背景），不进 P0：它每轮都在变，放进稳定前缀等于让缓存全失效。
     */
    default Optional<ChatPlan> plan(String message, ChatSlots slots, ContextFacts facts,
                                    List<String> history, String summary) {
        return plan(message, slots, facts, history);
    }

    /** 不给事实/历史的老签名：表单入口和单测用得上。 */
    default Optional<ChatPlan> plan(String message, ChatSlots slots) {
        return plan(message, slots, ContextFacts.EMPTY, List.of());
    }

    default Optional<ChatPlan> plan(String message, ChatSlots slots, List<String> history) {
        return plan(message, slots, ContextFacts.EMPTY, history);
    }

    /** 给自己的实现写一句人话，出现在启动日志和 {@code /api/chat/stats} 里。 */
    String describe();
}
