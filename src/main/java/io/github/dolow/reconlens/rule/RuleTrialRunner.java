package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.domain.TrialState;

/**
 * 影子试算的<b>触发口</b>（依赖倒置）。
 *
 * <p>试算本体 {@code ShadowTrialService} 挂在 agent 包、随 {@code recon.agent.enabled} 开关装配；
 * 但 MCP 的 {@code recon_define_rule}（mcp 包）落草稿后要自动触发试算，而 ArchUnit 边界
 * 禁止 mcp 直依赖 agent（只有 {@code mcp.AgentMcpTools} 这个注册桥例外）。
 * 所以把"跑一次试算"抽象成本接口：定义在 rule 包，实现由 agent 装配提供 ——
 * agent 关闭时没有实现，{@code recon_define_rule} 落草稿但不试算，话术里明确说明。
 */
public interface RuleTrialRunner {

    /** 对规则库中某一版跑影子试算，结论写回 {@code recon_rule.trial_*}。 */
    Result run(String code, int version);

    /** 试算结果：状态对象是 domain 类型，mcp 拿它读 {@code approval()} 即可，不接触 agent 类型。 */
    record Result(int version, TrialState state) {
    }
}
