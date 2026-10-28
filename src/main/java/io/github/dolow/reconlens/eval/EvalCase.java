package io.github.dolow.reconlens.eval;

/**
 * 一条评测记录：一道题、一个期望、一个实际结果。
 *
 * <p>字段刻意与 {@code recon_agent_step} 对齐（{@code turns / costMs / tokensEst}）：
 * 今天这些行来自回放脚本，真上线后同一套聚合函数可以改成读行为表 —— 口径不换，只换数据源。
 *
 * <p>{@code multi} 是必要的：金标里 30 条单轮题和 8 个多轮任务混在一起，
 * "平均轮数"这个 SLO 说的是<b>多轮任务</b>的平均步数，把所有题一起平均会得出
 * 一个既不是单轮也不是多轮的第三个数（实测差到 1.24 vs 2.13）。
 *
 * @param no      题号（同一批次内唯一，用来对齐 diff）
 * @param intent  期望意图；多轮任务没有单一意图，用 {@code null}
 * @param tool    期望工具；不需要工具的题用 {@code null}
 * @param turns   这条题花了几个动作（单轮题是 1）
 * @param multi   是否多轮任务（决定它进不进"平均轮数"的分母）
 * @param passed  判定（各题自带判定器：意图命中 / 末轮文本匹配）
 */
public record EvalCase(int no, String question,
                       String expectedIntent, String actualIntent,
                       String expectedTool, String actualTool,
                       int turns, boolean multi, int costMs, int tokensEst,
                       boolean passed) {
}
