package io.github.dolow.reconlens.rule;

/**
 * "给已存在的草稿排一次影子试算"的触发口。
 *
 * <p>为什么需要它而不是直接调 {@link RuleTrialRunner}：试算要跑两遍正式 + 一遍 canary，
 * 一分钟起步，而 MCP 客户端普遍 30 秒超时。同步跑的结果是"调用方拿到超时，服务端其实已经跑完" ——
 * 有结果，没人知道。所以这里只负责<b>排队并返回一个可轮询的号</b>，
 * 具体执行落在 Agent 侧的单线程有界队列里（{@code TrialTaskRegistry}）。
 *
 * <p>接口放在 {@code rule} 包、实现由 Agent 装配提供：{@code mcp} 包不直依赖 {@code agent} 包，
 * 与 {@link RuleTrialRunner} 同一个依赖倒置的理由。Agent 未启用时实现不存在，
 * 调用方退回同步路径（{@link RuleTrialRunner}）。
 */
public interface RuleTrialScheduler {

    /**
     * 排队一次试算。
     *
     * @return 会话号，可直接用于 {@code recon_trial_status(runId=...)} 轮询
     * @throws IllegalStateException 队列已满
     */
    long submit(String code, int version, String operator);
}
