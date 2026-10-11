package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleRegistry;
import io.github.dolow.reconlens.dsl.RuleSpec;
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
import java.util.stream.Collectors;

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

    private final RuleRegistry rules;
    private final ReconRepository repository;
    private final ShardRunner shardRunner;
    private final ReconProperties properties;
    private final ExecutorService submitter = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "recon-submit");
        thread.setDaemon(true);
        return thread;
    });

    public ReconEngine(RuleRegistry rules, ReconRepository repository, ShardRunner shardRunner, ReconProperties properties) {
        this.rules = rules;
        this.repository = repository;
        this.shardRunner = shardRunner;
        this.properties = properties;
    }

    /** 同步跑完整个任务（CLI、启动演示、单测用）。 */
    public ReconJob run(String ruleCode, String batchId, String remark) {
        RuleSpec rule = rules.get(ruleCode);
        long jobId = prepare(rule, batchId, remark);
        execute(rule, jobId, ShardPlanner.plan(rule));
        return repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("任务记录丢失: " + jobId));
    }

    /** 异步：HTTP 接口立刻返回 jobId，避免大对账把请求线程占住。 */
    public long runAsync(String ruleCode, String batchId, String remark) {
        RuleSpec rule = rules.get(ruleCode);
        long jobId = prepare(rule, batchId, remark);
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

    /** 断点续跑：只重做失败分片；onlyFailed=false 时全部重做。 */
    public ReconJob retry(long jobId, boolean onlyFailed) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        if (!job.status().finished()) {
            throw new IllegalStateException("任务 #" + jobId + " 当前状态为 " + job.status() + "，执行中的任务不能重跑");
        }
        RuleSpec rule = rules.get(job.ruleCode());
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

    private long prepare(RuleSpec rule, String batchId, String remark) {
        int shardCount = rule.shard().count();
        long jobId = repository.createJob(rule.code(), batchId, remark, shardCount);
        repository.createShards(jobId, shardCount);
        repository.markJobRunning(jobId);
        log.info("创建对账任务 #{} 规则={} 批次={} 分片数={}", jobId, rule.code(), batchId, shardCount);
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
        String errorMsg = shards.stream()
                .filter(s -> s.status() == ShardStatus.FAILED && s.errorMsg() != null)
                .map(s -> "分片" + s.shardId() + ": " + s.errorMsg())
                .collect(Collectors.joining("; "));

        repository.finishJob(jobId, status, errorMsg.isEmpty() ? null : errorMsg);
        ReconJob finished = repository.findJob(jobId).orElseThrow();
        log.info("任务 #{} 结束: status={} 分片成功 {}/{} 左侧 {} 行 右侧 {} 行 差异 {} 条",
                jobId, status, success, shards.size(), finished.leftRows(), finished.rightRows(), finished.diffRows());
    }
}
