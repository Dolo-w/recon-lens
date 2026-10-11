package io.github.dolow.reconlens.engine;

/** 一个分片的取数条件（占位符已经替换完成）。 */
public record ShardPlan(int shardId, String whereLeft, String whereRight) {
}
