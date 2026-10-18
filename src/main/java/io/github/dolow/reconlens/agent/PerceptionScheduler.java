package io.github.dolow.reconlens.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 巡检的定时触发：单线程 + {@code scheduleWithFixedDelay}。
 *
 * <h2>为什么不用 {@code @Scheduled}</h2>
 * 两条硬理由：
 * <ol>
 *   <li>{@code @Scheduled(fixedDelay = 0)} 表示<b>"立刻再来一轮"</b>，不是"关掉"。
 *       而这里 {@code perception-interval-ms = 0} 的语义是"只做按需诊断、不做定时巡检" ——
 *       用注解表达这个语义，0 会变成一个把 CPU 打满的死循环；</li>
 *   <li>巡检要能<b>手动触发</b>（测试、运维、REST）。自己持有一个单线程执行器，
 *       "立即跑一轮"和"定时跑"走的是同一条路径，不会被调度线程踩到。</li>
 * </ol>
 *
 * <h2>去重靠三件事，缺一不可</h2>
 * <ul>
 *   <li>{@code scheduleWithFixedDelay}：上一轮<b>跑完</b>才排下一轮，不会排队堆积；</li>
 *   <li>{@link AtomicBoolean}：手动触发与定时触发可能重叠，重叠时后来的直接跳过 ——
 *       两轮巡检同时读同一批任务，会各自认为自己是"第一次发现"，告警抑制就失效；</li>
 *   <li>单线程：巡检内部串行，不存在自己跟自己抢。</li>
 * </ul>
 *
 * <h2>多实例是已知缺口</h2>
 * 多实例部署时每个实例都会扫，抑制表在进程内 —— 同一个异常会被报 N 次（N = 实例数）。
 * 这一轮<b>不做分布式锁</b>（R18 的处置是"要么加锁、要么把单实例假设写死"），
 * 选择后者：先有真实的双实例部署需求，再决定用 DB 行锁还是独立调度进程。
 * 在那之前，这条假设必须写在文档里，而不是靠"反正只有一个实例"的默契。
 */
public class PerceptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(PerceptionScheduler.class);

    private final PerceptionService perception;
    private final AgentProperties properties;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PerceptionScheduler(PerceptionService perception, AgentProperties properties) {
        this.perception = perception;
        this.properties = properties;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "recon-perception");
            // 守护线程：巡检不该成为"进程关不掉"的原因
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 挂上定时。{@code interval <= 0} 表示不装定时器 —— 但"手动跑一轮"仍然可用，
     * 所以这里不是"不启动"，而是"不排期"。
     */
    public void start() {
        long interval = properties.getPerceptionIntervalMs();
        if (interval <= 0) {
            log.info("巡检定时器未启用（recon.agent.perception-interval-ms={}），仅保留按需触发", interval);
            return;
        }
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("巡检定时器已启用：每 {} 秒一轮（单实例假设，见 PerceptionScheduler 注释）", interval / 1000);
    }

    /**
     * 跑一轮。重叠时的跳过不是错误 —— 但必须留一条 debug：否则"我明明触发了却没结果"
     * 会被当成 bug 查半天。
     *
     * @return 真的跑了返回报告；因为已在跑而跳过时返回 {@code null}
     */
    public PerceptionService.PerceptionReport runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮巡检还没跑完，本次触发跳过");
            return null;
        }
        try {
            return perception.scan();
        } catch (RuntimeException e) {
            // 定时任务里抛出去不会让调度停摆，但每个周期留一条像崩溃的堆栈只会训练人忽略日志
            log.warn("巡检本轮失败，下一轮会再试: {}", e.toString(), e);
            return null;
        } finally {
            running.set(false);
        }
    }

    private void tick() {
        runOnce();
    }

    /** 上下文关闭时调用（Bean 的 destroyMethod）。不关会留一个 daemon 线程，测试里尤其明显。 */
    public void shutdown() {
        executor.shutdownNow();
    }
}
