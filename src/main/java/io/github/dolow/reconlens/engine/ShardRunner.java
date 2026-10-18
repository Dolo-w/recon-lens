package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 执行一个分片。关键约定：
 * <ol>
 *   <li>先删掉本分片上次的差异再重新对账 —— 重跑天然幂等，不需要"差异去重"的额外逻辑</li>
 *   <li>失败按 maxAttempts 重试，每次重试都是一次完整的"删+写"</li>
 *   <li>开始扫描前先校验规则字段在结果集里存在，把配置错误变成一条可读的失败原因，而不是跑一半炸掉</li>
 * </ol>
 */
@Component
public class ShardRunner {

    private static final Logger log = LoggerFactory.getLogger(ShardRunner.class);

    /** 差异攒批大小：兼顾 insert 吞吐与单分片内存占用 */
    private static final int DIFF_BATCH_SIZE = 500;

    private final ReconCursors cursors;
    private final ReconRepository repository;
    private final ReconProperties properties;

    public ShardRunner(ReconCursors cursors, ReconRepository repository, ReconProperties properties) {
        this.cursors = cursors;
        this.repository = repository;
        this.properties = properties;
    }

    public ShardResult run(RuleSpec rule, long jobId, ShardPlan plan) {
        return run(rule, jobId, plan, null);
    }

    /**
     * @param capture 非空时把每条差异留一份到内存（试算用）。生产路径传 {@code null}，
     *                此时行为与引入试算之前完全一致 —— 差异只落库、只算摘要，不额外持有。
     */
    public ShardResult run(RuleSpec rule, long jobId, ShardPlan plan, DiffCapture capture) {
        int maxAttempts = Math.max(1, rule.maxAttempts());
        String lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            repository.markShardRunning(jobId, plan.shardId(), attempt);
            try {
                repository.deleteShardDiffs(jobId, plan.shardId());
                try (RowCursor left = cursors.open(rule.left(), plan.whereLeft());
                     RowCursor right = cursors.open(rule.right(), plan.whereRight())) {
                    validateFields(rule, left.columns(), true);
                    validateFields(rule, right.columns(), false);
                    // 摘要与落库共用一条出口：算的就是"这次到底写出了什么"，不是另跑一遍
                    DigestingDiffSink sink = new DigestingDiffSink(
                            new BatchDiffSink(repository, DIFF_BATCH_SIZE),
                            DiffSetDigest.accumulator(rule.left().keyType(),
                                    properties.getEngine().getDiffDigestMaxKeys()));
                    DiffSink outlet = capture == null ? sink : new CapturingDiffSink(sink, capture);
                    MatchStats stats = new MergeMatcher(rule, jobId, plan.shardId()).match(left, right, outlet);
                    repository.markShardSuccess(jobId, plan.shardId(), stats);
                    DiffSetDigest digest = summarize(plan.shardId(), sink);
                    // digest == null 是"算不出摘要"（超上限），不是"没有差异"：必须带到上层，
                    // 否则两次都溢出的试算会拿两张空表做相等比较，把"无法判断"判成 PASS。
                    return new ShardResult(plan.shardId(), ShardStatus.SUCCESS, attempt, stats, digest,
                            digest == null, null,
                            capture == null ? java.util.List.of() : capture.snapshot());
                }
            } catch (Exception e) {
                lastError = Errors.describe(e);
                log.warn("分片 {} 第 {}/{} 次执行失败: {}", plan.shardId(), attempt, maxAttempts, lastError);
                sleepQuietly(attempt);
            }
        }

        repository.markShardFailed(jobId, plan.shardId(), lastError);
        return ShardResult.failed(plan.shardId(), maxAttempts, lastError);
    }

    /**
     * 每分片一行摘要日志：这是 M0 里"观察真实差异分布"的数据来源。
     * 超上限时打 WARN 并返回 {@code null} —— 没有摘要和"摘要只覆盖了一部分"必须能分清。
     */
    private DiffSetDigest summarize(int shardId, DigestingDiffSink sink) {
        Optional<DiffSetDigest> digest = sink.digest();
        if (digest.isEmpty()) {
            log.warn("分片 {} 差异 {} 条超过摘要上限 {}（recon.engine.diff-digest-max-keys），本次不产出摘要。"
                            + "这不是截断：截断出来的摘要看着正常，却会让同一份数据两次跑出不同的哈希",
                    shardId, sink.count(), properties.getEngine().getDiffDigestMaxKeys());
            return null;
        }
        DiffSetDigest value = digest.get();
        log.info("分片 {} 差异摘要 sha256={} 共 {} 条 byType={} byField={}",
                shardId, value.shortSha(), value.count(), value.byType(), value.byField());
        return value;
    }

    private void validateFields(RuleSpec rule, Set<String> columns, boolean leftSide) {
        String side = leftSide ? "左侧(" + rule.left().describe() + ")" : "右侧(" + rule.right().describe() + ")";
        String keyField = leftSide ? rule.left().keyField() : rule.right().keyField();
        String keyPath = leftSide ? "left.keyField" : "right.keyField";
        if (!columns.contains(keyField.toLowerCase(Locale.ROOT))) {
            throw new RuleException(keyPath + " 指向的字段 " + keyField + " 在" + side + "取数结果里不存在，实际列: " + columns);
        }
        for (FieldRule fieldRule : rule.compare()) {
            if (!columns.contains(fieldRule.field().toLowerCase(Locale.ROOT))) {
                throw new RuleException("compare." + fieldRule.field() + " 在" + side + "取数结果里不存在，实际列: " + columns);
            }
        }
    }

    private void sleepQuietly(int attempt) {
        try {
            // 轻量退避，避免下游抖动时立刻打满重试次数
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
