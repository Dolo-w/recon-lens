package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.engine.Errors;
import io.github.dolow.reconlens.store.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 启动巡检：把上次进程没跑完的会话交回给 {@link AgentOrchestrator#resume}。
 *
 * <p>为什么不在启动线程里同步做：一次恢复可能要重新问模型（几秒到几十秒），
 * 而"服务起不来"和"上次那个会话还没收尾"是两件事——后者不该挡住端口监听。
 *
 * <p>为什么只恢复 {@code RUNNING}：那是"进程崩了留下的"，产物还热。
 * {@code FAILED} 是执行里真出了异常才有的状态，救不救由人决定 ——
 * 自动重试会让"一直失败的会话"每次重启都撞一次墙。所以 {@code FAILED} 只能由人显式调
 * {@code POST /api/agent/runs/{id}/resume} 恢复。
 *
 * <p>为什么恢复失败时保持原状而不是标 FAILED：失败原因可能是临时的（库连不上、模型抖动），
 * 标成 FAILED 就把"下次重启还能再试一次"的机会扔了。代价是每次重启最多重试一轮，
 * 这是有界的，日志里写清楚就够。
 *
 * <p>为什么失败只记日志不抛出：一个会话恢复失败不该影响其它会话，也不该把应用标记成不健康。
 */
@Component
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class AgentRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRecovery.class);

    private final AgentRunRepository runs;
    private final AgentOrchestrator orchestrator;

    /**
     * 本进程的启动时刻：只恢复这之前创建、且仍是 {@code RUNNING} 的会话。
     *
     * <p>端口在就绪事件之前已经监听，一条刚被本进程受理的会话很可能正在跑；
     * 不加这条线，启动恢复会跟它并行跑同一个会话。
     */
    private final Instant processStart = Instant.now();

    public AgentRunRecovery(AgentRunRepository runs, AgentOrchestrator orchestrator) {
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<Long> unfinished;
        try {
            unfinished = runs.unfinishedRunIds(processStart);
        } catch (RuntimeException e) {
            log.warn("启动巡检读不到未完成的会话（不影响启动）：{}", Errors.describe(e));
            return;
        }
        if (unfinished.isEmpty()) {
            return;
        }
        log.info("启动巡检：发现 {} 个未完成的会话（{}），交给恢复", unfinished.size(), unfinished);
        // 单线程、daemon：恢复是串行的（同一条库连接上的写入按顺序来更像人操作），也别拦着进程退出
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-run-recovery");
            t.setDaemon(true);
            return t;
        });
        try {
            worker.submit(() -> recoverAll(unfinished));
        } finally {
            worker.shutdown();
        }
    }

    private void recoverAll(List<Long> unfinished) {
        int ok = 0;
        int failed = 0;
        for (long runId : unfinished) {
            try {
                AgentOrchestrator.AgentRunResult result = orchestrator.resume(runId, "startup-recovery");
                log.info("启动巡检：会话 #{} 恢复完成 → {}（{}）", runId, result.status(), result.message());
                ok++;
            } catch (RuntimeException e) {
                log.warn("启动巡检：会话 #{} 恢复失败，保持原状（可人工再试）：{}", runId, Errors.describe(e));
                failed++;
            }
        }
        log.info("启动巡检结束：成功 {} 个，失败 {} 个", ok, failed);
    }

    /** 测试用：等到恢复线程空闲（生产代码不调它）。 */
    void awaitIdle(long timeoutMillis) {
        Thread.yield();
        try {
            TimeUnit.MILLISECONDS.sleep(Math.min(timeoutMillis, 50));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


