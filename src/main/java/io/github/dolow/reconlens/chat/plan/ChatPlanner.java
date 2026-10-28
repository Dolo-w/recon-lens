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

    /**
     * 带预算档的版本：这一轮是在"弄懂要什么"还是在"执行/看结果"。
     *
     * <p>档位由运行时按会话累计用量给（见 {@code TurnLedger}），规划器不自己猜：
     * 它只决定每一段能占多少桶。默认忽略档位，等价于老行为。
     */
    default Optional<ChatPlan> plan(String message, ChatSlots slots, ContextFacts facts,
                                    List<String> history, String summary,
                                    io.github.dolow.reconlens.chat.TurnBudget.Phase phase) {
        return plan(message, slots, facts, history, summary);
    }

    /**
     * 「还差一步吗」：给定刚执行完的那一步和它的结果，问规划器要不要再做一个动作。
     *
     * <p>默认没有下一步 —— 词表规划器本来就是确定性单步路由，它没有"看完结果再决定"的能力，
     * 硬给它加一步只会把猜的东西叠加两次。只有模型规划器在 {@code recon.chat.loop.enabled=true}
     * 时才会返回候选，所以<b>关掉开关时整条链路的行为与引入它之前一致</b>。
     *
     * <p>为什么单独一个方法而不是塞进 {@link #plan}：这是两次不同的判断。
     * 第一次看的是"用户说了什么"，第二次看的是"库里返回了什么"——
     * 后者的输入里有工具结果，而工具结果不能进第一次的提示词（那是答案，不是线索）。
     */
    default Optional<ChatPlan> continuePlan(String message, ChatSlots slots, ContextFacts facts,
                                            ChatPlan done, String resultText) {
        return Optional.empty();
    }

    /**
     * 自由循环的第二跳及以后：把「用户原话 + 到目前为止做过的（含结果）」再交给规划器，
     * 它可以直接调下一个工具、也可以就此回话。
     *
     * <p>与 {@link #continuePlan} 的区别：后者是"只读 + 一次"的保守续跳，给老路径用；
     * 这里是 {@code recon.chat.mode=loop} 专用的无编排通道——没有意图枚举、没有槽位改写，
     * 唯一的硬约束是写动作由运行时拦下等回执（在 {@code ChatRuntime} 里，不在这里）。
     *
     * <p>默认空：老路径与词表规划器都没有"自己往下走"的能力，硬加只会把猜测叠加。
     *
     * @param transcript 已经执行过的步骤与它们的真实结果（按顺序，已按预算裁过）
     * @param hop        这是第几跳（从 1 开始），实现可用它刹车
     */
    default Optional<ChatPlan> continueLoop(String message, ChatSlots slots, ContextFacts facts,
                                            List<String> history, String summary,
                                            String transcript, int hop) {
        return Optional.empty();
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
