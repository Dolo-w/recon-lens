package io.github.dolow.reconlens.domain;

/** 试算结论。数据库里 {@code NULL} 表示"未跑"或"跳过"，不是这里的任何一个值。 */
public enum TrialVerdict {

    PASS,

    FAIL,

    /** 数据不足以支持放行也不足以判死：需要人看，或让 Agent 改写后重试。 */
    INCONCLUSIVE,

    /** 决定不跑（只允许非语义变更），必须带 {@code skip_reason}。 */
    SKIPPED

}
