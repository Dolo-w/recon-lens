package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.rule.RuleTrialRunner;
import io.github.dolow.reconlens.store.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台试算登记处：{@code recon_draft_rule} 立刻返回，{@code recon_trial_status} 轮询。
 *
 * <h2>为什么必须异步（评审 R16）</h2>
 * benchmark 实测单次对账 21.47 s，一次试算要跑两遍正式 + 一遍 canary —— 一分钟起步。
 * 而 MCP 客户端普遍 30 s 超时。同步做的话，调用方拿到的永远是一个超时异常，
 * 而服务端其实已经把草稿落库、试算也跑完了 —— 最糟的一种不一致：**有结果，没人知道**。
 *
 * <h2>三个刻意的选择</h2>
 * <ol>
 *   <li><b>先建会话再提交</b>：{@code runId} 由 {@link AgentRunRepository#startRun} 同步发出来，
 *       后台线程只负责执行。否则"立即返回一个可轮询的号"这句话做不到；</li>
 *   <li><b>单线程 + 有界队列</b>：试算会独占连接池（每个分片两个游标），
 *       并发两个会话就是拿"连接超时"换"看起来更快"。队列满了直接拒绝，比排队到超时好 ——
 *       拒绝是调用方立刻能处理的事实，排队是它等 2 分钟后拿到一个 503；</li>
 *   <li><b>任务表是内存的</b>：跨进程/重启后的真相在 {@code recon_rule.trial_*} 里。
 *       内存表只回答"现在跑到哪一步了"，查不到就落库查 —— 而不是假装没有这个任务。</li>
 * </ol>
 *
 * <p><b>进程重启会丢什么</b>：丢"排队中/进行中"的会话状态。启动时
 * {@link #run(ApplicationArguments)} 会把库里残留的 QUEUED/RUNNING 会话标成 FAILED 并写明原因 ——
 * 否则运维看到一行永远 RUNNING 的记录，分不清"还在跑"和"进程半路死了"。
 * 试算本身有租约回收，样本表不会漏。
 */
public class TrialTaskRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrialTaskRegistry.class);

    /** 默认的遗留会话判定窗口（分钟）。 */
    static final int DEFAULT_STALE_RUN_MINUTES = 10;

    public enum Status {
        QUEUED, RUNNING, SUCCESS, BLOCKED, FAILED;

        public boolean inFlight() {
            return this == QUEUED || this == RUNNING;
        }
    }

    /**
     * 一个后台会话的当前状态。
     *
     * @param code    ACT 阶段落了草稿之后才知道，之前为 null —— 所以轮询接口要允许按 runId 查
     * @param message 结束后的说明（审批入口、失败原因）
     */
    public record Task(long runId, String goal, Status status, String code, Integer version,
                       String message, Instant submittedAt, Instant finishedAt) {

        Task withStatus(Status next) {
            return new Task(runId, goal, next, code, version, message, submittedAt, finishedAt);
        }

        Task failed(String reason) {
            return new Task(runId, goal, Status.FAILED, code, version, reason, submittedAt, Instant.now());
        }
    }

    private final AgentRunRepository runs;
    private final AgentOrchestrator orchestrator;
    private final RuleTrialRunner trialRunner;
    private final ExecutorService pool;
    /** 只清理"开始超过这么久还没结束"的会话：另一个进程正在跑的不算遗留。 */
    private final int staleRunMinutes;
    private final ConcurrentHashMap<Long, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> draftIndex = new ConcurrentHashMap<>();

    public TrialTaskRegistry(AgentRunRepository runs, AgentOrchestrator orchestrator,
                             RuleTrialRunner trialRunner, int threads, int queueCapacity) {
        this(runs, orchestrator, trialRunner, threads, queueCapacity, DEFAULT_STALE_RUN_MINUTES);
    }

    public TrialTaskRegistry(AgentRunRepository runs, AgentOrchestrator orchestrator,
                             RuleTrialRunner trialRunner, int threads, int queueCapacity,
                             int staleRunMinutes) {
        this.runs = runs;
        this.staleRunMinutes = staleRunMinutes;
        this.orchestrator = orchestrator;
        this.trialRunner = trialRunner;
        AtomicInteger counter = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                Math.max(1, threads), Math.max(1, threads),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-trial-" + counter.incrementAndGet());
                    thread.setDaemon(true);      // 不阻止 JVM 退出：试算不是"必须跑完"的后台任务
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 启动时把库里残留的排队/运行中会话标成失败。
     *
     * <p>内存任务表是空的：这些会话这次进程不会再执行，但它们也不会自己消失。
     * 留着 RUNNING 会让人误判成"还在跑"，所以宁可标记成 FAILED 并写清原因。
     *
     * <p>只清理"开始超过 {@code recon.agent.stale-run-minutes}（默认 10）分钟"的行：
     * Web 与 MCP 是两个进程共用一个库，看见刚创建的 RUNNING 行时，它很可能正在另一个进程里跑。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int marked = runs.failUnfinished(
                    "进程重启时该会话仍在排队/运行中，已标记为失败；需要的话重新提交（recon_draft_rule 或 recon_define_rule）",
                    Duration.ofMinutes(staleRunMinutes));
            if (marked > 0) {
                log.warn("启动清理：{} 条排队/运行中的 Agent 会话被标记为失败（上一次进程没有收尾）", marked);
            }
        } catch (RuntimeException e) {
            // 清理失败不该阻止应用启动：它只是让状态更诚实，不是主流程
            log.warn("启动清理残留会话失败（不影响启动）：{}", e.toString());
        }
    }

    /**
     * 提交一次会话。返回的 runId 立即可用于 {@link #find(long)} 轮询。
     *
     * @throws IllegalStateException 队列已满（并发会话超过容量）
     */
    public long submit(AgentGoal goal, String operator) {
        String who = operator == null || operator.isBlank() ? "agent" : operator;
        long runId = runs.startRun(goal.describe(), goal.trigger().name(), who);
        Task queued = new Task(runId, goal.describe(), Status.QUEUED, goal.ruleCode(), null, null,
                Instant.now(), null);
        tasks.put(runId, queued);
        try {
            pool.execute(() -> execute(runId, goal, who));
        } catch (RuntimeException e) {
            tasks.put(runId, queued.failed("提交失败：后台队列已满，稍后重试"));
            runs.finishRun(runId, AgentRunRepository.RunStatus.BLOCKED, goal.ruleCode(), null, null,
                    "后台队列已满，未能开始执行");
            throw new IllegalStateException("Agent 后台队列已满（并发会话过多），请稍后重试", e);
        }
        return runId;
    }

    /**
     * 给<b>已存在</b>的草稿排一次试算（{@code recon_define_rule} 走这条路）。
     *
     * <p>与 {@link #submit} 的区别：不起草、不规划，只用同一个队列跑
     * {@link RuleTrialRunner#run}。这样"手写规则"与"Agent 起草规则"共用同一条试算通道，
     * 也就共用同一套"队列满了直接拒绝"的背压。
     *
     * @return 会话号，可直接用于 {@code recon_trial_status(runId=...)}
     */
    public synchronized long submitTrial(String code, int version, String operator) {
        Long running = inFlightTrial(code, version);
        if (running != null) {
            // 同一版草稿重复提交：两次试算会互相覆盖 recon_rule.trial_*，还会白占一条队列。
            // 直接把手上的号码还回去，调用方拿它轮询即可。
            log.info("规则 {} v{} 已有在跑的试算 #{}，复用而不重复排队", code, version, running);
            return running;
        }
        String who = operator == null || operator.isBlank() ? "api" : operator;
        String goal = "对已定义的草稿跑一次影子试算：" + code + " v" + version;
        long runId = runs.startRun(goal, AgentGoal.GoalTrigger.API.name(), who);
        Task queued = new Task(runId, goal, Status.QUEUED, code, version, null, Instant.now(), null);
        tasks.put(runId, queued);
        try {
            pool.execute(() -> executeTrial(runId, queued, who));
        } catch (RuntimeException e) {
            tasks.put(runId, queued.failed("提交失败：后台队列已满，稍后重试"));
            runs.finishRun(runId, AgentRunRepository.RunStatus.BLOCKED, code, null,
                    Integer.valueOf(version), "后台队列已满，未能开始试算");
            throw new IllegalStateException("Agent 后台队列已满（并发试算过多），请稍后重试", e);
        }
        return runId;
    }

    private Long inFlightTrial(String code, int version) {
        for (Task task : tasks.values()) {
            if (task.status().inFlight()
                    && task.code() != null && task.code().equals(code)
                    && task.version() != null && task.version() == version) {
                return task.runId();
            }
        }
        return null;
    }

    private void executeTrial(long runId, Task queued, String who) {
        tasks.computeIfPresent(runId, (id, task) -> task.withStatus(Status.RUNNING));
        try {
            RuleTrialRunner.Result result = trialRunner.run(queued.code(), queued.version());
            io.github.dolow.reconlens.domain.TrialState state = result.state();
            boolean hardBlock = state.approval() == io.github.dolow.reconlens.domain.ApprovalMode.BLOCK_HARD;
            Status status = hardBlock ? Status.BLOCKED : Status.SUCCESS;
            String message = "试算完成：" + state.getClass().getSimpleName()
                    + "，可批性 " + state.approval() + "；生效仍需用户确认（recon_submit_approval + 确认码）";
            Task done = new Task(runId, queued.goal(), status, queued.code(), result.version(),
                    message, queued.submittedAt(), Instant.now());
            tasks.put(runId, done);
            draftIndex.put(key(done.code(), done.version()), runId);
            // 成功/硬拦也要落终态，否则库里的会话一直停在 RUNNING（启动清理会把它当遗留任务）
            runs.finishRun(runId,
                    hardBlock ? AgentRunRepository.RunStatus.BLOCKED : AgentRunRepository.RunStatus.SUCCESS,
                    done.code(), null, done.version(),
                    message.length() > 2000 ? message.substring(0, 2000) : message);
        } catch (RuntimeException e) {
            log.error("后台试算 #{} 异常终止：{}", runId, e.toString());
            String message = "试算异常：" + e;
            tasks.put(runId, new Task(runId, queued.goal(), Status.FAILED, queued.code(), queued.version(),
                    message, queued.submittedAt(), Instant.now()));
            // 库里的会话必须落终态：漏了这一步，试算会永远停在 RUNNING，查不到结论也看不出失败原因
            runs.finishRun(runId, AgentRunRepository.RunStatus.FAILED, queued.code(), null,
                    queued.version(), message.length() > 2000 ? message.substring(0, 2000) : message);
        }
    }

    private void execute(long runId, AgentGoal goal, String who) {
        tasks.computeIfPresent(runId, (id, task) -> task.withStatus(Status.RUNNING));
        try {
            AgentOrchestrator.AgentRunResult result = orchestrator.execute(runId, goal, who);
            Status status = switch (result.status()) {
                case SUCCESS -> Status.SUCCESS;
                case BLOCKED -> Status.BLOCKED;
                case FAILED, RUNNING -> Status.FAILED;
            };
            Task current = tasks.get(runId);
            Instant submitted = current == null ? Instant.now() : current.submittedAt();
            Task done = new Task(runId, goal.describe(), status, result.ruleCode(), result.draftVersion(),
                    result.message(), submitted, Instant.now());
            tasks.put(runId, done);
            if (done.code() != null && done.version() != null) {
                draftIndex.put(key(done.code(), done.version()), runId);
            }
        } catch (RuntimeException e) {
            // orchestrator 已经兜住自己的异常；走到这里说明连"兜住"都失败了（例如轨迹表不可写）
            log.error("后台会话 #{} 异常终止：{}", runId, e.toString());
            Task current = tasks.get(runId);
            tasks.put(runId, new Task(runId, goal.describe(), Status.FAILED, null, null,
                    "执行异常：" + e, current == null ? Instant.now() : current.submittedAt(), Instant.now()));
        }
    }

    public Optional<Task> find(long runId) {
        return Optional.ofNullable(tasks.get(runId));
    }

    /** 按 (code, version) 找还在跑的那次会话 —— 试算进行中时，库里还查不到结论。 */
    public Optional<Task> findByDraft(String code, int version) {
        Long runId = draftIndex.get(key(code, version));
        return runId == null ? Optional.empty() : find(runId);
    }

    public List<Task> snapshot() {
        return List.copyOf(tasks.values());
    }

    /** 关闭线程池。由容器的 destroy 回调调用。 */
    public void shutdown() {
        pool.shutdownNow();
    }

    private static String key(String code, int version) {
        return code + "#" + version;
    }
}
