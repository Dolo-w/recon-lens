package io.github.dolow.reconlens.domain;

/**
 * 试算任务的子阶段。{@code kind=TRIAL} 只说明"这不是生产对账"，
 * 还要能分清"正式试算"与"canary 自检"—— 后者的结果<b>不得</b>参与 T1–T7 与差异摘要，
 * 否则一份被人为改坏的数据会进判定。
 */
public enum TrialPhase {

    /** 正式试算。 */
    TRIAL,

    /** T0 对照样本（注入式自检）。 */
    CANARY
}
