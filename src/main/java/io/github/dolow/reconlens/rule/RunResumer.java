package io.github.dolow.reconlens.rule;

/**
 * 「把一个中断的 Agent 会话接着做完」的<b>窄入口</b>（依赖倒置）。
 *
 * <p>恢复本体挂在 agent 包、随 {@code recon.agent.enabled} 开关装配；但 HTTP 入口
 * {@code web.AgentRunController} 在 web 包，而 ArchUnit 边界禁止 web 直依赖 agent ——
 * 那条规则的用意是：agent 关闭时，web 层不该还留着一个指向不存在子系统的入口。
 *
 * <p>和 {@link RuleTrialRunner} 是同一个套路：接口定义在引擎侧（rule 包），实现由 agent 装配提供。
 * 这样 agent 关闭时控制器一起消失（它自己也带 {@code recon.agent.enabled=true} 条件），
 * 依赖方向仍然是 {@code web → rule}。
 */
public interface RunResumer {

    /**
     * 从最后一个持久化产物接着做。
     *
     * @param runId    会话号
     * @param operator 谁点的恢复（必填，会落进轨迹）
     * @return 恢复后的结论；不存在的会话抛 {@link IllegalArgumentException}，状态不允许抛
     *         {@link IllegalStateException}（由控制器翻译成 404 / 409）
     */
    Outcome resume(long runId, String operator);

    /** 恢复结论。刻意用 record 而不是复用 agent 的类型：web 不该看到 {@code AgentOrchestrator} 的内部结构。 */
    record Outcome(String status, String ruleCode, Integer draftVersion, Long jobId, String message) {
    }
}
