package io.github.dolow.reconlens.dsl;

/**
 * 分片规则。{shards} / {index} 是引擎替换的占位符，用户可以自由决定怎么切：
 * 取模、按业务键哈希、按时间区间、按 ID 区间都行。
 */
public record ShardSpec(int count, int parallelism, String whereLeft, String whereRight) {

    public int effectiveParallelism() {
        return parallelism <= 0 ? count : parallelism;
    }
}
