package io.github.dolow.reconlens.domain;

import java.time.Instant;

/** 一次对账任务。 */
public record ReconJob(
        long id,
        String ruleCode,
        String batchId,
        JobStatus status,
        int shardCount,
        int shardDone,
        long leftRows,
        long rightRows,
        long matchedRows,
        long diffRows,
        String remark,
        String errorMsg,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public ReconJob withStatus(JobStatus newStatus) {
        return new ReconJob(id, ruleCode, batchId, newStatus, shardCount, shardDone, leftRows, rightRows,
                matchedRows, diffRows, remark, errorMsg, createdAt, startedAt, finishedAt);
    }
}
