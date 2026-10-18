package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.domain.CoverageReason;

/**
 * 一次试算的<b>全部输入</b>。它是 {@link TrialVerdictResolver} 的唯一参数 —— 判定逻辑刻意不做 I/O，
 * 这样"为什么这条规则被判成 PASS"可以只对着一个 record 复算，不需要重跑数据库。
 *
 * <p><b>用 {@code Boolean} 而不是 {@code boolean} 是有意的</b>：{@code null} 表示"这项没评估"，
 * 与"评了但不通过"是两件事。样本不达标时 T3/T4 的结论本来就不可靠（基线差异因为没扫到而"消失"），
 * 这时必须让它缺席，而不是默认成 {@code false} 把规则判死。
 *
 * @param materialized  是否真的物化出了样本表。{@code false} 时只能拿到 {@code FIELDS_ONLY}
 * @param blockage      阻断物化的原因；{@code materialized=true} 时必须为 {@code null}
 * @param canary        T0 自检结果
 * @param jobSucceeded  T1：试算任务是否 SUCCESS
 * @param idempotent    T2：同一份物化样本跑两次，摘要是否一致
 * @param coverageLossExplained T3：消失的差异是否逐条可机械解释
 * @param zeroDiffExplained     T4：差异归零是否可机械解释（基线 > 0 且新差异 = 0 时才评估）
 * @param diffGrowthExceeded    T6：差异增幅是否超阈值（<b>软判据</b>，只进审核重点区，不参与 verdict）
 * @param upstreamTruncated     上游 SQL 自带 {@code LIMIT} 一类的截断（见 {@link SqlTruncationDetector}）。
 *                              它<b>与 {@code materialized} 正交</b>：物化成功了，但物化到的只是原查询
 *                              被截断后的一角。所以它不是 {@code blockage}（那会降成 {@code FIELDS_ONLY}），
 *                              而是一个独立的覆盖降级理由
 */
public record TrialOutcome(boolean materialized,
                           CoverageReason blockage,
                           SampleStats sample,
                           Canary canary,
                           Boolean jobSucceeded,
                           Boolean idempotent,
                           Boolean coverageLossExplained,
                           Boolean zeroDiffExplained,
                           boolean diffGrowthExceeded,
                           boolean upstreamTruncated) {

    public TrialOutcome {
        if (materialized && blockage != null) {
            // 两个字段同时出现说明组装代码把"降级"和"成功"混着写了。宁可当场报错，
            // 也不要让一个自相矛盾的输入悄悄产出一个看着正常的判定。
            throw new IllegalArgumentException("已物化时不能再带阻断原因: " + blockage);
        }
        if (!materialized && blockage == null) {
            // 没物化就必须说明为什么 —— 否则 resolver 会产出 coverage=FIELDS_ONLY 且 reasons 为空，
            // 而 TrialState.Trialed 的构造器会拒绝这个组合（"降级了却不说原因"）。
            // 与其让它在下一层抛，不如在这里指出是组装代码漏了原因。
            throw new IllegalArgumentException("未物化时必须带上阻断原因（NO_DDL_PRIVILEGE / COLLATION_MISMATCH / ...）");
        }
    }

    /**
     * 样本信封。{@code shardCount} 只做展示，判定分母恒为 {@code baselineShardsWithData}。
     *
     * @param baselineShardsWithData 最近一次 PROD 任务里有数据的分片数；无基线时为 0
     * @param baselineDiffCount      基线任务的差异总数（T6 的分子/分母来源）
     * @param newDiffCount           本次试算的差异总数
     */
    public record SampleStats(int leftRows, int rightRows, int commonKeys,
                              int shardsWithData, int shardCount, int baselineShardsWithData,
                              int baselineDiffCount, int newDiffCount) {

        public static SampleStats empty() {
            return new SampleStats(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * T0 对照样本的结果。
     *
     * @param runnable 能不能自检（样本里还剩 ≥2 个"本来就干净"的键）
     * @param passed   注入的两处是否都被恰好检出
     * @param detail   人可读的说明：注入了哪个键、改了哪个字段、期望几条、实际几条
     */
    public record Canary(boolean runnable, boolean passed, String detail) {

        public static Canary skipped(String why) {
            return new Canary(false, false, why);
        }
    }
}
