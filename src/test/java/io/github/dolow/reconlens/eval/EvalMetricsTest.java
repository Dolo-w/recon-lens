package io.github.dolow.reconlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口径函数的单测。
 *
 * <p>为什么口径也要测：数字对不上时，99% 的时间花在确认"到底是模型变差了，还是分母变了"。
 * 这批用例里"平均轮数只算多轮题"那一条就是被实测逼出来的 —— 混算得出 1.24，
 * 而金标 SLO 说的 2.13 是多轮任务的平均步数。
 */
class EvalMetricsTest {

    private static EvalCase single(int no, String want, String got, String wantTool, String gotTool, boolean passed) {
        return new EvalCase(no, "q" + no, want, got, wantTool, gotTool, 1, false, 0, 0, passed);
    }

    private static EvalCase multi(int no, int turns, boolean passed) {
        return new EvalCase(no, "m" + no, null, null, null, null, turns, true, 0, 0, passed);
    }

    @Test
    void 空集不除零也不装出数字() {
        EvalMetrics m = EvalMetrics.of(List.of());
        assertThat(m.total()).isZero();
        assertThat(m.intentHitRate()).isZero();
        assertThat(m.toolSelectionRate()).isZero();
        assertThat(m.p95CostMs()).isZero();
        assertThat(m.meetsSlo()).isFalse();
    }

    @Test
    void 意图命中的分母只算有期望意图的题() {
        EvalMetrics m = EvalMetrics.of(List.of(
                single(1, "RUN", "RUN", null, null, true),
                single(2, "LIST_DIFFS", "HELP", null, null, false),
                multi(3, 3, true)));
        assertThat(m.intentTotal()).isEqualTo(2);
        assertThat(m.intentHitRate()).isEqualTo(50.0);
        assertThat(m.total()).isEqualTo(3);
    }

    @Test
    void 平均轮数只算多轮任务() {
        // 如果 30 条单轮题也进分母，平均数会被拉低到"既不是单轮也不是多轮"的第三个数
        EvalMetrics m = EvalMetrics.of(List.of(
                single(1, "RUN", "RUN", null, null, true),
                single(2, "HELP", "HELP", null, null, true),
                multi(3, 2, true),
                multi(4, 3, true)));
        assertThat(m.averageTurns()).isEqualTo(2.5);
        assertThat(m.total()).isEqualTo(4);
    }

    @Test
    void 没有多轮题时平均轮数为零而不是除以全部() {
        EvalMetrics m = EvalMetrics.of(List.of(single(1, "RUN", "RUN", null, null, true)));
        assertThat(m.averageTurns()).isZero();
    }

    @Test
    void 工具命中允许同时调了别的工具() {
        EvalMetrics m = EvalMetrics.of(List.of(
                single(1, "LIST_DIFFS", "LIST_DIFFS", "recon_list_diffs", "[recon_list_diffs, recon_recall]", true),
                single(2, "RETRY", "RETRY", "recon_retry", "", false)));
        assertThat(m.toolTotal()).isEqualTo(2);
        assertThat(m.toolSelectionRate()).isEqualTo(50.0);
    }

    @Test
    void p95取最慢的那几条而不是插值出没发生过的耗时() {
        List<EvalCase> cases = List.of(
                new EvalCase(1, "a", null, null, null, null, 1, false, 10, 100, true),
                new EvalCase(2, "b", null, null, null, null, 1, false, 20, 100, true),
                new EvalCase(3, "c", null, null, null, null, 1, false, 30, 100, true),
                new EvalCase(4, "d", null, null, null, null, 1, false, 40, 100, true),
                new EvalCase(5, "e", null, null, null, null, 1, false, 1000, 100, true));
        EvalMetrics m = EvalMetrics.of(cases);
        // ceil(5*0.95)-1 = 4 → 下标 4，即 1000（而不是 40 与 1000 之间插值）
        assertThat(m.p95CostMs()).isEqualTo(1000);
        assertThat(m.tokenMedian()).isEqualTo(100);
    }

    @Test
    void 成本为零的题不进分位_回放里没测成本时不该把p95拉成0() {
        EvalMetrics m = EvalMetrics.of(List.of(
                new EvalCase(1, "a", null, null, null, null, 1, false, 0, 0, true),
                new EvalCase(2, "b", null, null, null, null, 1, false, 0, 0, true),
                new EvalCase(3, "c", null, null, null, null, 1, false, 800, 50, true)));
        assertThat(m.p95CostMs()).isEqualTo(800);
        assertThat(m.tokenMedian()).isEqualTo(50);
    }

    @Test
    void 通过率() {
        EvalMetrics m = EvalMetrics.of(List.of(
                multi(1, 1, true),
                multi(2, 2, true),
                multi(3, 3, false)));
        assertThat(m.passRate()).isEqualTo(66.7);
    }

    @Test
    void 四项全部达标才算达标() {
        EvalMetrics good = EvalMetrics.of(List.of(
                new EvalCase(1, "a", "RUN", "RUN", "recon_run", "recon_run", 1, false, 100, 100, true)));
        assertThat(good.meetsSlo()).isTrue();

        EvalMetrics slow = EvalMetrics.of(List.of(
                new EvalCase(1, "a", "RUN", "RUN", "recon_run", "recon_run", 4, true, 100, 9000, true)));
        assertThat(slow.meetsSlo()).isFalse();
    }
}
