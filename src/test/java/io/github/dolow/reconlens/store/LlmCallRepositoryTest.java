package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.LlmCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本账本的存储层验证（清单 P0-2）——真 H2 库，不 mock SQL。
 *
 * <p>为什么必须打真库：这一层的风险全在 SQL 上（列名、时间比较、聚合口径），
 * mock 掉 JdbcTemplate 等于把唯一的被测对象换掉。
 */
class LlmCallRepositoryTest {

    private JdbcTemplate jdbc;
    private LlmCallRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:llm_call_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        // 与生产同构的 DDL（列名/类型必须一致，否则测过了线上还是会炸）
        jdbc.execute("""
                CREATE TABLE recon_llm_call (
                  id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
                  purpose               VARCHAR(32)   NOT NULL,
                  provider              VARCHAR(32)   NOT NULL,
                  model                 VARCHAR(128)  NOT NULL,
                  prompt_tokens         INT           NOT NULL DEFAULT 0,
                  completion_tokens     INT           NOT NULL DEFAULT 0,
                  total_tokens          INT           NOT NULL DEFAULT 0,
                  latency_ms            BIGINT        NOT NULL DEFAULT 0,
                  attempt               INT           NOT NULL DEFAULT 1,
                  outcome               VARCHAR(16)   NOT NULL,
                  error_kind            VARCHAR(64)   NULL,
                  cost_micros           BIGINT        NOT NULL DEFAULT 0,
                  price_input_snapshot  DECIMAL(12,4) NOT NULL DEFAULT 0,
                  price_output_snapshot DECIMAL(12,4) NOT NULL DEFAULT 0,
                  called_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        repository = new LlmCallRepository(jdbc);
    }

    @Test
    @DisplayName("写入后能按日×用途聚合出调用次数/token/成本")
    void aggregatesByDayAndPurpose() {
        repository.insert(call("explain", "primary", "deepseek-chat", 100, 20, "OK", 1000, 360));
        repository.insert(call("explain", "primary", "deepseek-chat", 200, 40, "OK", 1500, 720));
        repository.insert(call("harvest", "primary", "deepseek-chat", 300, 60, "OK", 2000, 1080));

        List<Map<String, Object>> rows = repository.dailyByPurpose(7);

        assertThat(rows).hasSize(2);
        Map<String, Object> explainRow = rows.stream()
                .filter(r -> "explain".equals(r.get("purpose"))).findFirst().orElseThrow();
        assertThat(((Number) explainRow.get("calls")).intValue())
                .as("同一天同用途同结果要合并成一行")
                .isEqualTo(2);
        assertThat(((Number) explainRow.get("tokens")).intValue()).isEqualTo(360);
        assertThat(((Number) explainRow.get("cost_micros")).longValue()).isEqualTo(1080);
        assertThat(((Number) explainRow.get("avg_latency_ms")).longValue())
                .as("1250ms 平均（1000 和 1500）")
                .isEqualTo(1250);
    }

    @Test
    @DisplayName("outcome 分开统计：失败调用不被合并进成功里（否则失败率永远算不出来）")
    void separatesByOutcome() {
        repository.insert(call("explain", "primary", "m", 10, 5, "OK", 100, 60));
        repository.insert(call("explain", "primary", "m", 0, 0, "FAILED", 100, 0));

        List<Map<String, Object>> rows = repository.dailyByPurpose(7);
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactlyInAnyOrder("OK", "FAILED");
    }

    @Test
    @DisplayName("总量汇总含失败数与重试成功数")
    void totalsIncludeFailuresAndRetries() {
        repository.insert(call("explain", "primary", "m", 10, 5, "OK", 100, 60));
        repository.insert(call("a", "primary", "m", 10, 5, "RETRIED", 100, 60));
        repository.insert(call("b", "primary", "m", 0, 0, "FAILED", 100, 0));

        Map<String, Object> totals = repository.totals();

        assertThat(((Number) totals.get("calls")).longValue()).isEqualTo(3);
        assertThat(((Number) totals.get("failures")).longValue())
                .as("RETRY 不算失败，只有最终 FAILED 算")
                .isEqualTo(1);
        assertThat(((Number) totals.get("retried")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口外的记录不计入（避免用全表历史判断'现在挂没挂'）")
    void windowExcludesOldRows() {
        repository.insert(call("explain", "primary", "m", 10, 5, "OK", 100, 60));
        jdbc.update("UPDATE recon_llm_call SET called_at = DATEADD('DAY', -30, CURRENT_TIMESTAMP)");

        assertThat(repository.since(7)).isEmpty();
        assertThat(repository.since(60)).hasSize(1);
    }

    @Test
    @DisplayName("失败率统计只数 FAILED，把 RETRY 排除在外")
    void failureStatsCountOnlyFinalFailures() {
        repository.insert(call("explain", "primary", "m", 10, 5, "OK", 100, 60));
        repository.insert(call("explain", "primary", "m", 0, 0, "RETRY", 100, 0));
        repository.insert(call("explain", "primary", "m", 0, 0, "FAILED", 100, 0));

        int[] stats = repository.failureStats(60);
        assertThat(stats[0]).as("总记录数").isEqualTo(3);
        assertThat(stats[1]).as("最终失败数").isEqualTo(1);
    }

    @Test
    @DisplayName("记账失败只返回 false，不抛异常（账本是旁路）")
    void insertQuietlySwallowsErrors() {
        jdbc.execute("DROP TABLE recon_llm_call");
        assertThat(repository.insertQuietly(call("x", "primary", "m", 1, 1, "OK", 10, 6))).isFalse();
    }

    private static LlmCall call(String purpose, String provider, String model,
                               int prompt, int completion, String outcome, long latency, long costMicros) {
        return new LlmCall(null, purpose, provider, model, prompt, completion, prompt + completion,
                latency, 1, outcome, null, costMicros, 2.0, 8.0, null);
    }
}
