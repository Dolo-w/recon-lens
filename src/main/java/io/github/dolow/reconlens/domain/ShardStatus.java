package io.github.dolow.reconlens.domain;

/** 分片状态：只有 SUCCESS 的分片在重跑时会被跳过，这正是"断点续跑"的实现基础。 */
public enum ShardStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}
