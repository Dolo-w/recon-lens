package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 2 的判定表。这是整个 M2 里最该有测试的一处：
 * 它把 T0–T7 的布尔结论压成 {@code (verdict, coverage)}，而这两个值决定了"能不能批准"。
 *
 * <p>用例刻意成对写（{@code X + FULL} / {@code X + SAMPLE_PARTIAL}），因为矩阵里最容易出错的地方
 * 从来不是"某个值对不对"，而是"两个值的组合合不合法"。
 */
class TrialVerdictResolverTest {

    /** 用默认阈值（min-sample-rows=200 / min-common-keys=100 / min-shards-with-data=2 / ratio=0.8）。 */
    private final TrialVerdictResolver resolver = new TrialVerdictResolver(new ReconProperties().getTrial());

    private static TrialOutcome.SampleStats sample(int left, int right, int common, int shards, int count, int baseline) {
        return new TrialOutcome.SampleStats(left, right, common, shards, count, baseline, 0, 0);
    }

    /** 所有判据都通过、样本达标、分片覆盖达标的一份"理想输入"。 */
    private TrialOutcome healthy() {
        return new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, false);
    }

    private TrialState.Trialed trialed(TrialOutcome outcome) {
        return (TrialState.Trialed) resolver.resolve(outcome);
    }

    // ---------------- 覆盖等级 ----------------

    @Test
    void fullCoverageWithEverythingGreenIsPass() {
        TrialState.Trialed state = trialed(healthy());
        assertThat(state.verdict()).isEqualTo(TrialVerdict.PASS);
        assertThat(state.coverage()).isEqualTo(Coverage.FULL);
        assertThat(state.reasons()).isEmpty();
        assertThat(state.approval()).isEqualTo(ApprovalMode.NO_SIGNOFF_REQUIRED);
    }

    @Test
    void smallSampleDegradesToPartialAndNeedsSignature() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(50, 50, 40, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).contains(CoverageReason.SAMPLE_TOO_SMALL);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
    }

    @Test
    void shardCoverageShortDegradesToPartial() {
        // baselineHit=8 → required = min(8, max(2, floor(0.8*8)=6)) = 6；只验到 3 片
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 3, 8, 8),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).containsExactly(CoverageReason.SHARD_COVERAGE);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
    }

    @Test
    void baselineWithOneShardIsNotAVacuum() {
        // baselineHit=1 → required = min(1, max(2,0)) = 1：生产也只落一片，验到一片就够了。
        // v0.5 的 "≥2 片" 会把这条规则永久判死，本用例就是钉住那次修正。
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 1, 8, 1),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, false);
        assertThat(trialed(outcome).coverage()).isEqualTo(Coverage.FULL);
    }

    @Test
    void noBaselineFallsBackToShardCount() {
        // 没有生产基线时退回总片数：8 片里验到 7 片，required = min(8, max(2,6)) = 6 → 通过
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 7, 8, 0),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, false);
        assertThat(trialed(outcome).coverage()).isEqualTo(Coverage.FULL);
    }

    @Test
    void notMaterializedGivesFieldsOnly() {
        TrialOutcome outcome = new TrialOutcome(false, CoverageReason.NO_DDL_PRIVILEGE,
                TrialOutcome.SampleStats.empty(), null, null, null, null, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.coverage()).isEqualTo(Coverage.FIELDS_ONLY);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.reasons()).containsExactly(CoverageReason.NO_DDL_PRIVILEGE);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
    }

    // ---------------- 闸门自己坏了 ----------------

    @Test
    void canaryFailureForcesNoneAndHardBlock() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, false, "期望 2 条，实际 0 条"), true, true, true, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.coverage()).isEqualTo(Coverage.NONE);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.reasons()).containsExactly(CoverageReason.CANARY_FAILED);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
    }

    @Test
    void gateUntrustedForcesNoneAndHardBlock() {
        TrialOutcome outcome = new TrialOutcome(false, CoverageReason.GATE_UNTRUSTED,
                TrialOutcome.SampleStats.empty(), null, null, null, null, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.coverage()).isEqualTo(Coverage.NONE);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
    }

    /**
     * 关键用例：canary 失败<b>且</b> T1 也失败时，必须是 {@code INCONCLUSIVE × NONE}，
     * 不能是 {@code FAIL × NONE} —— 后者是非法组合，构造就抛异常，等于让闸门故障升级成服务不可用。
     */
    @Test
    void canaryFailureOutranksJobFailureSoTheStateStaysConstructible() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, false, "未检出"), false, false, false, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.coverage()).isEqualTo(Coverage.NONE);
    }

    @Test
    void canaryWithoutCleanRowIsPartialNotUntrusted() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(false, false, "只剩 1 个干净键"), true, true, true, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).containsExactly(CoverageReason.CANARY_NO_CLEAN_ROW);
        // 不置 gate_trusted=false —— 这是规则差异率问题，不是闸门故障
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
    }

    // ---------------- T1–T4 ----------------

    @Test
    void jobFailureIsHardFail() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), false, null, null, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.FAIL);
        assertThat(state.coverage()).isEqualTo(Coverage.FULL);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
    }

    @Test
    void nonIdempotentIsHardFail() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, false, true, null, false, false);
        assertThat(trialed(outcome).verdict()).isEqualTo(TrialVerdict.FAIL);
    }

    @Test
    void unexplainedCoverageLossIsHardFail() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, false, null, false, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.FAIL);
        // FAIL × SAMPLE_PARTIAL 也是硬拦：差异方向已明确，不因为样本不完整降级成"待签字通过"
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_HARD);
    }

    @Test
    void unexplainedZeroDiffIsHardFail() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, false, false, false);
        assertThat(trialed(outcome).verdict()).isEqualTo(TrialVerdict.FAIL);
    }

    @Test
    void hardFailureOutranksSampleInsufficiency() {
        // T5 不达标 + T1 失败 → FAIL（"规则有问题"比"我们没验清楚"更诚实，处置也完全不同）
        TrialOutcome outcome = new TrialOutcome(true, null, sample(10, 10, 5, 1, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), false, null, null, null, false, false);
        assertThat(trialed(outcome).verdict()).isEqualTo(TrialVerdict.FAIL);
    }

    @Test
    void diffGrowthIsSoftAndDoesNotAffectVerdict() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, true, false);
        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.PASS);
        assertThat(state.coverage()).isEqualTo(Coverage.FULL);
    }

    // ---------------- T5-c 的阈值表 ----------------

    /**
     * {@code b=1..10} 的<b>硬编码</b>期望值。
     *
     * <p>为什么不用公式现算期望值：那等于拿实现证明实现。这张表来自设计文档 §5.3，
     * 表与代码一旦漂移，这个用例就红 —— 它盯的是"文档和代码是同一个东西"。
     */
    @Test
    void requiredShardsMatchesTheDocumentedTable() {
        int[] baselineHits = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        int[] expected = {1, 2, 2, 3, 4, 4, 5, 6, 7, 8};
        for (int i = 0; i < baselineHits.length; i++) {
            assertThat(resolver.requiredShards(baselineHits[i]))
                    .as("baselineHit=%d", baselineHits[i])
                    .isEqualTo(expected[i]);
        }
    }

    @Test
    void requiredShardsBeyondTenUseTheFormula() {
        assertThat(resolver.requiredShards(16)).isEqualTo(12);   // min(16, max(2, floor(12.8)))
        assertThat(resolver.requiredShards(0)).isZero();         // 交给 T5-a 报"样本为空"
    }

    @Test
    void allowedToleranceIncreasesWithBaseline() {
        // 允许少的分片数：b=3 起才有容忍，且单调不减 —— 这是"允许抖动但不允许只验到零头"的口径
        int previous = -1;
        for (int b = 1; b <= 10; b++) {
            int allowed = b - resolver.requiredShards(b);
            assertThat(allowed).as("baselineHit=%d", b).isGreaterThanOrEqualTo(previous);
            previous = allowed;
        }
        assertThat(1 - resolver.requiredShards(1)).isZero();   // 基线只有 1 片时零容忍
        assertThat(2 - resolver.requiredShards(2)).isZero();   // 基线 2 片时零容忍（少一片就进真空）
        assertThat(3 - resolver.requiredShards(3)).isEqualTo(1);
    }

    @Test
    void reasonsAreOrderedBySeverity() {
        TrialOutcome outcome = new TrialOutcome(true, null, sample(10, 10, 5, 1, 4, 4),
                new TrialOutcome.Canary(false, false, "无干净键"), true, true, true, null, false, false);
        List<CoverageReason> reasons = trialed(outcome).reasons();
        // SAMPLE_TOO_SMALL(10) / SHARD_COVERAGE(25) / CANARY_NO_CLEAN_ROW(20) 的严重度降序
        assertThat(reasons).containsExactly(CoverageReason.SHARD_COVERAGE,
                CoverageReason.CANARY_NO_CLEAN_ROW, CoverageReason.SAMPLE_TOO_SMALL);
        assertThat(CoverageReason.main(reasons)).isEqualTo(CoverageReason.SHARD_COVERAGE);
    }

    @Test
    void upstreamTruncationDegradesCoverageButKeepsTheVerdict() {
        // 上游 SQL 自带 LIMIT：样本是"原查询被截断后的一角"。它不是 FAIL —— 这一角里确实没问题，
        // 但它也绝不是 FULL：PASS 只覆盖了窗口里还剩下的那部分。
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, true);

        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.PASS);
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).containsExactly(CoverageReason.UPSTREAM_LIMIT);
        // 可批，但必须签字：降级签字通道不能自动放行一条"只验到一角"的规则
        assertThat(state.approval()).isEqualTo(io.github.dolow.reconlens.domain.ApprovalMode.NEED_SIGN);
    }

    @Test
    void upstreamLimitOutranksSmallSampleInTheReasonColumn() {
        // A28 的情景：sql 自带 LIMIT，样本当然也不够。两个理由同时成立，主理由必须是"结构性"的那个 ——
        // 调大 window-rows 修不好上游截断，调窗口才是次要动作
        TrialOutcome outcome = new TrialOutcome(true, null, sample(50, 50, 40, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, true, true, null, false, true);

        TrialState.Trialed state = trialed(outcome);
        assertThat(state.reasons()).containsExactly(CoverageReason.UPSTREAM_LIMIT,
                CoverageReason.SAMPLE_TOO_SMALL);
        assertThat(CoverageReason.main(state.reasons())).isEqualTo(CoverageReason.UPSTREAM_LIMIT);
    }

    @Test
    void upstreamTruncationIsRecordedOnlyWhenMaterialized() {
        // 没物化时 coverage 已经是 FIELDS_ONLY（更差）。再挂一条"样本被截断"，报告会自相矛盾
        // （样本行数是 0，却说样本被截断），也会把注意力从"根本没验"引开
        TrialOutcome outcome = new TrialOutcome(false, CoverageReason.NO_DDL_PRIVILEGE,
                TrialOutcome.SampleStats.empty(), null, null, null, null, null, false, true);

        TrialState.Trialed state = trialed(outcome);
        assertThat(state.coverage()).isEqualTo(Coverage.FIELDS_ONLY);
        assertThat(state.reasons()).containsExactly(CoverageReason.NO_DDL_PRIVILEGE);
    }

    @Test
    void digestOverflowIsInconclusiveNotPass() {
        // T2 的摘要超过上限时两次都拿不到摘要，两张空表彼此相等。若照旧判 PASS，
        // 等于用"什么都看不见"给幂等性背书 —— 规则差异实际是什么样，没人验过
        TrialOutcome outcome = new TrialOutcome(true, null, sample(500, 500, 400, 4, 4, 4),
                new TrialOutcome.Canary(true, true, "ok"), true, null, true, null, false, false, true);

        TrialState.Trialed state = trialed(outcome);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).contains(CoverageReason.DIGEST_OVERFLOW);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
    }

    @Test
    void contradictoryOutcomeIsRejectedAtConstruction() {
        // 已物化却带阻断原因：组装代码把"降级"和"成功"混着写了
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TrialOutcome(true,
                        CoverageReason.NO_DDL_PRIVILEGE, TrialOutcome.SampleStats.empty(), null,
                        null, null, null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        // 未物化却不说原因：会让 coverage=FIELDS_ONLY 配一个空 reasons
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TrialOutcome(false, null,
                        TrialOutcome.SampleStats.empty(), null, null, null, null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
