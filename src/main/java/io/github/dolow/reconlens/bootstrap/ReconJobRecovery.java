package io.github.dolow.reconlens.bootstrap;

import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：把上次进程中断留下的 RUNNING/PENDING 任务回收成 FAILED。
 *
 * <p>为什么在启动时做而不是提供接口让人手动调：孤儿状态的危害是"闸门永远关不上"
 * （执行中的任务不能重跑），越晚发现越堵 —— 服务一起来就该说清楚"这些是断的"。
 * 放在 {@link ApplicationRunner} 里（而不是 @PostConstruct）：等整个上下文就绪、
 * 数据源健康检查过了再动手，避免半初始化状态下误伤。
 *
 * <p>与 agent 包的 {@code AgentRunRecovery} 是兄弟关系：那边恢复 Agent 会话，
 * 这边恢复对账任务，互不依赖。
 */
@Component
public class ReconJobRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconJobRecovery.class);

    private final ReconRepository repository;

    public ReconJobRecovery(ReconRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = repository.recoverInterruptedJobs();
        if (recovered > 0) {
            log.warn("启动自检：回收了 {} 个上次进程中断留下的任务（已标 FAILED，可 retry 重跑失败分片）", recovered);
        }
    }
}
