package io.github.dolow.reconlens.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调模型这条路上的共用熔断器：连续失败若干次就闭嘴一段时间。
 *
 * <p><b>为什么从 CompositePlanner 里搬出来</b>：原本只有会话路由知道自己被模型拖慢了。
 * 但一条会话里调模型的地方有三处 —— 路由（哪句话什么意思）、归因（这个差异为什么）、
 * 起草（把业务描述变成规则）—— 模型挂了的时候三处一起挂，每一处都各自等满超时。
 * 用户点一次"解释"，等待时间就是三处超时相加；而熔断状态是分开记的，于是谁也救不了谁。
 *
 * <p>所以「每个调模型的路径都要有账本」比「所有路径共用一个计数器」更贴着问题：归因失败不代表路由坏了，
 * 一个组件把闸关了就把别的组件一起停掉，反而是过度反应。判定权留在各自手里：调用方问 {@link #allow()}，\n * 自己决定关了之后退回什么（词表 / 模板 / 转人工）。
 * 反过来，如果把它做成"熔断时抛异常"，就等于把降级路径写死在这一个类里 —— 那三处需要
 * 的降级根本不是一回事。
 *
 * <p>线程安全：agent 是并发跑的（一个批次多个任务），计数用原子类型；打开后不重置计数，
 * 冷却结束自然放行，再失败一次就再次打开 —— 避免"半开"状态下的抖动。
 */
public final class LlmCircuitBreaker {

    /** 默认：连续 3 次失败就打开。 */
    public static final int DEFAULT_FAILURES_BEFORE_OPEN = 3;

    /** 默认：打开后冷却 5 分钟。 */
    public static final long DEFAULT_COOL_DOWN_MS = 5 * 60 * 1000L;

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    private final String name;
    private final int failuresBeforeOpen;
    private final long coolDownMs;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong();

    public LlmCircuitBreaker(String name) {
        this(name, DEFAULT_FAILURES_BEFORE_OPEN, DEFAULT_COOL_DOWN_MS);
    }

    public LlmCircuitBreaker(String name, int failuresBeforeOpen, long coolDownMs) {
        this.name = name;
        this.failuresBeforeOpen = failuresBeforeOpen;
        this.coolDownMs = coolDownMs;
    }

    /** 现在能不能调。冷却没到点就是 false。 */
    public boolean allow() {
        long until = openUntil.get();
        return until == 0 || System.currentTimeMillis() >= until;
    }

    /** 记一次失败；够数就打开，并打一条能看懂的日志。 */
    public void onFailure() {
        int count = failures.incrementAndGet();
        if (count >= failuresBeforeOpen && openUntil.get() == 0) {
            openUntil.set(System.currentTimeMillis() + coolDownMs);
            log.warn("{} 连续失败 {} 次，熔断 {} 秒，期间走降级路径", name, count, coolDownMs / 1000);
        }
    }

    /** 记一次成功：清零，并立刻恢复。 */
    public void onSuccess() {
        failures.set(0);
        openUntil.set(0);
    }

    /** 还剩多久冷却（毫秒）；没打开就是 0。 */
    public long remainingMs() {
        long until = openUntil.get();
        long now = System.currentTimeMillis();
        return until <= now ? 0 : until - now;
    }

    /** 给运维/测试看的一句话状态。 */
    public String state() {
        long left = remainingMs();
        return left > 0 ? "熔断中，还剩 " + (left / 1000) + " 秒" : "正常";
    }

    public String name() {
        return name;
    }
}
