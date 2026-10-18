package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;

/**
 * 试算残留的定时回收：样本表（{@code trial_*}）与过期 TRIAL 任务。
 *
 * <h2>为什么清理要挂在"定时"上，而不是"启动时"</h2>
 * 启动时扫一遍在单实例下是对的 —— 那时库里的样本表要么是本次刚建的，要么确实是谁崩溃留下的。
 * 多实例下这个推论断了：B 启动时看到的 {@code trial_xxx_l} 可能正是 A 此刻在跑的那张，
 * 于是"清理"变成"删别人的工作区"。改成定时 + 租约判据后，<b>何时跑不重要，跑的时候判据说了算</b>。
 *
 * <h2>为什么用 fixedDelay 而不是 fixedRate</h2>
 * fixedDelay 保证上一次跑完才排下一次；fixedRate 在清理变慢（表多、库慢）时会排队堆积。
 * 而且清理本身不需要"准点"，它需要的是"别插队"。
 *
 * <h2>为什么单实例内不需要分布式锁</h2>
 * 多实例同时跑是<b>预期内</b>的：判据（心跳超时 / 无登记）本身就保证了不会互相删错表，
 * 并发的结果最多是两边对同一张表各发一次 {@code DROP TABLE IF EXISTS}（幂等）。
 * 加锁反而会引入"锁没释放导致永远不清"的新失败模式。
 */
public class TrialCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialCleanupScheduler.class);

    private final ShadowTrialService trials;
    private final SampleMaterializer materializer;
    private final ReconProperties properties;

    public TrialCleanupScheduler(ShadowTrialService trials, SampleMaterializer materializer,
                                 ReconProperties properties) {
        this.trials = trials;
        this.materializer = materializer;
        this.properties = properties;
    }

    /**
     * 默认 30 分钟跑一次，首次延迟 10 分钟。
     *
     * <p>首次延迟不是为了"等库准备好"，而是为了不让一个刚启动的实例在启动流程最忙的时候
     * 去扫全库表名 —— 那是最容易和别的实例的试算撞在一起的时刻。取值也留了测试余量：
     * 全量测试上下文里这个调度器同样会启动，延迟太短会让清理插进某个用例正在跑试算的中间。
     *
     * <p>两个间隔<b>不</b>放进 {@link ReconProperties}：它们是纯粹的运维参数，和 Spring 的调度机制绑定，
     * 放这里就只有一处默认值。进 {@code ReconProperties} 会制造第二处默认值，两处不同步就成了
     * "配了但没生效"的经典来源（租约时长不一样，它是判据，必须可校验，所以在配置类里）。
     */
    @Scheduled(fixedDelayString = "${recon.trial.cleanup-interval-ms:1800000}",
            initialDelayString = "${recon.trial.cleanup-initial-delay-ms:600000}")
    public void run() {
        Instant now = Instant.now();
        // 两件事各自兜异常：删表失败不该拖住删任务记录，反之亦然。
        // 定时任务里抛出去不会让调度停摆，但会给每个周期留一条"看起来像崩溃"的堆栈。
        try {
            int tables = materializer.cleanupResidual(now,
                    Duration.ofMinutes(properties.getTrial().getCleanupLeaseMinutes()));
            if (tables > 0) {
                log.info("试算样本表回收：{} 张", tables);
            }
        } catch (RuntimeException e) {
            log.warn("回收试算样本表失败，本轮跳过（下一轮会再试）: {}", e.toString(), e);
        }
        try {
            int jobs = trials.cleanupExpiredTrials();
            if (jobs > 0) {
                log.info("试算任务回收：{} 个", jobs);
            }
        } catch (RuntimeException e) {
            log.warn("回收过期试算任务失败，本轮跳过（下一轮会再试）: {}", e.toString(), e);
        }
    }
}
