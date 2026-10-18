package io.github.dolow.reconlens.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code verdict × coverage} 矩阵与 {@link TrialState} 的<strong>逐格对齐</strong>（设计文档 A29 / A33）。
 *
 * <p>这张表的意义不是"再抄一遍"，而是把"25 格里 9 格合法"从文档里的断言变成一条会红的测试：
 * 少一格/多一格都会被发现。第七轮之前，{@code SKIPPED × NONE} 就是因为没有人对格，被错勾成合法。
 */
class TrialStateTest {

    /** FULL 之外必须给降级理由；FULL 允许没有（见 TrialState.Trialed 的注释）。 */
    private static TrialState trial(TrialVerdict verdict, Coverage coverage) {
        List<CoverageReason> reasons = coverage == Coverage.FULL
                ? List.of()
                : List.of(CoverageReason.SAMPLE_TOO_SMALL);
        return new TrialState.Trialed(verdict, coverage, reasons);
    }

    private static boolean constructible(TrialVerdict verdict, Coverage coverage) {
        try {
            trial(verdict, coverage);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void 只有七种判定结果组合能构造出来_加上跳过与未跑共九格() {
        int legal = 0;
        for (TrialVerdict verdict : TrialVerdict.values()) {
            for (Coverage coverage : Coverage.values()) {
                if (constructible(verdict, coverage)) {
                    legal++;
                }
            }
        }
        // PASS×2 + FAIL×2 + INCONCLUSIVE×3 = 7；再算上 Skipped 与 NotRun 两个变体 = 9
        assertThat(legal).isEqualTo(7);
        assertThat(legal + 2).isEqualTo(9);
    }

    @Test
    void 逐格核对合法与非法() {
        // ✅ / ⚠️ / ⛔ / 🚫 七格
        assertThat(constructible(TrialVerdict.PASS, Coverage.FULL)).isTrue();
        assertThat(constructible(TrialVerdict.PASS, Coverage.SAMPLE_PARTIAL)).isTrue();
        assertThat(constructible(TrialVerdict.FAIL, Coverage.FULL)).isTrue();
        assertThat(constructible(TrialVerdict.FAIL, Coverage.SAMPLE_PARTIAL)).isTrue();
        assertThat(constructible(TrialVerdict.INCONCLUSIVE, Coverage.SAMPLE_PARTIAL)).isTrue();
        assertThat(constructible(TrialVerdict.INCONCLUSIVE, Coverage.FIELDS_ONLY)).isTrue();
        assertThat(constructible(TrialVerdict.INCONCLUSIVE, Coverage.NONE)).isTrue();

        // ❌ 没跑数据不可能 PASS / FAIL
        assertThat(constructible(TrialVerdict.PASS, Coverage.FIELDS_ONLY)).isFalse();
        assertThat(constructible(TrialVerdict.PASS, Coverage.NONE)).isFalse();
        assertThat(constructible(TrialVerdict.FAIL, Coverage.FIELDS_ONLY)).isFalse();
        assertThat(constructible(TrialVerdict.FAIL, Coverage.NONE)).isFalse();
        // ❌ 全验了还不确定 = 判定逻辑 bug
        assertThat(constructible(TrialVerdict.INCONCLUSIVE, Coverage.FULL)).isFalse();
        // ❌ SKIPPED 没有判定结果，走的是 TrialState.Skipped
        for (Coverage coverage : Coverage.values()) {
            assertThat(constructible(TrialVerdict.SKIPPED, coverage)).isFalse();
        }
    }

    @Test
    void 能构造不等于能批() {
        assertThat(trial(TrialVerdict.PASS, Coverage.FULL).approval()).isEqualTo(ApprovalMode.NO_SIGNOFF_REQUIRED);
        assertThat(trial(TrialVerdict.PASS, Coverage.SAMPLE_PARTIAL).approval()).isEqualTo(ApprovalMode.NEED_SIGN);
        assertThat(trial(TrialVerdict.FAIL, Coverage.FULL).approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
        assertThat(trial(TrialVerdict.FAIL, Coverage.SAMPLE_PARTIAL).approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
        assertThat(trial(TrialVerdict.INCONCLUSIVE, Coverage.SAMPLE_PARTIAL).approval())
                .isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
        assertThat(trial(TrialVerdict.INCONCLUSIVE, Coverage.FIELDS_ONLY).approval())
                .isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
    }

    @Test
    void 闸门坏了要能落库等修_而不是构造即抛() {
        // A33：CANARY_FAILED / GATE_UNTRUSTED 让每条规则都落这一格。标成"非法"等于把
        // "全部拦下等修"升级成"服务不可用" —— 所以必须能构造，只是谁也不能批。
        TrialState state = new TrialState.Trialed(TrialVerdict.INCONCLUSIVE, Coverage.NONE,
                List.of(CoverageReason.GATE_UNTRUSTED));

        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
    }

    @Test
    void 跳过试算必须写理由_且不需要签字() {
        assertThat(new TrialState.Skipped("只改了描述").approval()).isEqualTo(ApprovalMode.NO_SIGNOFF_REQUIRED);
        assertThatCode(() -> new TrialState.Skipped("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrialState.Skipped(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 未跑不可批() {
        assertThat(new TrialState.NotRun().approval()).isEqualTo(ApprovalMode.NOT_APPROVABLE);
    }

    @Test
    void 没验全却不说理由_构造失败() {
        assertThatThrownBy(() -> new TrialState.Trialed(TrialVerdict.PASS, Coverage.SAMPLE_PARTIAL, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("为什么没验全");
    }
}
