package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.List;

/** 差异出口。引擎只管产出差异，落库/落文件由实现决定，便于单测替换。 */
public interface DiffSink {

    /**
     * 接收一条差异及其原始行凭证。凭证可以为空（不产出凭证的场景），但实现必须把
     * 差异本身写出去 —— 凭证是加分项，差异是主结果，凭证链路出问题不许连累差异。
     */
    void accept(DiffRecord diff, List<DiffEvidence> evidence);

    /** 无凭证场景的便捷入口。 */
    default void accept(DiffRecord diff) {
        accept(diff, List.of());
    }

    /** 把缓冲区刷出去。分片结束时必须调用一次。 */
    void flush();

    /** 已产出的差异条数（含已刷出的）。 */
    long count();
}
