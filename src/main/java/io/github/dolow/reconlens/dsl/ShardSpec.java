package io.github.dolow.reconlens.dsl;

/**
 * 分片规则。{shards} / {index} 是引擎替换的占位符，用户可以自由决定怎么切：
 * 取模、按业务键哈希、按时间区间、按 ID 区间都行。
 *
 * <p><b>rangeKey（性能档位）</b>：配置了它（如自增主键 {@code id}）就不再用 whereLeft/Right 模板，
 * 引擎探两侧该列的 MIN/MAX 后按数值均分成 count 段，生成 {@code col >= lo AND col < hi}（末段 {@code <= hi}）。
 * 好处：谓词是纯列与常量比较，<b>能走主键/索引</b>，而 {@code MOD(CRC32(...))} 这类表达式分片必然全表扫。
 * 两侧用同一列名 + 合并后的同一组边界，保证同一业务键永远落在同一分片（同源约束由构造保证，不靠人自觉）。
 * 代价：只支持数值列（探针直接返回数字），且段边界探测多两次 MIN/MAX（走索引，代价可忽略）。
 */
public record ShardSpec(int count, int parallelism, String whereLeft, String whereRight, String rangeKey) {

    /** 兼容构造器：绝大多数规则仍是模板分片，四参写法保持不变。 */
    public ShardSpec(int count, int parallelism, String whereLeft, String whereRight) {
        this(count, parallelism, whereLeft, whereRight, null);
    }

    public int effectiveParallelism() {
        return parallelism <= 0 ? count : parallelism;
    }
}
