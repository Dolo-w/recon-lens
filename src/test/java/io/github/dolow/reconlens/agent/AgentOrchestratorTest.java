package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import io.github.dolow.reconlens.dsl.DuplicateSpec;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.MissingSpec;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.ShardSpec;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.llm.LlmChatClient;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.AgentRunRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 四阶段编排的行为断言。
 *
 * <p>这里刻意用"真仓储 + 真起草器 + 真校验器 + mock 外部边界"的组合：
 * <ul>
 *   <li>真仓储：轨迹是验收 A8 的证据，mock 掉就等于没验；</li>
 *   <li>真起草器与校验器：自纠正循环的价值就在这条链路上，mock 掉只剩一个 if；</li>
 *   <li>mock 引擎 / 试算：它们各自有端到端用例，这里只关心"编排有没有在该调的时候调"。</li>
 * </ul>
 */
class AgentOrchestratorTest {

    private static DataSource dataSource;
    private static SchemaCatalog schema;
    private static AgentProperties properties;

    @BeforeAll
    static void setUpSchema() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:agent_orchestrator;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_left (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_right (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2), status VARCHAR(16))");

        properties = new AgentProperties();
        properties.getSchema().setTables(List.of("bill_left", "bill_right"));
        schema = new SchemaCatalog(dataSource, properties);
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final AgentRunRepository runs = new AgentRunRepository(jdbc);

    private RuleCatalog catalog;
    private ReconEngine engine;
    private ShadowTrialService trials;
    private ExplainService explain;
    private ScriptedClient llm;

