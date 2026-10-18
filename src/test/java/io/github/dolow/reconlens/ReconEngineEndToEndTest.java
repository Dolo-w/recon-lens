package io.github.dolow.reconlens;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端：跑真库（H2）+ 真 SQL + 真分片并发。
 * 这里验证"整套东西接起来还对不对"，纯逻辑由各单元测试覆盖。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class ReconEngineEndToEndTest {

    @Autowired
    private ReconEngine engine;
    @Autowired
    private ReconRepository repository;
    @Autowired
    private ExplainService explainService;

    @Test
    void classifiesEveryDiffKindAndIsIdempotentOnRetry() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "it-bill", "集成测试");

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(job.shardCount()).isEqualTo(2);
        assertThat(job.shardDone()).isEqualTo(2);
        assertThat(job.leftRows()).isEqualTo(10);
        assertThat(job.rightRows()).isEqualTo(10);

        Map<String, Long> byType = repository.summaryByType(job.id());
        assertThat(byType)
                .containsEntry("AMOUNT_MISMATCH", 1L)
                .containsEntry("MISSING_RIGHT", 1L)
                .containsEntry("MISSING_LEFT", 1L)
                .containsEntry("DUPLICATE_KEY", 1L);
        assertThat(job.diffRows()).isEqualTo(4);

        // 金额差异带上了 delta 与相对比例，前端/报告不用自己算
        List<DiffRecord> amount = repository.listDiffs(job.id(), "AMOUNT_MISMATCH", 0, 10);
        assertThat(amount).hasSize(1);
        assertThat(amount).anyMatch(d -> d.deltaValue() != null && d.deltaRatio() != null);

        // 重跑整批：先删本分片差异再写，所以条数不会翻倍
        ReconJob rerun = engine.retry(job.id(), false);
        assertThat(rerun.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(rerun.diffRows()).isEqualTo(4);
        assertThat(repository.countDiffs(job.id(), null)).isEqualTo(4);
        assertThat(repository.listShards(job.id()))
                .extracting(ReconShard::status)
                .containsOnly(ShardStatus.SUCCESS);
    }

    @Test
    void explanationsAreFilledByRuleTemplateWhenLlmIsOff() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "it-explain", "归因测试");

        int explained = explainService.explainJob(job.id(), 50);

        assertThat(explained).isEqualTo(4);
        assertThat(explainService.llmAvailable()).isFalse();
        List<DiffRecord> diffs = repository.listDiffs(job.id(), null, 0, 50);
        assertThat(diffs).allSatisfy(diff -> assertThat(diff.explanation()).isNotBlank());
        assertThat(diffs).anySatisfy(diff -> assertThat(diff.explanation()).contains("业务键"));
    }

    @Test
    void customSqlAggregationRuleRunsOnBothSides() {
        ReconJob job = engine.run("CUSTOMER_SUM_RECON", "it-sum", "聚合对账");

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(job.leftRows()).isPositive();
        assertThat(job.rightRows()).isPositive();
    }

    @Test
    void unknownRuleIsRejectedWithReadableMessage() {
        assertThatThrownBy(() -> engine.run("NOT_EXIST", "x", null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("BILL_VS_SETTLE");
    }

    @Test
    void retryOnlyFailedShardsKeepsSuccessfulOnes() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "it-retry", "断点续跑");
        long diffsBefore = repository.countDiffs(job.id(), null);

        // 没有失败分片时，onlyFailed 重跑不应该做任何事
        ReconJob again = engine.retry(job.id(), true);

        assertThat(again.shardDone()).isEqualTo(2);
        assertThat(repository.countDiffs(job.id(), null)).isEqualTo(diffsBefore);
    }

    @Test
    void retryOfUnknownJobIsRejected() {
        assertThatThrownBy(() -> engine.retry(999_999L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    void overlongFailureReasonIsTruncatedInsteadOfBlowingUp() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "it-long-error", "超长失败原因");

        // 真实踩过：分片各自把 SQL 拼进失败原因，汇总后超过 error_msg 列宽(1000)，
        // MySQL 直接 Data too long，导致创建任务的请求 500、任务状态没落库。
        repository.finishJob(job.id(), JobStatus.FAILED, "分片0: " + "x".repeat(5_000));

        ReconJob finished = repository.findJob(job.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.FAILED);
        assertThat(finished.errorMsg()).hasSize(1000).startsWith("分片0: ");
    }
}
