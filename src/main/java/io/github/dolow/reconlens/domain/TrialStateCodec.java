package io.github.dolow.reconlens.domain;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * {@link TrialState} 与"落库四列"之间的<b>唯一</b>转换点。
 *
 * <p>为什么要有这个东西：sealed 类型解决了"构造不出来"，但数据库里只有扁平的
 * {@code trial_verdict / trial_coverage / trial_coverage_reason / skip_reason} 四列。
 * 一旦有两处代码各自把"列 → 状态"翻译一遍，那两处的规则就会漂移 ——
 * 比如一处把 {@code verdict=NULL} 当成 {@code NotRun}、另一处当成 {@code Skipped}，
 * 而这正是 §5.3 那张 5×5 矩阵最初填错 {@code SKIPPED × NONE} 的根因。
 *
 * <p>投影规则（只此一份）：
 * <table>
 *   <tr><th>状态</th><th>verdict</th><th>coverage</th><th>reason</th><th>skip_reason</th></tr>
 *   <tr><td>{@code NotRun}</td><td>NULL</td><td>NULL</td><td>NULL</td><td>NULL</td></tr>
 *   <tr><td>{@code Skipped}</td><td>SKIPPED</td><td>NULL</td><td>NULL</td><td>非空</td></tr>
 *   <tr><td>{@code Trialed}</td><td>枚举名</td><td>枚举名</td><td>逗号分隔（按严重度降序）</td><td>NULL</td></tr>
 * </table>
 */
public final class TrialStateCodec {

    private static final String SEPARATOR = ",";

    private TrialStateCodec() {
    }

    /** 落库投影。{@code reasons} 的顺序来自 {@link CoverageReason#severity()}，不是调用方给的顺序。 */
    public record Columns(String verdict, String coverage, String reasons, String skipReason) {
    }

    public static Columns toColumns(TrialState state) {
        if (state instanceof TrialState.NotRun) {
            return new Columns(null, null, null, null);
        }
        if (state instanceof TrialState.Skipped skipped) {
            return new Columns(TrialVerdict.SKIPPED.name(), null, null, skipped.skipReason());
        }
        TrialState.Trialed trialed = (TrialState.Trialed) state;
        String reasons = trialed.reasons().stream()
                .sorted(Comparator.comparingInt(CoverageReason::severity).reversed())
                .map(Enum::name)
                .reduce((a, b) -> a + SEPARATOR + b)
                .orElse(null);
        return new Columns(trialed.verdict().name(), trialed.coverage().name(), reasons, null);
    }

    /**
     * 列 → 状态。{@code verdict} 为空就是 {@link TrialState.NotRun}（"未跑"与"跳过"的处置相反，
     * 所以这两个绝不能共用一个空值）。
     */
    public static TrialState fromColumns(String verdict, String coverage, String reasons, String skipReason) {
        if (verdict == null || verdict.isBlank()) {
            if (skipReason != null && !skipReason.isBlank()) {
                // 数据不一致：有跳过理由却没有 SKIPPED。宁可报错，也不要静默当成 NotRun —— 那会让
                // "决定不验"重新变成"还没验"，审批页上又能点"等一等"混过去。
                throw new IllegalStateException("skip_reason 非空但 trial_verdict 为空：这两列必须一起写");
            }
            return new TrialState.NotRun();
        }
        TrialVerdict parsed = TrialVerdict.valueOf(verdict);
        if (parsed == TrialVerdict.SKIPPED) {
            return new TrialState.Skipped(skipReason);
        }
        if (coverage == null || coverage.isBlank()) {
            // 有判定结果却没有覆盖等级：这正是"字段有了、语义却散在各处"的那种脏数据。
            // 宁可报错也不猜一个默认值 —— 猜 FULL 会让一条没验过的规则看起来可以直接批。
            throw new IllegalStateException("trial_verdict=" + verdict + " 但 trial_coverage 为空：这两列必须一起写");
        }
        return new TrialState.Trialed(parsed, Coverage.valueOf(coverage), parseReasons(reasons));
    }

    private static List<CoverageReason> parseReasons(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(SEPARATOR))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(CoverageReason::valueOf)
                .toList();
    }
}
