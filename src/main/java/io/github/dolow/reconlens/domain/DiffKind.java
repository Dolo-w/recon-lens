package io.github.dolow.reconlens.domain;

/**
 * 差异的"语义种类"。规则 DSL 里的 diffType 是自由字符串（业务可自定义，如 AMOUNT_MISMATCH），
 * 引擎内部用 DiffKind 归类，归因说明模板只依赖它。
 */
public enum DiffKind {
    /** 左表（我方）没有、右表有 */
    MISSING_LEFT,
    /** 右表（对方）没有、左表有 */
    MISSING_RIGHT,
    /** 两边都有但字段值不一致 */
    VALUE_MISMATCH,
    /** 同一业务键出现多条 */
    DUPLICATE_KEY
}
