package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobKind;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.domain.TrialPhase;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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
        long jobId = prepare(resolved, batchId, remark, JobKind.PROD, null);
        // 生产路径不捕获差异明细：那会把整个任务的差异按批堆在内存里，只为了一个没人看的列表
        execute(resolved.spec(), jobId, planFor(resolved.spec()), false);
        return repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("任务记录丢失: " + jobId));
    }

    /**
     * 带轨迹的同步执行：<b>把每个分片的差异明细留在内存里</b>，供闸门 2 做 T0/T2/T3/T4 判定。
     *
     * <p>为什么不从 {@code recon_diff} 读回来：落库时 {@code biz_key} 截断到 128、value 截断到 512，
     * 两条前缀相同的业务键会得到同一个摘要 —— T2 会因此<b>假通过</b>。假通过比漏判更糟，
     * 它给的是"确定"的假象。
     *
     * @param kind  {@code PROD} 或 {@code TRIAL}；试算任务不能进生产基线（巡检/任务列表按它过滤）
     * @param phase {@code TRIAL} / {@code CANARY}，仅 {@code kind=TRIAL} 时有意义
     */
    public RunTrace runTraced(RuleCatalog.Resolved resolved, String batchId, String remark,
                              JobKind kind, TrialPhase phase) {
        long jobId = prepare(resolved, batchId, remark, kind, phase);
        RuleSpec rule = resolved.spec();
        List<ShardResult> results = execute(rule, jobId, planFor(rule), true);
        ReconJob job = repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("任务记录丢失: " + jobId));
        return new RunTrace(job, results);
    }

    /**
     * 一次执行的完整轨迹。
     *
     * @param results 逐分片结果，含摘要与（试算时的）差异明细
     */
    public record RunTrace(ReconJob job, List<ShardResult> results) {

        /** 全部差异明细；生产路径（未捕获）时为空。 */
        public List<DiffRecord> capturedDiffs() {
            return results.stream().flatMap(result -> result.captured().stream()).toList();
        }

        /** T2 的比对对象：{@code shardId → sha256}。分片计划是确定性的，所以这张表等价于全局哈希。 */
        public Map<Integer, String> digestsByShard() {
            Map<Integer, String> digests = new TreeMap<>();
            for (ShardResult result : results) {
                if (result.digest() != null) {
                    digests.put(result.shardId(), result.digest().sha256());
                }
            }
            return digests;
        }

        /**
         * 是否有分片因为差异条数超过摘要上限而拿不到摘要。
         *
         * <p>这类分片在 {@link #digestsByShard()} 里是<b>缺席</b>的，两张缺席的表彼此相等 ——
         * 不单独暴露这个信号，T2 就会把"两次都算不出"读成"两次一致"。
         */
        public boolean digestsOverflowed() {
            return results.stream().anyMatch(ShardResult::digestOverflowed);
        }
    }

    /** 异步：HTTP 接口立刻返回 jobId，避免大对账把请求线程占住。 */
    public long runAsync(String ruleCode, String batchId, String remark) {
        return runAsync(catalog.resolve(ruleCode), batchId, remark);
    }

    public long runAsync(RuleCatalog.Resolved resolved, String batchId, String remark) {
        long jobId = prepare(resolved, batchId, remark, JobKind.PROD, null);
        RuleSpec rule = resolved.spec();
        submitter.submit(() -> {
            try {
                execute(rule, jobId, planFor(rule), false);
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
        List<ShardPlan> allPlans = planFor(rule);
        List<ShardPlan> plans = shardIds.stream().map(allPlans::get).toList();

        repository.markJobForRetry(jobId);
        log.info("任务 #{} 重跑 {} 个分片: {}", jobId, plans.size(), shardIds);
        execute(rule, jobId, plans, false);
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

    private long prepare(RuleCatalog.Resolved resolved, String batchId, String remark,
                         JobKind kind, TrialPhase phase) {
        RuleSpec rule = resolved.spec();
        int shardCount = rule.shard().count();
        long jobId = repository.createJob(rule.code(), resolved.version(), resolved.snapshot(), batchId, remark,
                shardCount, kind, phase);
        repository.createShards(jobId, shardCount);
        repository.markJobRunning(jobId);
        log.info("创建对账任务 #{} 规则={} 版本={} 批次={} 分片数={} kind={}{}", jobId, rule.code(),
                resolved.version() == null ? "内联" : "v" + resolved.version(), batchId, shardCount, kind,
                phase == null ? "" : " phase=" + phase);
        return jobId;
    }

    /**
     * 生成分片计划：模板分片直接渲染；{@code shard.rangeKey} 分片先探两侧列的 MIN/MAX 再均分。
     * 探测失败（列不是数值 / 表为空 / 权限不足）一律 fail-loud —— 静默退化成模板分片
     * 等于把"能走索引"的承诺偷偷换成"全表扫"，用户还以为拿到了快。
     */
    private List<ShardPlan> planFor(RuleSpec rule) {
        if (rule.shard().rangeKey() == null || rule.shard().rangeKey().isBlank()) {
            return ShardPlanner.plan(rule);
        }
        long[][] bounds = repository.rangeBounds(rule.left().table(), rule.right().table(),
                rule.shard().rangeKey(), rule.shard().count());
        log.info("范围分片：rangeKey={}，边界已探测，{} 段", rule.shard().rangeKey(), bounds.length);
        return ShardPlanner.plan(rule, bounds);
    }

    private List<ShardResult> execute(RuleSpec rule, long jobId, List<ShardPlan> plans, boolean captureDiffs) {
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
                // 每个分片一个 capture：共享一个 list 会逼着加锁，而试算的并行度和生产一样
                DiffCapture capture = captureDiffs ? new DiffCapture() : null;
                futures.add(pool.submit(() -> shardRunner.run(rule, jobId, plan, capture)));
            }
            for (Future<ShardResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("对账任务 #" + jobId + " 被中断", e);
                } catch (ExecutionException e) {
                    results.add(ShardResult.failed(-1, 0, Errors.describe(e.getCause())));
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
        logDiffSummary(jobId, results);
        return results;
    }

    /**
     * 任务级差异分布：把各分片摘要的计数合起来，给人一眼看出"这次差异大概是什么形态"。
     * <p>注意这里<b>不</b>重算一个任务级 sha256 —— 集合哈希要求把全部业务键放进一个集合里排序，
     * 跨分片合并等于把内存占用从"单分片"抬到"整个任务"。要对比两次运行是否一致，
     * 比的是 {@code shardId → sha256} 这张表（分片计划是确定性的，同一份数据分片不变）。
     */
    private void logDiffSummary(long jobId, List<ShardResult> results) {
        List<DiffSetDigest> digests = results.stream()
                .map(ShardResult::digest)
                .filter(Objects::nonNull)
                .toList();
        if (digests.isEmpty()) {
            return;
        }
        Map<String, Long> byType = new TreeMap<>();
        Map<String, Long> byField = new TreeMap<>();
        for (DiffSetDigest digest : digests) {
            digest.byType().forEach((key, value) -> byType.merge(key, value, Long::sum));
            digest.byField().forEach((key, value) -> byField.merge(key, value, Long::sum));
        }
        long total = digests.stream().mapToLong(DiffSetDigest::count).sum();
        log.info("任务 #{} 差异性摘要: 共 {} 条（{}/{} 个分片有摘要） byType={} byField={}",
                jobId, total, digests.size(), results.size(), byType, byField);
    }
}
