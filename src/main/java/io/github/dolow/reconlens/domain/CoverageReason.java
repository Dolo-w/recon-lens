package io.github.dolow.reconlens.domain;

import java.util.Comparator;
import java.util.List;

/**
 * "为什么没验到"。<b>优先级只在这里定义一次</b>（{@code severity}），表格与排序都是它的投影。
 *
 * <p>第六轮 11.1-② 的教训：v0.6 在"表行序"和"§7.2 的严重度降序"里写了两个相反的次序，
 * 同一份 {@code reasons} 用两个函数会算出不同主理由。所以优先级必须是枚举上的字段，
 * 任何地方想排序都调 {@link #main(List)}，不允许再写一份比较逻辑。
 */
public enum CoverageReason {

    /** canary 检出数与期望不符 → 闸门自己坏了，整个 gate-version 不可信。 */
    CANARY_FAILED(60),

    /** 闸门不可信期间不验（不复用 CANARY_FAILED：两个码必须能互相区分）。 */
    GATE_UNTRUSTED(50),

    /** 未物化（无 DDL 权限 / CTAS 失败），只做了字段级校验 + 单次只读跑。 */
    NO_DDL_PRIVILEGE(40),

    /** collation 不一致且半连接也失败：结构性，最难靠调参改善。 */
    COLLATION_MISMATCH(35),

    /** 规则 / 上游 SQL 自带截断（{@code LIMIT}）。 */
    UPSTREAM_LIMIT(30),

    /** T2 的差异摘要超过键数上限：两次都拿不到摘要，幂等性<b>无法判断</b>（不是"通过"）。 */
    DIGEST_OVERFLOW(28),

    /** 物化成功但 T5-c 分片覆盖未过：取窗偏了，可重取改善。 */
    SHARD_COVERAGE(25),

    /** canary 跑不了（样本几乎全有差异，无干净键）：规则本身可能写错。 */
    CANARY_NO_CLEAN_ROW(20),

    /** 物化成功但 T5-a/T5-b 未过：最容易修。 */
    SAMPLE_TOO_SMALL(10);

    private final int severity;

    CoverageReason(int severity) {
        this.severity = severity;
    }

    /** 数值越大越严重。唯一的优先级出处。 */
    public int severity() {
        return severity;
    }

    /** 主理由 = 严重度最大者；并列时按枚举声明顺序取前者（稳定，不依赖 Map 迭代顺序）。 */
    public static CoverageReason main(List<CoverageReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            throw new IllegalArgumentException("没有理由就没有主理由；未跑 / 跳过不该走这里");
        }
        return reasons.stream()
                .max(Comparator.comparingInt(CoverageReason::severity)
                        .thenComparing(Enum::ordinal, Comparator.reverseOrder()))
                .orElseThrow();
    }
}
