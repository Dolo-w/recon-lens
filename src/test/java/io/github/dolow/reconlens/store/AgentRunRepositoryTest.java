package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轨迹表的语义。这里只验"存得下、串得起来、顺序能拦住"，不验编排逻辑。
 *
 * <p>重点在被改过一次的那个东西：{@code payload} 与 {@code digest} 是两列。
 * 混成一列的后果不是"多占点空间"，而是<b>两个能力同时消失</b> ——
 * 想按哈希比对时发现列里是内容，想截断时发现列名叫 digest 不敢动。
 */
class AgentRunRepositoryTest {

    private static final String DDL_RUN = """
            CREATE TABLE IF NOT EXISTS recon_agent_run (
              id             BIGINT AUTO_INCREMENT PRIMARY KEY,
              goal           TEXT         NOT NULL,
              trigger_type   VARCHAR(16)  NOT NULL,
              status         VARCHAR(16)  NOT NULL,
              plan_json      TEXT         NULL,
              plan_truncated BOOLEAN      NOT NULL DEFAULT FALSE,
              rule_code      VARCHAR(64)  NULL,
              job_id         BIGINT       NULL,
              draft_version  INT          NULL,
              llm_calls      INT          NOT NULL DEFAULT 0,
              llm_tokens     INT          NOT NULL DEFAULT 0,
              error_msg      VARCHAR(1000) NULL,
              created_by     VARCHAR(64)  NULL,
              created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              finished_at    TIMESTAMP    NULL
            )
            """;

    private static final String DDL_STEP = """
            CREATE TABLE IF NOT EXISTS recon_agent_step (
              id         BIGINT AUTO_INCREMENT PRIMARY KEY,
              run_id     BIGINT      NOT NULL,
              seq        INT         NOT NULL,
              phase      VARCHAR(16) NOT NULL,
              action     VARCHAR(64) NOT NULL,
              input_json TEXT        NULL,
              payload    TEXT        NULL,
              digest     CHAR(64)    NULL,
              truncated  BOOLEAN     NOT NULL DEFAULT FALSE,
              status     VARCHAR(16) NOT NULL,
              cost_ms    INT         NULL,
              created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:agent_run;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final AgentRunRepository repository = new AgentRunRepository(jdbc);

    @BeforeEach
    void reset() {
        jdbc.execute(DDL_RUN);
        jdbc.execute(DDL_STEP);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_step ON recon_agent_step (run_id, seq)");
        jdbc.update("DELETE FROM recon_agent_step");
        jdbc.update("DELETE FROM recon_agent_run");
    }

    @Test
    void startRunOpensARunningSession() {
        long runId = repository.startRun("给结算单加一条对账规则", "API", "tester");

        AgentRunRepository.AgentRun run = repository.findRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(run.triggerType()).isEqualTo("API");
        assertThat(run.goal()).contains("结算单");
        assertThat(run.llmCalls()).isZero();
        assertThat(run.finishedAt()).isNull();
    }

    /** A8：会话 → 步骤 → 规则版本 / 任务，四条链路要能串起来。 */
    @Test
    void runLinksToStepsAndToTheDraftItProduced() {
        long runId = repository.startRun("月度账单对账", "NL_QUESTION", "tester");
        repository.recordStep(runId, 1, "OBSERVE", "list-rules", null, "3 条规则可用", "sha-a", false, "OK", 5);
        repository.recordStep(runId, 2, "PLAN", "draft-rule", null, "选了 LLM 起草", "sha-b", false, "OK", 900);
        repository.recordStep(runId, 3, "ACT", "shadow-trial", null, "PASS SAMPLE_PARTIAL", "sha-c", false, "OK", 1200);
        repository.bumpLlm(runId, 2, 3200);
        repository.updatePlan(runId, "{\"path\":\"DRAFT_RULE\"}", false);
        repository.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, "BILL_MONTHLY", 42L, 7, null);

        AgentRunRepository.AgentRun run = repository.findRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo("SUCCESS");
        assertThat(run.ruleCode()).isEqualTo("BILL_MONTHLY");
        assertThat(run.jobId()).isEqualTo(42L);
        assertThat(run.draftVersion()).isEqualTo(7);
        assertThat(run.llmCalls()).isEqualTo(2);
        assertThat(run.llmTokens()).isEqualTo(3200);
        assertThat(run.planJson()).contains("DRAFT_RULE");
        assertThat(run.finishedAt()).isNotNull();

        List<AgentRunRepository.AgentStep> steps = repository.listSteps(runId);
        assertThat(steps).extracting(AgentRunRepository.AgentStep::seq).containsExactly(1, 2, 3);
        assertThat(steps).extracting(AgentRunRepository.AgentStep::phase)
                .containsExactly("OBSERVE", "PLAN", "ACT");
        assertThat(steps.get(2).digest()).isEqualTo("sha-c");
    }

    /** payload 可以截断、digest 不行 —— 这就是"一列拆两列"的实际用法。 */
    @Test
    void truncatedPayloadAndFullDigestCoexist() {
        long runId = repository.startRun("g", "API", "t");
        repository.recordStep(runId, 1, "PLAN", "draft-rule", null, "截断后的内容…", "0123456789abcdef", true, "OK", 1);

        AgentRunRepository.AgentStep step = repository.listSteps(runId).get(0);
        assertThat(step.truncated()).isTrue();
        assertThat(step.payload()).isEqualTo("截断后的内容…");
        assertThat(step.digest()).hasSize(16);
    }

    @Test
    void duplicateSeqIsRejectedByTheDatabase() {
        long runId = repository.startRun("g", "API", "t");
        repository.recordStep(runId, 1, "OBSERVE", "a", null, "x", null, false, "OK", 1);

        assertThatThrownBy(() -> repository.recordStep(runId, 1, "PLAN", "b", null, "y", null, false, "OK", 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void nextSeqContinuesAfterTheLastStep() {
        long runId = repository.startRun("g", "API", "t");
        assertThat(repository.nextSeq(runId)).isEqualTo(1);
        repository.recordStep(runId, 1, "OBSERVE", "a", null, "x", null, false, "OK", 1);
        assertThat(repository.nextSeq(runId)).isEqualTo(2);
    }

    /** BLOCKED 与 FAILED 必须是两个值：前者"按规则不该继续"，后者"出错了"。 */
    @Test
    void blockedIsNotFailed() {
        long runId = repository.startRun("g", "API", "t");
        repository.finishRun(runId, AgentRunRepository.RunStatus.BLOCKED, null, null, null,
                "Agent 预算耗尽（轮数）：已用 3 轮");

        AgentRunRepository.AgentRun run = repository.findRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo("BLOCKED");
        assertThat(run.errorMsg()).contains("预算耗尽");
    }

    @Test
    void recentRunsAreNewestFirst() {
        long first = repository.startRun("第一条", "API", "t");
        long second = repository.startRun("第二条", "API", "t");

        assertThat(repository.recentRuns(10)).extracting(AgentRunRepository.AgentRun::id)
                .containsExactly(second, first);
    }
}
