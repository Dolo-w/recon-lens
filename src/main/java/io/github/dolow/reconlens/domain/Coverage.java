package io.github.dolow.reconlens.domain;

/**
 * 试算覆盖等级。<b>它是推导值，不是调用方填的字段</b>，而且只对"跑过试算"的场合成立 ——
 * {@code SKIPPED} 与"未跑"压根没有判定结果，因此没有覆盖等级（落库为 {@code NULL}）。
 */
public enum Coverage {

    /** T5-a/b/c 全过：样本规模、键窗口、分片覆盖都达标。 */
    FULL,

    /** 验了一部分：能支持放行，但样本不完整，必须签字。 */
    SAMPLE_PARTIAL,

    /** 只做了字段级校验 + 单次只读跑，没有物化样本。 */
    FIELDS_ONLY,

    /** 什么也没验到（闸门自己坏了 / canary 失败）。 */
    NONE
}
