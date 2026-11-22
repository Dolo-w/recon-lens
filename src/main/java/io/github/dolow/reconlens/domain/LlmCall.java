package io.github.dolow.reconlens.domain;

/**
 * 一次大模型调用的账本记录（清单 P0-2「成本归因」）。
 *
 * <p><b>为什么要有这张表</b>：在它之前，{@code recon_agent_run.llm_tokens} 只记总数 ——
 * 知道"花了 12 万 token"，但不知道是路由花的还是归因花的、是哪个模型、失败了几次、
 * 折成钱是多少。生产环境没法按功能/按客户算账，也没法解释"这个月成本为什么涨了"。
 *
 * <p><b>单价快照</b>：{@code priceInputPerMtok / priceOutputPerMtok} 是<b>落库那一刻</b>的配置值，
 * 不是报表时现算。改价只影响之后的记录，历史账不会被篡改。
 *
 * <p><b>成本单位是 micros（元的百万分之一）</b>：单价按"元/百万 token"计，所以
 * {@code 1 token × 1 元/Mtok = 1 micro 元}，数值上直接相乘即可，全整数运算无浮点累计误差。
 *
 * @param outcome 结果分类：{@code OK} 成功 / {@code FAILED} 最终失败 / {@code RETRIED} 重试后成功 /
 *                {@code UNAVAILABLE} 未配置 key 或开关关闭 / {@code FALLBACK} 备用 provider 成功
 */
public record LlmCall(
        Long id,
        String purpose,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        int attempt,
        String outcome,
        String errorKind,
        long costMicros,
        double priceInputPerMtok,
        double priceOutputPerMtok,
        String calledAt) {

    /** 本次调用折算成钱（元），用于报表展示。 */
    public double costYuan() {
        return costMicros / 1_000_000.0;
    }

    /** 成本计算：整数 micros，调用方不必关心单位换算。 */
    public static long costMicros(int promptTokens, int completionTokens,
                                  double priceInputPerMtok, double priceOutputPerMtok) {
        double micros = promptTokens * priceInputPerMtok + completionTokens * priceOutputPerMtok;
        return Math.round(micros);
    }
}
