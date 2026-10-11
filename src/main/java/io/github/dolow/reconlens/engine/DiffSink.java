package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

/** 差异出口。引擎只管产出差异，落库/落文件由实现决定，便于单测替换。 */
public interface DiffSink {

    void accept(DiffRecord diff);

    /** 把缓冲区刷出去。分片结束时必须调用一次。 */
    void flush();

    /** 已产出的差异条数（含已刷出的）。 */
    long count();
}
