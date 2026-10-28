package io.github.dolow.reconlens.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 回放集的四个口径 + 一个通过率。
 *
 * <p>为什么口径要单独成类、还要单测：<b>量法本身就是结论的一部分</b>。
 * 上一版把"模型缺 jobId 所以先反问"算成了"没调工具"，工具选择率因此掉到 84% ——
 * 数字没错，是定义把"更谨慎"记成了"更差"。所以每个分母都显式写出来，并允许以后按需改。
 *
 * <p>口径：
 * <ul>
 *   <li>意图命中率 = 命中 / 有期望意图的题（多轮题不参与）；</li>
 *   <li>工具选择率 = 命中 / 有期望工具的题；"多调了别的工具"不算失败；</li>
 *   <li>平均轮数 = <b>多轮任务</b>的动作数均值（单轮题不进这个分母；混算会得出
 *       一个既不是单轮也不是多轮的第三个数）；</li>
 *   <li>成本 p95 = 95 分位（回放里只有 30~40 条，p95 实际是"最慢的第二三条"，
 *       写成 p95 是为了和以后的生产口径一致，而不是声称它有统计意义）。</li>
 * </ul>
 *
 * @param intentTotal 参与意图统计的题数（可能小于 {@code total}）
 * @param toolTotal   参与工具统计的题数
 */
public record EvalMetrics(int total, int intentTotal, double intentHitRate,
                          int toolTotal, double toolSelectionRate,
                          double averageTurns, int p95CostMs, int tokenMedian,
                          double passRate) {

    private static final EvalMetrics EMPTY =
            new EvalMetrics(0, 0, 0.0, 0, 0.0, 0.0, 0, 0, 0.0);

    public static EvalMetrics of(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return EMPTY;
        }
        int intentTotal = 0, intentHit = 0;
        int toolTotal = 0, toolHit = 0;
        int passed = 0;
        long multiTurnSum = 0;
        int multiCount = 0;
        List<Integer> costs = new ArrayList<>();
        List<Integer> tokens = new ArrayList<>();

        for (EvalCase c : cases) {
            if (c.expectedIntent() != null && !c.expectedIntent().isBlank()) {
                intentTotal++;
                if (c.expectedIntent().equals(c.actualIntent())) {
                    intentHit++;
                }
            }
            if (c.expectedTool() != null && !c.expectedTool().isBlank()) {
                toolTotal++;
                // 期望工具出现在实际调用里就算命中：多调一个 recall 之类不该判错
                if (calledTool(c.actualTool(), c.expectedTool())) {
                    toolHit++;
                }
            }
            if (c.multi()) {
                multiCount++;
                multiTurnSum += Math.max(1, c.turns());
            }
            if (c.costMs() > 0) {
                costs.add(c.costMs());
            }
            if (c.tokensEst() > 0) {
                tokens.add(c.tokensEst());
            }
            if (c.passed()) {
                passed++;
            }
        }

        return new EvalMetrics(cases.size(), intentTotal, percent(intentHit, intentTotal),
                toolTotal, percent(toolHit, toolTotal),
                round2(multiCount == 0 ? 0.0 : (double) multiTurnSum / multiCount),
                percentile95(costs), (int) Math.round(median(tokens)),
                percent(passed, cases.size()));
    }

    /** 是否达到内部 SLO（意图 ≥90%、工具 ≥85%、轮数 ≤3、token 中位 ≤6000）。 */
    public boolean meetsSlo() {
        return intentHitRate >= 90.0 && toolSelectionRate >= 85.0
                && averageTurns <= 3.0 && tokenMedian <= 6000;
    }

    /** 实际调用里有没有期望的工具。入参可能是单个名字，也可能是 {@code [a, b]} 形式。 */
    static boolean calledTool(String actual, String expected) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        for (String part : actual.replace("[", "").replace("]", "").split(",")) {
            if (expected.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    static double percent(int hit, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return round1(100.0 * hit / total);
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 95 分位：n 很小的时候取"最慢的那一两条"，不插值（插值会造出没发生过的耗时）。 */
    static int percentile95(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static double median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
