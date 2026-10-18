package io.github.dolow.reconlens.domain;

/**
 * 审批接口唯一该读的东西。对应 {@code verdict × coverage} 矩阵里"能构造出来"的 5 种格子；
 * 矩阵里标 {@code ❌ 非法} 的 16 格没有对应值 —— 它们压根构造不出来。
 *
 * <p>关键区分（第七轮 12.1）：<b>能构造 ≠ 能批</b>。
 * {@link #BLOCK_HARD} 是"真实存在但谁也不能批准"，把它折进"非法"会让闸门一坏就变成服务不可用。
 */
public enum ApprovalMode {

    /**
     * ✅ 试算验全了，<b>不需要额外签字</b>。
     *
     * <p>旧名 {@code AUTO_APPROVE} 会被读成"系统自动批准"——宿主大模型正是这么理解的（实测照此自批过）。
     * 它只表示"这一格不需要人再补一份签字说明"，<b>批准动作仍然只能由人发起</b>；
     * 改名就是把"可批性等级"和"谁来批"拆开：等级只回答"还差不差签字"。
     */
    NO_SIGNOFF_REQUIRED,

    /** ⚠️ 可批，但必须留下签字 + 理由。 */
    NEED_SIGN,

    /** ⛔ 拦，但 {@code HUMAN} 可凭"接受降级验证"签字放行（只用于"没验成"）。 */
    BLOCK_SIGNABLE,

    /** 🚫 拦，任何身份不可签字（结论已明确不合格，或上游自己坏了）。 */
    BLOCK_HARD,

    /** ⏳ 还没跑。 */
    NOT_APPROVABLE

}
