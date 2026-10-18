package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ShardStatus;

import java.util.List;

/**
 * 单个分片的执行结果。
 *
 * @param digest   本分片差异集合摘要；分片失败或差异条数超过摘要上限时为 {@code null}
 *                 （超限的具体原因由 {@code ShardRunner} 打 WARN，不在这里重复表达）
 * @param digestOverflowed {@code digest == null} 的两种情形必须能区分：分片失败 vs 差异太多算不出摘要。
 *                 后者如果不再区分，两次都溢出的试算会得到两张同样的空表，T2 会把"无法判断"判成"通过"。
 * @param captured 试算时才非空：内存态的差异明细。<b>不能</b>用 {@code recon_diff} 里的数据替代 ——
 *                 那里 {@code biz_key} 截断到 128、value 截断到 512，两条前缀相同的键会算出同一个摘要，
 *                 于是 T2 会假通过。生产路径这里恒为空，不额外持有任何记录。
 */
public record ShardResult(int shardId, ShardStatus status, int attempts, MatchStats stats,
                          DiffSetDigest digest, boolean digestOverflowed, String errorMsg,
                          List<DiffRecord> captured) {

    public ShardResult {
        captured = captured == null ? List.of() : List.copyOf(captured);
    }

    /**
     * 不带"摘要溢出"标记的兼容构造：失败的试算用例与老调用点不需要关心这个字段。
     */
    public ShardResult(int shardId, ShardStatus status, int attempts, MatchStats stats,
                       DiffSetDigest digest, String errorMsg, List<DiffRecord> captured) {
        this(shardId, status, attempts, stats, digest, false, errorMsg, captured);
    }

    /** 不产出摘要时用它；失败分支统一走这里，避免每个调用点各写一个 {@code null}。 */
    public static ShardResult failed(int shardId, int attempts, String errorMsg) {
        return new ShardResult(shardId, ShardStatus.FAILED, attempts, MatchStats.empty(), null, false,
                errorMsg, List.of());
    }

    public boolean success() {
        return status == ShardStatus.SUCCESS;
    }
}
