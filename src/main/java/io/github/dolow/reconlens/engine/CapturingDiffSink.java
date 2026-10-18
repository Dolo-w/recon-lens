package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

/**
 * 把差异同时交给下游（落库 + 摘要）并留一份到 {@link DiffCapture}。
 *
 * <p>放在链路最外层是有意的：只有<b>成功交出去</b>的差异才该进捕获得结果。
 * 若放在最内层，一条落库失败、被上层重试掉的记录也会进判定输入 ——
 * 而试算的结论正是靠这批记录算出来的。
 */
final class CapturingDiffSink implements DiffSink {

    private final DiffSink delegate;
    private final DiffCapture capture;

    CapturingDiffSink(DiffSink delegate, DiffCapture capture) {
        this.delegate = delegate;
        this.capture = capture;
    }

    @Override
    public void accept(DiffRecord diff) {
        delegate.accept(diff);
        capture.add(diff);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public long count() {
        return delegate.count();
    }
}
