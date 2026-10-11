package io.github.dolow.reconlens.domain;

import java.time.Instant;

/** 分片执行记录。断点续跑就是"只重跑 status != SUCCESS 的分片"。 */
public record ReconShard(
        long jobId,
        int shardId,
        ShardStatus status,
        int attempts,
        long scannedLeft,
        long scannedRight,
        long matchedRows,
        long diffRows,
        String errorMsg,
        Instant startedAt,
        Instant finishedAt) {
}
