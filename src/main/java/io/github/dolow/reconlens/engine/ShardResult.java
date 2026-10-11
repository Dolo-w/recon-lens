package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.ShardStatus;

/** 单个分片的执行结果。 */
public record ShardResult(int shardId, ShardStatus status, int attempts, MatchStats stats, String errorMsg) {

    public boolean success() {
        return status == ShardStatus.SUCCESS;
    }
}
