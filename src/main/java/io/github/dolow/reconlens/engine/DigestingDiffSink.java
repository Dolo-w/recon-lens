package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.Objects;
import java.util.Optional;

/**
 * 给任意 {@link DiffSink} 套一层摘要统计。不改变落库行为：每条差异先交给委托落库，
 * 落库成功后才记进摘要。
 *
 * <p>顺序是有意的：反过来先记后落，落库抛异常的记录会出现在摘要里 —— 摘要是给别人判断
 * "这份结果可不可信"用的，让它比库里多出几条，等于在一张本来用于自我校验的凭证上写假数。
 *
 * <p>超限时不产出摘要（见 {@link DiffSetDigest.Accumulator}），但<b>照常把差异交给委托</b> ——
 * 摘要失败绝不能变成"少写差异"。
 */
public final class DigestingDiffSink implements DiffSink {

    private final DiffSink delegate;
    private final DiffSetDigest.Accumulator accumulator;

    public DigestingDiffSink(DiffSink delegate, DiffSetDigest.Accumulator accumulator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
    }

    @Override
    public void accept(DiffRecord diff) {
        delegate.accept(diff);
        accumulator.accept(diff);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public long count() {
        return delegate.count();
    }

    /** 摘要；超过键数上限时为空（不是"部分摘要"）。 */
    public Optional<DiffSetDigest> digest() {
        return accumulator.build();
    }

    public boolean overflowed() {
        return accumulator.overflowed();
    }
}
