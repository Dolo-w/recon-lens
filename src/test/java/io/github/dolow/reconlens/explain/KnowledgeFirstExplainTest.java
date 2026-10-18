package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.DiffKnowledgeRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本分层（验收 A6）：知识库命中之后，同类差异<b>不再触发 LLM</b>。
 *
 * <p>这些用例的价值全在"模型调用次数"这一个断言上。只验"解释了、有文案"是不够的 ——
 * 一个把知识库查了但照样把整批丢给模型的实现，文案完全一样，成本分文未降。
 * 所以这里数调用次数，而不是看输出。
 *
 * <p>用的是真知识库（H2）而不是 mock 的 {@code DiffKnowledgeBase}：分层的关键在
 * "写回之后下次会命中"，mock 掉知识库等于把要验的因果链本身换成桩。
 */
class KnowledgeFirstExplainTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS recon_diff_kb (
              id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
              cause_key         VARCHAR(64)   NOT NULL,
              rule_code         VARCHAR(64)   NOT NULL,
              diff_type         VARCHAR(64)   NOT NULL,
              field_name        VARCHAR(64)   NOT NULL DEFAULT '',
              feature_signature VARCHAR(256)  NOT NULL DEFAULT '',
              cause             VARCHAR(1000) NOT NULL,
              action            VARCHAR(1000) NULL,
              confidence        DECIMAL(4,3)  NOT NULL DEFAULT 0.500,
              source            VARCHAR(16)   NOT NULL,
              hits              INT           NOT NULL DEFAULT 0,
              misses            INT           NOT NULL DEFAULT 0,
              created_by        VARCHAR(64)   NULL,
              created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at        TIMESTAMP     NULL
            )
            """;

    private static final String RULE = "BILL_VS_SETTLE";

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:explain_kb;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final DiffKnowledgeRepository knowledgeRepository = new DiffKnowledgeRepository(jdbc);
    private final ReconProperties properties = new ReconProperties();

    private final RuleBasedExplainer template = mock(RuleBasedExplainer.class);
    private final LlmDiffExplainer llm = mock(LlmDiffExplainer.class);
    private final ReconEngine engine = mock(ReconEngine.class);
    private final ReconRepository repository = mock(ReconRepository.class);
    private ExplainService service;

    @BeforeEach
    void setUp() {
        jdbc.execute(DDL);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_cause_key ON recon_diff_kb (cause_key)");
        jdbc.update("DELETE FROM recon_diff_kb");

        rebuildService();
        when(llm.available()).thenReturn(true);
        when(llm.name()).thenReturn("stub-llm");
        when(template.name()).thenReturn("rule-template");
        when(template.explain(any(), any())).thenReturn("模板兜底文案");
        when(engine.ruleForJob(any())).thenReturn(mock(RuleSpec.class));
    }

    private void rebuildService() {
        service = new ExplainService(template, llm,
                new DiffKnowledgeBase(knowledgeRepository, properties), engine, repository);
    }

    /** shape 相同、金额不同的两条差异 —— 它们应该共用同一条归因。 */
    private static DiffRecord diff(long id, long jobId, String bizKey, String left, String right) {
        return new DiffRecord(id, jobId, 0, "AMOUNT_MISMATCH", Severity.HIGH, bizKey, "amount",
                left, right, new BigDecimal("10"), new BigDecimal("0.005"), null);
    }

    private void pending(long jobId, DiffRecord... diffs) {
        ReconJob job = new ReconJob(jobId, RULE, "B1", JobStatus.SUCCESS, 1, 1, 10, 10, 9,
                diffs.length, null, null, Instant.now(), Instant.now(), Instant.now());
        when(repository.findJob(jobId)).thenReturn(Optional.of(job));
        when(repository.listUnexplainedDiffs(eq(jobId), anyInt())).thenReturn(List.of(diffs));
    }

    @Test
    void knowledgeHitMeansZeroLlmCalls() {
        DiffRecord diff = diff(1L, 10L, "ORD-1", "100.00", "100.50");
        CauseKey key = CauseKey.of(RULE, diff);
        jdbc.update("INSERT INTO recon_diff_kb (cause_key, rule_code, diff_type, field_name, feature_signature,"
                        + " cause, confidence, source) VALUES (?,?,?,?,?,?,?,?)",
                key.digest(), RULE, "AMOUNT_MISMATCH", "amount", key.featureSignature(),
                "入账时点不同，对方 23:59 批次延迟", "0.900", "HUMAN");
        pending(10L, diff);

        assertThat(service.explainJob(10L, 100)).isEqualTo(1);

        verify(llm, never()).explainBatch(any(), anyList());
        verify(llm, never()).explain(any(), any());
        verify(repository).updateExplanation(1L, "入账时点不同，对方 23:59 批次延迟");
    }

    /**
     * A6 的完整闭环：第一次问模型 → 写回知识库 → 同一规则、同形状、不同金额的第二批差异，
     * 一条都不用再问。
     */
    @Test
    void secondBatchOfTheSameShapeNeverReachesTheModel() {
        pending(10L, diff(1L, 10L, "ORD-1", "100.00", "100.50"));
        when(llm.explainBatch(any(), anyList())).thenReturn(Map.of(1L, "汇率进位导致的分币差"));

        service.explainJob(10L, 100);
        verify(llm, times(1)).explainBatch(any(), anyList());

        pending(11L, diff(2L, 11L, "ORD-2", "888.00", "888.90"));
        service.explainJob(11L, 100);

        // 第二批同形状差异必须走知识库：模型调用次数不能涨
        verify(llm, times(1)).explainBatch(any(), anyList());
        verify(repository).updateExplanation(2L, "汇率进位导致的分币差");
        assertThat(service.knowledgeSnapshot().hits()).isEqualTo(1);
    }

    @Test
    void modelAnswerIsWrittenBackWithLowConfidence() {
        pending(10L, diff(1L, 10L, "ORD-1", "100.00", "100.50"));
        when(llm.explainBatch(any(), anyList())).thenReturn(Map.of(1L, "对方批次未同步"));

        service.explainJob(10L, 100);

        DiffKnowledgeRepository.Entry entry = knowledgeRepository.recent(1).get(0);
        assertThat(entry.source()).isEqualTo("LLM");
        assertThat(entry.confidence())
                .as("模型自述不是事实，置信度必须低于人工修正的 0.9")
                .isEqualByComparingTo("0.5");
    }

    /** 模型漏答一条：那一条落回模板，且不能把模板文案当成"学到的知识"写进库。 */
    @Test
    void partialModelAnswerFallsBackToTemplateWithoutPollutingTheKnowledgeBase() {
        DiffRecord answered = diff(1L, 10L, "ORD-1", "100.00", "100.50");
        DiffRecord missed = diff(2L, 10L, "ORD-3", "500.00", "520.00");
        pending(10L, answered, missed);
        when(llm.explainBatch(any(), anyList())).thenReturn(Map.of(1L, "对方批次未同步"));

        service.explainJob(10L, 100);

        verify(repository).updateExplanation(2L, "模板兜底文案");
        assertThat(knowledgeRepository.recent(10))
                .as("只有模型真正给出的那条该入库")
                .hasSize(1);
    }

    /** 没配 key 的机器：知识库空、模型不可用，模板必须照样给出解释（不能留空）。 */
    @Test
    void templateIsTheFloorWhenNeitherKnowledgeNorModelIsAvailable() {
        when(llm.available()).thenReturn(false);
        pending(10L, diff(1L, 10L, "ORD-1", "100.00", "100.50"));

        service.explainJob(10L, 100);

        verify(repository).updateExplanation(1L, "模板兜底文案");
        verify(llm, never()).explainBatch(any(), anyList());
        assertThat(service.knowledgeSnapshot().lookups()).isEqualTo(1);
        assertThat(service.knowledgeSnapshot().absent()).isEqualTo(1);
    }

    /** 置信度门槛：低于门槛的条目不采用，但要记一次 misses —— 否则看不出"库里有货但用不上"。 */
    @Test
    void lowConfidenceEntriesAreCountedAsMissesNotHits() {
        DiffRecord diff = diff(1L, 10L, "ORD-1", "100.00", "100.50");
        CauseKey key = CauseKey.of(RULE, diff);
        knowledgeRepository.insertIfAbsent(new DiffKnowledgeRepository.Entry(
                0L, key.digest(), RULE, "AMOUNT_MISMATCH", "amount", key.featureSignature(),
                "模型猜的", null, new BigDecimal("0.5"), "LLM", 0, 0, "agent", null, null));

        properties.getExplain().getKnowledge().setMinConfidence(0.9);
        rebuildService();
        pending(10L, diff);
        when(llm.explainBatch(any(), anyList())).thenReturn(Map.of(1L, "更靠谱的说法"));

        service.explainJob(10L, 100);

        assertThat(service.knowledgeSnapshot().lowConfidence()).isEqualTo(1);
        assertThat(service.knowledgeSnapshot().hits()).isZero();
        verify(llm).explainBatch(any(), anyList());
        assertThat(knowledgeRepository.findByKey(key.digest()).orElseThrow().misses()).isEqualTo(1);
    }

    /** 关掉知识库就等于退回"每次都问模型"，用于验证知识库到底省了多少。 */
    @Test
    void disablingTheKnowledgeBaseRestoresTheDirectLlmPath() {
        properties.getExplain().getKnowledge().setEnabled(false);
        rebuildService();
        pending(10L, diff(1L, 10L, "ORD-1", "100.00", "100.50"));
        when(llm.explainBatch(any(), anyList())).thenReturn(Map.of(1L, "模型说法"));

        service.explainJob(10L, 100);

        verify(llm).explainBatch(any(), anyList());
        assertThat(knowledgeRepository.recent(10))
                .as("关掉之后不该再有任何写回")
                .isEmpty();
    }

    /** 归因回流：人工修正后，同一形状的差异下一轮直接用人的说法，且模型写的 0.5 条目盖不掉它。 */
    @Test
    void humanCorrectionWinsOverLaterModelAnswers() {
        DiffRecord diff = diff(1L, 10L, "ORD-1", "100.00", "100.50");
        when(repository.findDiff(1L)).thenReturn(Optional.of(diff));
        pending(10L, diff);

        assertThat(service.correct(1L, "渠道方未同步退款", "去查退款流水", "ops")).isTrue();

        DiffKnowledgeRepository.Entry entry = knowledgeRepository.recent(1).get(0);
        assertThat(entry.source()).isEqualTo("HUMAN");
        assertThat(entry.confidence()).isEqualByComparingTo("0.9");
        verify(repository).updateExplanation(1L, "渠道方未同步退款 建议动作：去查退款流水");

        // 下一批同形状差异：直接命中人工结论，模型一次都不调
        pending(11L, diff(2L, 11L, "ORD-9", "77.00", "77.30"));
        service.explainJob(11L, 100);

        verify(llm, never()).explainBatch(any(), anyList());
        verify(repository).updateExplanation(2L, "渠道方未同步退款 建议动作：去查退款流水");
    }

    /** 单条路径也要走同一套分层，否则"点一条看一条"就成了最贵的用法。 */
    @Test
    void singleExplainAlsoConsultsTheKnowledgeBaseFirst() {
        DiffRecord diff = diff(1L, 10L, "ORD-1", "100.00", "100.50");
        when(repository.findDiff(1L)).thenReturn(Optional.of(diff));
        when(llm.explain(any(), any())).thenReturn("对方批次未同步");
        pending(10L, diff);

        assertThat(service.explain(1L)).isEqualTo("对方批次未同步");
        assertThat(service.explain(1L))
                .as("第二次同一形状的差异应该命中知识库，文案一致")
                .isEqualTo("对方批次未同步");

        verify(llm, times(1)).explain(any(), any());
    }
}
