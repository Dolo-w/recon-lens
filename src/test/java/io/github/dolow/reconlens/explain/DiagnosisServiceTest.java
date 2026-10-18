package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 诊断的核心是"证据与建议分开"，以及"零成本"。
 *
 * <p>两个反例说明为什么值得测：
 * <ul>
 *   <li>把 {@code PARTIAL_FAILED} 当成功 → 差异数缺了一块，但界面上一切正常，这是最贵的一种错；</li>
 *   <li>诊断时顺手调一次模型归因 → 一个运维随手点的按钮变成账单上最大的一行。</li>
 * </ul>
 */
class DiagnosisServiceTest {

    private final ReconRepository repository = mock(ReconRepository.class);
    private final ReconEngine engine = mock(ReconEngine.class);
    private final ExplainService explainService = mock(ExplainService.class);
    private DiagnosisService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisService(repository, engine, explainService);
        when(engine.ruleForJob(any())).thenReturn(mock(RuleSpec.class));
        when(repository.summaryByType(anyLong())).thenReturn(Map.of());
        when(repository.summaryBySeverity(anyLong())).thenReturn(Map.of());
        when(repository.listShards(anyLong())).thenReturn(List.of());
        when(repository.listDiffs(anyLong(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private void job(JobStatus status, long leftRows, long rightRows, long diffRows) {
        when(repository.findJob(7L)).thenReturn(Optional.of(new ReconJob(7L, "BILL_VS_SETTLE", "B1", status,
                2, 2, leftRows, rightRows, Math.max(0, leftRows - diffRows), diffRows, null, null,
                Instant.now(), Instant.now(), Instant.now())));
    }

    @Test
    void unknownJobIsAnErrorNotAnEmptyDiagnosis() {
        when(repository.findJob(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.diagnose(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    void bothSidesEmptyMeansTheJobComparedNothing() {
        job(JobStatus.SUCCESS, 0, 0, 0);

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.BROKEN);
        assertThat(diagnosis.facts()).anySatisfy(fact -> assertThat(fact.detail()).contains("都是 0 行"));
        assertThat(diagnosis.suggestions())
                .anySatisfy(item -> assertThat(item.action()).contains("时间窗").contains("模型表"));
    }

    @Test
    void oneSideEmptyIsAlsoBrokenNotJustAllMissingDiffs() {
        job(JobStatus.SUCCESS, 100, 0, 100);

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.BROKEN);
        assertThat(diagnosis.suggestions())
                .anySatisfy(item -> assertThat(item.reason()).contains("单边缺失"));
    }

    /** 分片失败是比 status 更早暴露问题的地方：PARTIAL_FAILED 恰恰"看起来成功"。 */
    @Test
    void failedShardsMakeTheDiffCountUntrustworthy() {
        job(JobStatus.PARTIAL_FAILED, 100, 100, 3);
        when(repository.listShards(7L)).thenReturn(List.of(
                new ReconShard(7L, 0, ShardStatus.SUCCESS, 1, 50, 50, 48, 2, null, Instant.now(), Instant.now()),
                new ReconShard(7L, 1, ShardStatus.FAILED, 2, 0, 0, 0, 0, "ORA-00942", Instant.now(), Instant.now())));

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.BROKEN);
        assertThat(diagnosis.facts()).anySatisfy(fact -> assertThat(fact.detail()).contains("#1"));
        assertThat(diagnosis.suggestions())
                .anySatisfy(item -> assertThat(item.action())
                        .as("必须只补跑失败分片：整批重跑会把已经跑好的分片再跑一遍，代价与风险都翻倍")
                        .contains("只补跑失败分片"));
    }

    @Test
    void aDominantDiffTypeIsCalledOutInsteadOfListingEverything() {
        job(JobStatus.SUCCESS, 1000, 1000, 100);
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put("AMOUNT_MISMATCH", 78L);
        byType.put("MISSING_RIGHT", 22L);
        when(repository.summaryByType(7L)).thenReturn(byType);
        when(repository.summaryBySeverity(7L)).thenReturn(Map.of("LOW", 100L));

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.ATTENTION);
        assertThat(diagnosis.suggestions())
                .anySatisfy(item -> assertThat(item.reason()).contains("AMOUNT_MISMATCH").contains("78%"));
    }

    @Test
    void highSeverityShareIsSurfacedAsAFactAndASuggestion() {
        job(JobStatus.SUCCESS, 100, 100, 10);
        when(repository.summaryByType(7L)).thenReturn(Map.of("AMOUNT_MISMATCH", 10L));
        when(repository.summaryBySeverity(7L)).thenReturn(Map.of("HIGH", 8L, "LOW", 2L));

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.ATTENTION);
        assertThat(diagnosis.suggestions())
                .anySatisfy(item -> assertThat(item.reason()).contains("高严重度占 80%"));
    }

    /** 零成本：抽样归因只走知识库/模板，绝不允许触发模型调用。 */
    @Test
    void samplesUseTheZeroCostExplainPath() {
        job(JobStatus.SUCCESS, 100, 100, 2);
        when(repository.summaryByType(7L)).thenReturn(Map.of("AMOUNT_MISMATCH", 1L, "MISSING_RIGHT", 1L));
        when(repository.listDiffs(7L, null, 0, 200)).thenReturn(List.of(
                new DiffRecord(11L, 7L, 0, "AMOUNT_MISMATCH", Severity.HIGH, "ORD-1", "amount",
                        "100", "100.5", new BigDecimal("0.5"), new BigDecimal("0.005"), null),
                new DiffRecord(12L, 7L, 0, "MISSING_RIGHT", Severity.HIGH, "ORD-2", null,
                        "200", null, null, null, null)));
        when(explainService.cheapExplain(any(), anyString(), any())).thenReturn("这一类通常是批次边界");

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.samples()).hasSize(2);
        assertThat(diagnosis.samples()).extracting(DiagnosisService.Sample::diffType)
                .as("按类型各抽一条：三条同类型会让人以为只有一个问题")
                .containsExactly("AMOUNT_MISMATCH", "MISSING_RIGHT");
        verify(explainService, never()).explain(anyLong());
        verify(explainService, never()).explainJob(anyLong(), anyInt());
    }

    @Test
    void healthyJobSaysSoWithoutInventingProblems() {
        job(JobStatus.SUCCESS, 100, 100, 0);

        DiagnosisService.Diagnosis diagnosis = service.diagnose(7L);

        assertThat(diagnosis.verdict()).isEqualTo(DiagnosisService.Verdict.HEALTHY);
        assertThat(diagnosis.summary()).isEqualTo("没有发现异常信号");
        assertThat(diagnosis.suggestions())
                .as("0 条差异也要提醒「可能两侧取到同一份数据」，但不能把它说成异常")
                .anySatisfy(item -> assertThat(item.action()).contains("抽查两侧行数"));
    }

    @Test
    void verdictAndSuggestionsAreReadableInOnePass() {
        job(JobStatus.PARTIAL_FAILED, 100, 0, 100);
        when(repository.listShards(7L)).thenReturn(List.of(
                new ReconShard(7L, 1, ShardStatus.FAILED, 1, 0, 0, 0, 0, "ORA-00942", Instant.now(), Instant.now())));

        String text = service.diagnose(7L).describe();

        assertThat(text).contains("任务 #7").contains("BILL_VS_SETTLE").contains("不可信")
                .contains("建议动作").contains("依据：");
    }
}
