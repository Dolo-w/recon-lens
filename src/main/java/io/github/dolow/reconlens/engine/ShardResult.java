package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ShardStatus;

import java.util.List;

/**
 * 单个分片的执行结果。
 *
 * @param digest   本分片差异集合摘要；分片失败或差异条数超过摘要上限时为 {@code null}
 *                 （超限的具体原因由 {@code ShardRunner} 打 WARN，不在这里重复表达）
 * @param captured 试算时才非空：内存态的差异明细。<b>不能</b>用 {@code recon_diff} 里的数据替代 ——
 *                 那里 {@code biz_key} 截断到 128、value 截断到 512，两条前缀相同的键会算出同一个摘要，
 *                 于是 T2 会假通过。生产路径这里恒为空，不额外持有任何记录。
 */
public record ShardResult(int shardId, ShardStatus status, int attempts, MatchStats stats,
                          DiffSetDigest digest, String errorMsg, List<DiffRecord> captured) {

    public ShardResult {
        captured = captured == null ? List.of() : List.copyOf(captured);
    }

    /** 不产出摘要时用它；失败分支统一走这里，避免每个调用点各写一个 {@code null}。 */
    public static ShardResult failed(int shardId, int attempts, String errorMsg) {
        return new ShardResult(shardId, ShardStatus.FAILED, attempts, MatchStats.empty(), null, errorMsg, List.of());
    }

    public boolean success() {
        return status == ShardStatus.SUCCESS;
    }
}
