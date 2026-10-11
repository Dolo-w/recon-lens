package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
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

    public ShardRunner(ReconCursors cursors, ReconRepository repository) {
        this.cursors = cursors;
        this.repository = repository;
    }

    public ShardResult run(RuleSpec rule, long jobId, ShardPlan plan) {
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
                    BatchDiffSink sink = new BatchDiffSink(repository, DIFF_BATCH_SIZE);
                    MatchStats stats = new MergeMatcher(rule, jobId, plan.shardId()).match(left, right, sink);
                    repository.markShardSuccess(jobId, plan.shardId(), stats);
                    return new ShardResult(plan.shardId(), ShardStatus.SUCCESS, attempt, stats, null);
                }
            } catch (Exception e) {
                lastError = Errors.describe(e);
                log.warn("分片 {} 第 {}/{} 次执行失败: {}", plan.shardId(), attempt, maxAttempts, lastError);
                sleepQuietly(attempt);
            }
        }

        repository.markShardFailed(jobId, plan.shardId(), lastError);
        return new ShardResult(plan.shardId(), ShardStatus.FAILED, maxAttempts, MatchStats.empty(), lastError);
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