    @BeforeEach
    void reset() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_agent_run (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " goal TEXT NOT NULL, trigger_type VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,"
                + " plan_json TEXT, plan_truncated BOOLEAN NOT NULL DEFAULT FALSE, rule_code VARCHAR(64),"
                + " job_id BIGINT, draft_version INT, llm_calls INT NOT NULL DEFAULT 0,"
                + " llm_tokens INT NOT NULL DEFAULT 0, error_msg VARCHAR(1000), created_by VARCHAR(64),"
                + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_agent_step (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " run_id BIGINT NOT NULL, seq INT NOT NULL, phase VARCHAR(16) NOT NULL,"
                + " action VARCHAR(64) NOT NULL, input_json TEXT, payload TEXT, digest CHAR(64),"
                + " truncated BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(16) NOT NULL, cost_ms INT,"
                + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_step ON recon_agent_step (run_id, seq)");
        jdbc.update("DELETE FROM recon_agent_step");
        jdbc.update("DELETE FROM recon_agent_run");

        catalog = mock(RuleCatalog.class);
        engine = mock(ReconEngine.class);
        trials = mock(ShadowTrialService.class);
        explain = mock(ExplainService.class);
        llm = new ScriptedClient();
    }

    /** L0：现有规则命中 → 一个 token 都不花，且结果来自真引擎返回值。 */
    @Test
    void existingRuleTakesTheDeterministicPathWithZeroLlmCalls() {
        when(catalog.exists("BILL_VS_SETTLE")).thenReturn(true);
        when(catalog.resolve("BILL_VS_SETTLE")).thenReturn(new RuleCatalog.Resolved(rule(), 3, "{}"));
        when(catalog.allResolved()).thenReturn(List.of(new RuleCatalog.Resolved(rule(), 3, "{}")));
        when(engine.run(any(RuleCatalog.Resolved.class), anyString(), anyString())).thenReturn(job(42L, JobStatus.SUCCESS, 7));
        when(explain.explainJob(42L, 20)).thenReturn(5);
        when(explain.llmName()).thenReturn("rule-template");

        AgentOrchestrator.AgentRunResult result = orchestrator()
                .run(new AgentGoal("跑一下账单对账", "BILL_VS_SETTLE", "B-1", null, java.util.Set.of(),
                        AgentGoal.GoalTrigger.API, AgentGoal.Intent.RUN_RECONCILIATION), "tester");

        assertThat(result.status()).isEqualTo(AgentRunRepository.RunStatus.SUCCESS);
        assertThat(result.jobId()).isEqualTo(42L);
        assertThat(result.draftVersion()).as("确定性路径不产生草稿").isNull();

        AgentRunRepository.AgentRun run = runs.findRun(result.runId()).orElseThrow();
        assertThat(run.llmCalls()).isZero();
        assertThat(run.ruleCode()).isEqualTo("BILL_VS_SETTLE");
        assertThat(llm.prompts).as("现有规则能回答，就不该问模型").isEmpty();

        assertThat(runs.listSteps(result.runId()))
                .extracting(AgentRunRepository.AgentStep::phase)
                .containsExactly("OBSERVE", "PLAN", "ACT", "LEARN");
        verify(explain).explainJob(42L, 20);
    }

    /**
     * {@code recon_draft_rule} 的入参里有可选 {@code ruleCode}（"改现有规则时必须传"）。
     * 传一个已存在的 code 只应该"拿它当基线改一版草稿"，<b>不能</b>顺手跑一次生产对账。
     *
     * <p>这条断言拦的是语义越界：一个叫"起草"的动作产生 PROD 任务，会写 {@code recon_job} /
     * {@code recon_diff}，还会进巡检的环比基线 —— 运维看到的是"没人点过对账，却多出一条生产任务"。
     */
    @Test
    void draftIntentOnAnExistingRuleDoesNotRunProductionReconciliation() {
        when(catalog.exists("BILL_VS_SETTLE")).thenReturn(true);
        when(catalog.resolve("BILL_VS_SETTLE")).thenReturn(new RuleCatalog.Resolved(rule(), 3, "{}"));
        when(catalog.allResolved()).thenReturn(List.of(new RuleCatalog.Resolved(rule(), 3, "{}")));
        when(catalog.defineDraft(any(), anyString(), anyString(), eq(RuleOrigin.AGENT), any(), anyString()))
                .thenReturn(new RuleCatalog.Resolved(rule("BILL_VS_SETTLE"), 4, "{}"));
        when(trials.trial("BILL_VS_SETTLE", 4)).thenReturn(passedTrial("BILL_VS_SETTLE", 4));
        llm.script.add(ruleJson());

        AgentOrchestrator.AgentRunResult result = orchestrator()
                .run(AgentGoal.of("把容差改成 0.02，其余不动", "BILL_VS_SETTLE", null), "tester");

        assertThat(result.status()).as("run message: %s", result.message())
                .isEqualTo(AgentRunRepository.RunStatus.SUCCESS);
        assertThat(result.jobId()).as("起草不产生对账任务").isNull();
        verify(engine, org.mockito.Mockito.never())
                .run(any(RuleCatalog.Resolved.class), anyString(), anyString());
    }

    /** 没有 LLM、目标规则也不存在 → BLOCKED 转人工，而不是一次 500。 */
    @Test
    void withoutLlmAndWithoutMatchingRuleTheRunIsBlockedNotFailed() {
        llm.available = false;
        when(catalog.allResolved()).thenReturn(List.of());
        when(catalog.exists(anyString())).thenReturn(false);

        AgentOrchestrator.AgentRunResult result = orchestrator()
                .run(AgentGoal.of("给结算单加一条对账规则", "NEW_RULE", null), "tester");

        assertThat(result.status()).isEqualTo(AgentRunRepository.RunStatus.BLOCKED);
        assertThat(result.message()).contains("大模型未启用");
        assertThat(result.ruleCode()).isEqualTo("NEW_RULE");

        AgentRunRepository.AgentRun run = runs.findRun(result.runId()).orElseThrow();
        assertThat(run.status()).isEqualTo("BLOCKED");
        assertThat(run.errorMsg()).contains("大模型未启用");
        assertThat(run.planJson()).contains("DRAFT_RULE");
        assertThat(runs.listSteps(result.runId()))
                .extracting(AgentRunRepository.AgentStep::phase)
                .as("没有 ACT：连一次起草请求都没发出去")
                .containsExactly("OBSERVE", "PLAN");
    }

    /** DRAFT 路径：起草 → 落草稿（不是 ACTIVE）→ 试算 → 结束；审批留给人工。 */
    @Test
    void draftPathSavesADraftRunsTheTrialAndLeavesApprovalToAHuman() {
        when(catalog.allResolved()).thenReturn(List.of());
        when(catalog.exists(anyString())).thenReturn(false);
        when(catalog.defineDraft(any(), anyString(), anyString(), eq(RuleOrigin.AGENT), any(), anyString()))
                .thenReturn(new RuleCatalog.Resolved(rule("BILL_AGENT"), 1, "{}"));
        when(trials.trial("BILL_AGENT", 1)).thenReturn(passedTrial("BILL_AGENT", 1));
        llm.script.add(ruleJson());

        AgentOrchestrator.AgentRunResult result = orchestrator()
                .run(AgentGoal.of("按订单号逐笔比对账单和结算的金额", "BILL_AGENT", null), "tester");

        assertThat(result.status()).as("run message: %s", result.message())
                .isEqualTo(AgentRunRepository.RunStatus.SUCCESS);
        assertThat(result.draft()).isNotNull();
        assertThat(result.draft().assumptions()).contains("假设 order_no 是订单号");
        assertThat(result.message())
                .contains("审批不由 Agent 执行")
                .contains("/api/rules/BILL_AGENT/1/approve");

        // 生死线：一定走的是 defineDraft（DRAFT），不能是 defineMap（立刻 ACTIVE）
        verify(catalog).defineDraft(any(), anyString(), anyString(), eq(RuleOrigin.AGENT), any(), anyString());
        verify(catalog, org.mockito.Mockito.never()).defineMap(any(), anyString(), anyString());

        AgentRunRepository.AgentRun run = runs.findRun(result.runId()).orElseThrow();
        assertThat(run.ruleCode()).isEqualTo("BILL_AGENT");
        assertThat(run.draftVersion()).isEqualTo(1);
        assertThat(run.llmCalls()).isEqualTo(1);
        assertThat(runs.listSteps(result.runId()))
                .extracting(AgentRunRepository.AgentStep::action)
                .contains("save-draft", "shadow-trial");
        // 试算结束不等于批准：会话不碰审批
        assertThat(result.message()).doesNotContain("已批准").doesNotContain("已生效");
    }

    /** token 预算耗尽 → BLOCKED（不是 FAILED，也不是把半成品草稿当成功）。 */
    @Test
    void tokenBudgetExhaustionEndsAsBlocked() {
        properties.setMaxLlmTokensPerRun(10);
        try {
            when(catalog.allResolved()).thenReturn(List.of());
            when(catalog.exists(anyString())).thenReturn(false);
            llm.script.add(ruleJson());

            AgentOrchestrator.AgentRunResult result = orchestrator()
                    .run(AgentGoal.of("按订单号对账金额", "BILL_AGENT", null), "tester");

            assertThat(result.status()).isEqualTo(AgentRunRepository.RunStatus.BLOCKED);
            assertThat(result.message()).contains("预算耗尽");
        } finally {
            properties.setMaxLlmTokensPerRun(40000);
        }
    }

    /** 引擎抛错 = 真故障 → FAILED，轨迹保留（用于排障）。 */
    @Test
    void engineFailureIsRecordedAsFailedWithItsTrace() {
        when(catalog.exists("BILL_VS_SETTLE")).thenReturn(true);
        when(catalog.resolve("BILL_VS_SETTLE")).thenReturn(new RuleCatalog.Resolved(rule(), 3, "{}"));
        when(catalog.allResolved()).thenReturn(List.of());
        when(engine.run(any(RuleCatalog.Resolved.class), anyString(), anyString())).thenThrow(new IllegalStateException("连接池耗尽"));

        AgentOrchestrator.AgentRunResult result = orchestrator()
                .run(new AgentGoal(null, "BILL_VS_SETTLE", null, null, java.util.Set.of(),
                        AgentGoal.GoalTrigger.API, AgentGoal.Intent.RUN_RECONCILIATION), "tester");

        assertThat(result.status()).isEqualTo(AgentRunRepository.RunStatus.FAILED);
        assertThat(runs.findRun(result.runId()).orElseThrow().errorMsg()).contains("连接池耗尽");
        assertThat(runs.listSteps(result.runId())).isNotEmpty();
        verifyNoInteractions(explain);
    }

    // ------------------------------------------------------------------

    private AgentOrchestrator orchestrator() {
        RuleDrafter drafter = new RuleDrafter(llm, schema, catalog,
                new RuleSafetyValidator(schema, new ShardSplitAnalyzer(), new BigDecimal("2.0")),
                new ObjectMapper());
        return new AgentOrchestrator(runs, catalog, engine, trials, drafter, explain, properties,
                new ObjectMapper());
    }

    private static ReconJob job(long id, JobStatus status, long diffs) {
        Instant now = Instant.parse("2026-10-17T09:00:00Z");
        return new ReconJob(id, "BILL_VS_SETTLE", "B-1", status, 4, 4, 20, 20, 13, diffs,
                "test", null, now, now, now);
    }

    private static ShadowTrialService.TrialResult passedTrial(String code, int version) {
        TrialState state = new TrialState.Trialed(TrialVerdict.PASS, Coverage.SAMPLE_PARTIAL,
                List.of(CoverageReason.SAMPLE_TOO_SMALL));
        return new ShadowTrialService.TrialResult(code, version, state, null, "digest");
    }

    private static RuleSpec rule() {
        return rule("BILL_VS_SETTLE");
    }

    private static RuleSpec rule(String code) {
        return new RuleSpec(code, "账单对账", "按订单号比对金额",
                new SideSpec("账单", "bill_left", null, "order_no", FieldType.STRING, "order_no"),
                new SideSpec("结算", "bill_right", null, "order_no", FieldType.STRING, "order_no"),
                new ShardSpec(1, 1, null, null),
                List.of(new FieldRule("amount", FieldType.DECIMAL, false,
                        new BigDecimal("0.01"), null, "AMOUNT_MISMATCH", Severity.HIGH)),
                new MissingSpec("MISSING_LEFT", "MISSING_RIGHT", Severity.HIGH),
                new DuplicateSpec("DUPLICATE_KEY", Severity.MEDIUM),
                2);
    }

    private static String ruleJson() {
        return """
                {"code":"BILL_AGENT","reasoning":"按订单号逐笔比对金额","assumptions":["假设 order_no 是订单号"],
                 "rule":{
                   "code":"BILL_AGENT","name":"账单对账","description":"按订单号比对金额",
                   "left":{"label":"账单","table":"bill_left","keyField":"order_no","keyType":"STRING","orderBy":"order_no"},
                   "right":{"label":"结算","table":"bill_right","keyField":"order_no","keyType":"STRING","orderBy":"order_no"},
                   "shard":{"count":1,"parallelism":1},
                   "compare":[{"field":"amount","type":"DECIMAL","tolerance":0.01,"diffType":"AMOUNT_MISMATCH","severity":"HIGH"}],
                   "missing":{"leftMissingDiffType":"MISSING_LEFT","rightMissingDiffType":"MISSING_RIGHT","severity":"HIGH"},
                   "duplicate":{"diffType":"DUPLICATE_KEY","severity":"MEDIUM"}
                 }}
                """;
    }

    private static final class ScriptedClient implements LlmChatClient {

        private final Deque<String> script = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<String> complete(String system, String user) {
            prompts.add(user);
            String next = script.pollFirst();
            return next == null ? Optional.empty() : Optional.of(next);
        }

        @Override
        public String name() {
            return "scripted";
        }
    }
}
