package io.github.dolow.reconlens.rule;

/**
 * "带确认码完成审批"的唯一入口。
 *
 * <p>它存在的理由不是解耦洁癖，而是把<b>审批权</b>和<b>转发权</b>在类型上分开：
 * MCP 桥（{@code mcp.AgentMcpTools}）只允许依赖这个窄接口，不允许引用
 * {@link RuleLifecycleService} 本体 —— 后者还包含 {@code approve/submit/reject} 这些
 * 不需要确认码的入口，误用一次就等于把"模型自批"的口子重新打开。
 *
 * <p>实现必须自己核销确认码（条件 UPDATE + 一次性），并且<b>不信任</b>调用方传来的
 * 试算结论：可批性只能从 {@code recon_rule.trial_*} 读。
 */
public interface ApprovalRelay {

    /**
     * 核销确认码并完成审批。
     *
     * @param decision         {@code APPROVE} / {@code REJECT}（大小写不敏感）
     * @param operator         转发人（进审计）
     * @param confirmationCode 人签发的一次性确认码；缺失、过期、已用、绑定不符都必须拒绝
     * @param note             可选备注
     */
    void confirmApproval(String code, int version, String decision, String operator,
                         String confirmationCode, String note);
}
