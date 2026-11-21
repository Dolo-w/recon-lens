package io.github.dolow.reconlens.domain;

/**
 * 差异生命周期（豁免台账）：一条差异从「报出来」到「闭环」的处置状态。
 *
 * <p>设计立场：<b>差异本身不可改写</b> —— recon_diff 是对账结论的快照，谁都不能改；
 * 处置动作全部落在 append-only 的台账表（recon_diff_lifecycle）里，每一步谁做的、
 * 为什么做都有留痕。当前状态 = 台账里最新一条动作的目标状态，没有动作 = OPEN。
 *
 * <p>状态机：
 * <pre>
 *  OPEN --CLAIM--> CLAIMED --RELEASE--> OPEN
 *   |                |
 *   +--RESOLVE--> RESOLVED   +--RESOLVE--> RESOLVED
 *   +--EXEMPT---> EXEMPTED   +--EXEMPT---> EXEMPTED
 *                    EXEMPTED --UNEXEMPT--> OPEN   （豁免错了要能撤）
 * </pre>
 * 非法转移 fail-loud 报当前状态，绝不静默改状态 —— 财务台账改错一笔比多问一句贵得多。
 */
public final class DiffLifecycle {

    /** 处置状态。没有台账记录的差异 = OPEN（新报出来，没人碰过）。 */
    public enum Status {
        OPEN, CLAIMED, RESOLVED, EXEMPTED
    }

    /**
     * 处置动作（写进台账的动词）。
     * RESOLVE / EXEMPT 要求带 note（核销原因 / 豁免依据）—— 台账里一句"处理了"
     * 没有内容，等于没处理。
     */
    public enum Action {
        CLAIM, RELEASE, RESOLVE, EXEMPT, UNEXEMPT
    }

    /** 一条台账记录。{@code status} 是该动作执行后的状态（不是动作前）。 */
    public record DiffAction(
            Long id,
            long diffId,
            long jobId,
            Action action,
            Status status,
            String operator,
            String note,
            java.time.Instant createdAt) {
    }

    /** Action → 执行后的 Status。UNEXEMPT 撤销豁免回到 OPEN（豁免错了随时能撤，不设时限）。 */
    public static Status resultOf(Action action) {
        return switch (action) {
            case CLAIM -> Status.CLAIMED;
            case RELEASE, UNEXEMPT -> Status.OPEN;
            case RESOLVE -> Status.RESOLVED;
            case EXEMPT -> Status.EXEMPTED;
        };
    }

    private DiffLifecycle() {
    }
}
