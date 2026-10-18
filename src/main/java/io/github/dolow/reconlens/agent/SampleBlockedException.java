package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.domain.CoverageReason;

/**
 * 采样阶段就验不下去时抛它。<b>它不是错误，是一种结论</b>：闸门要把它翻译成"降级 + 理由码"，
 * 而不是让异常冒到接口上变成 500。
 *
 * <p>所以它继承 {@link RuntimeException} 却不带堆栈（{@link #fillInStackTrace()} 直接返回自身）——
 * 这是控制流，不是 bug；带着一堆无意义的栈帧反而会让真正的异常读起来更费劲。
 */
public class SampleBlockedException extends RuntimeException {

    private final CoverageReason reason;

    public SampleBlockedException(CoverageReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SampleBlockedException(CoverageReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public CoverageReason reason() {
        return reason;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
