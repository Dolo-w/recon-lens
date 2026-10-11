package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 对账任务编排：
 * <ol>
 *   <li>建任务 + 建分片记录（分片本身就是"断点"的粒度）</li>
 *   <li>按规则并行度开线程池，分片并行执行</li>
 *   <li>按分片结果汇总任务状态：全部成功 = SUCCESS，部分成功 = PARTIAL_FAILED，全失败 = FAILED</li>
 * </ol>
 * 重跑只重做非 SUCCESS 的分片，已经成功的分片不会重复计算。
 */
@Component
public class ReconEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconEngine.class);

    private final RuleCatalog catalog;
    private final ReconRepository repository;
    private final ShardRunner shardRunner;
    private final ReconProperties properties;
    private final ExecutorService submitter = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "recon-submit");
        thread.setDaemon(true);
        return thread;
    });

    public ReconEngine(RuleCatalog catalog, ReconRepository repository, ShardRunner shardRunner, ReconProperties properties) {
        this.catalog = catalog;
        this.repository = repository;
        this.shardRunner = shardRunner;
        this.properties = properties;
    }

    /** 同步跑完整个任务（CLI、启动演示、单测用）。 */
    public ReconJob run(String ruleCode, String batchId, String remark) {
        return run(catalog.resolve(ruleCode), batchId, remark);
    }

    /** 用请求里内联的规则跑（不落规则库）：使用方临时换维度、试算用。 */
    public ReconJob run(RuleCatalog.Resolved resolved, String batchId, String remark) {
        long jobId = prepare(resolved, batchId, remark);
        execute(resolved.spec(), jobId, ShardPlanner.plan(resolved.spec()));
        return repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("任务记录丢失: " + jobId));
    }

    /** 异步：HTTP 接口立刻返回 jobId，避免大对账把请求线程占住。 */
    public long runAsync(String ruleCode, String batchId, String remark) {
        return runAsync(catalog.resolve(ruleCode), batchId, remark);
    }

    public long runAsync(RuleCatalog.Resolved resolved, String batchId, String remark) {
        long jobId = prepare(resolved, batchId, remark);
        RuleSpec rule = resolved.spec();
        submitter.submit(() -> {
            try {
                execute(rule, jobId, ShardPlanner.plan(rule));
            } catch (RuntimeException e) {
                log.error("任务 #{} 执行异常", jobId, e);
                repository.finishJob(jobId, JobStatus.FAILED, Errors.describe(e));
            }
        });
        return jobId;
    }

    /** 断点续跑：只重做失败分片；onlyFailed=false 时全部重做。规则按任务创建时的快照执行。 */
    public ReconJob retry(long jobId, boolean onlyFailed) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        if (!job.status().finished()) {
            throw new IllegalStateException("任务 #" + jobId + " 当前状态为 " + job.status() + "，执行中的任务不能重跑");
        }
        RuleSpec rule = ruleForJob(job);
        List<Integer> shardIds = repository.shardIds(jobId, onlyFailed ? ShardStatus.FAILED : null);
        if (shardIds.isEmpty()) {
            log.info("任务 #{} 没有需要重跑的分片", jobId);
            return job;
        }
        List<ShardPlan> allPlans = ShardPlanner.plan(rule);
        List<ShardPlan> plans = shardIds.stream().map(allPlans::get).toList();

        repository.markJobForRetry(jobId);
        log.info("任务 #{} 重跑 {} 个分片: {}", jobId, plans.size(), shardIds);
        execute(rule, jobId, plans);
        return repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("任务记录丢失: " + jobId));
    }

    /**
     * 一个任务该用哪份规则：优先用任务创建时的快照（规则后来改过也不会让历史任务换语义），
     * 没有快照（老任务 / 内联任务）时退回归档版本，最后才用最新版本。重跑与差异归因共用这一份。
     */
    public RuleSpec ruleForJob(ReconJob job) {
        String snapshot = repository.ruleSnapshot(job.id()).orElse(null);
        if (snapshot != null && !snapshot.isBlank()) {
            return catalog.parseSnapshot(snapshot, "任务 #" + job.id() + " 的规则快照");
        }
        Integer version = repository.ruleVersion(job.id());
        if (version != null) {
            log.warn("任务 #{} 没有规则快照，按规则库 v{} 重跑", job.id(), version);
            return catalog.resolve(job.ruleCode(), version).spec();
        }
        log.warn("任务 #{} 既没有规则快照也没有版本号，按规则 {} 的最新版本重跑", job.id(), job.ruleCode());
        return catalog.resolve(job.ruleCode()).spec();
    }

    private long prepare(RuleCatalog.Resolved resolved, String batchId, String remark) {
        RuleSpec rule = resolved.spec();
        int shardCount = rule.shard().count();
        long jobId = repository.createJob(rule.code(), resolved.version(), resolved.snapshot(), batchId, remark, shardCount);
        repository.createShards(jobId, shardCount);
        repository.markJobRunning(jobId);
        log.info("创建对账任务 #{} 规则={} 版本={} 批次={} 分片数={}", jobId, rule.code(),
                resolved.version() == null ? "内联" : "v" + resolved.version(), batchId, shardCount);
        return jobId;
    }

    private void execute(RuleSpec rule, long jobId, List<ShardPlan> plans) {
        int parallelism = Math.max(1, Math.min(rule.shard().effectiveParallelism(),
                Math.max(1, properties.getEngine().getParallelism())));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "recon-shard");
            thread.setDaemon(true);
            return thread;
        });

        List<ShardResult> results = new ArrayList<>(plans.size());
        try {
            List<Future<ShardResult>> futures = new ArrayList<>(plans.size());
            for (ShardPlan plan : plans) {
                futures.add(pool.submit(() -> shardRunner.run(rule, jobId, plan)));
            }
            for (Future<ShardResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("对账任务 #" + jobId + " 被中断", e);
                } catch (ExecutionException e) {
                    results.add(new ShardResult(-1, ShardStatus.FAILED, 0, MatchStats.empty(), Errors.describe(e.getCause())));
                }
            }
        } finally {
            pool.shutdown();
        }

        List<ReconShard> shards = repository.listShards(jobId);
        long success = shards.stream().filter(s -> s.status() == ShardStatus.SUCCESS).count();
        long failed = shards.stream().filter(s -> s.status() == ShardStatus.FAILED).count();
        JobStatus status = failed == 0 ? JobStatus.SUCCESS : (success > 0 ? JobStatus.PARTIAL_FAILED : JobStatus.FAILED);
        // 只留前几条失败原因：分片多时整串会超过 error_msg 列宽，而且根因通常是同一条
        List<String> failureMessages = shards.stream()
                .filter(s -> s.status() == ShardStatus.FAILED && s.errorMsg() != null)
                .map(s -> "分片" + s.shardId() + ": " + s.errorMsg())
                .limit(3)
                .toList();
        String errorMsg = failureMessages.isEmpty() ? null
                : String.join("; ", failureMessages)
                        + (failed > failureMessages.size() ? "；共 " + failed + " 个分片失败" : "");

        repository.finishJob(jobId, status, errorMsg);
        ReconJob finished = repository.findJob(jobId).orElseThrow();
        log.info("任务 #{} 结束: status={} 分片成功 {}/{} 左侧 {} 行 右侧 {} 行 差异 {} 条",
                jobId, status, success, shards.size(), finished.leftRows(), finished.rightRows(), finished.diffRows());
    }
}
