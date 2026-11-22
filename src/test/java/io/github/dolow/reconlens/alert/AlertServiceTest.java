package io.github.dolow.reconlens.alert;

import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.config.SecurityPosture;
import io.github.dolow.reconlens.domain.Alert;
import io.github.dolow.reconlens.domain.LlmCall;
import io.github.dolow.reconlens.store.AlertRepository;
import io.github.dolow.reconlens.store.LlmCallRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 告警规则的行为验证（清单 P0-6）——真 H2、真 SQL、真 HTTP webhook。
 *
 * <p>重点验证三个容易做错的地方：
 * <ol>
 *   <li><b>不刷屏</b>：同一条告警连续两轮评估只能产生一条；</li>
 *   <li><b>能恢复</b>：条件不再满足时置 RESOLVED；</li>
 *   <li><b>样本不足不误判</b>：没流量既不开新告警，也不把已有告警当成"已恢复"。</li>
 * </ol>
 */
class AlertServiceTest {

    private JdbcTemplate jdbc;
    private AlertRepository alertRepository;
    private LlmCallRepository llmCalls;
    private SecurityPosture posture;
    private ReconProperties properties;
    private AlertService service;
    private HttpServer webhook;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:alert_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE recon_alert (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  alert_key VARCHAR(64) NOT NULL,
                  severity VARCHAR(16) NOT NULL,
                  message VARCHAR(500) NOT NULL,
                  metric_value DECIMAL(20,4) NOT NULL DEFAULT 0,
                  threshold_value DECIMAL(20,4) NOT NULL DEFAULT 0,
                  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                  notified BOOLEAN NOT NULL DEFAULT FALSE,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  resolved_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE recon_llm_call (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  purpose VARCHAR(32) NOT NULL,
                  provider VARCHAR(32) NOT NULL,
                  model VARCHAR(128) NOT NULL,
                  prompt_tokens INT NOT NULL DEFAULT 0,
                  completion_tokens INT NOT NULL DEFAULT 0,
                  total_tokens INT NOT NULL DEFAULT 0,
                  latency_ms BIGINT NOT NULL DEFAULT 0,
                  attempt INT NOT NULL DEFAULT 1,
                  outcome VARCHAR(16) NOT NULL,
                  error_kind VARCHAR(64) NULL,
                  cost_micros BIGINT NOT NULL DEFAULT 0,
                  price_input_snapshot DECIMAL(12,4) NOT NULL DEFAULT 0,
                  price_output_snapshot DECIMAL(12,4) NOT NULL DEFAULT 0,
                  called_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        alertRepository = new AlertRepository(jdbc);
        llmCalls = new LlmCallRepository(jdbc);
        posture = mock(SecurityPosture.class);
        when(posture.writeAuthEnforced()).thenReturn(true);
        when(posture.listeningExternal()).thenReturn(false);
        when(posture.listenAddress()).thenReturn("127.0.0.1");

        properties = new ReconProperties();
        properties.getAlert().setEnabled(true);
        properties.getAlert().setLlmFailureMinCalls(5);
        properties.getAlert().setLlmFailureRateThreshold(0.3);

        service = new AlertService(alertRepository, llmCalls, posture, properties);
    }

    @AfterEach
    void tearDown() {
        if (webhook != null) {
            webhook.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // LLM 失败率
    // ------------------------------------------------------------------

    @Test
    @DisplayName("失败率超阈值 → 产生 CRITICAL 告警，且同一轮评估只产生一条")
    void raisesOnHighFailureRate() {
        recordCalls(10, 5); // 10 次里 5 次失败 = 50%

        List<Alert> raised = service.evaluate();

        assertThat(raised).extracting(Alert::alertKey).contains(AlertService.LLM_FAILURE_RATE);
        assertThat(alertRepository.countOpen()).isEqualTo(1);
        assertThat(raised.get(0).severity()).isEqualTo(Alert.SEVERITY_CRITICAL);
    }

    @Test
    @DisplayName("连续两轮评估不重复开告警（否则每 5 分钟刷一条，告警就没人看了）")
    void doesNotDuplicateOnRepeatedEvaluation() {
        recordCalls(10, 5);

        service.evaluate();
        List<Alert> second = service.evaluate();

        assertThat(second).as("第二轮不该再产生新告警").isEmpty();
        assertThat(alertRepository.countOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("失败率降下来 → 置 RESOLVED，openCount 归零")
    void resolvesWhenRateRecovers() {
        recordCalls(10, 5);
        service.evaluate();
        assertThat(alertRepository.countOpen()).isEqualTo(1);

        // 再灌 10 条全成功，把整体失败率压到 25%（低于 30% 阈值）
        recordCalls(10, 0);
        service.evaluate();

        assertThat(alertRepository.countOpen()).isZero();
        assertThat(alertRepository.listRecent(10))
                .filteredOn(a -> AlertService.LLM_FAILURE_RATE.equals(a.alertKey()))
                .anyMatch(a -> Alert.RESOLVED.equals(a.status()));
    }

    @Test
    @DisplayName("样本不足时既不报警也不解除 —— '没流量'不等于'已恢复'")
    void insufficientSamplesKeepsExistingAlert() {
        recordCalls(10, 5);
        service.evaluate();
        assertThat(alertRepository.countOpen()).isEqualTo(1);

        // 把记录都推到窗口外：样本量归零
        jdbc.update("UPDATE recon_llm_call SET called_at = DATEADD('HOUR', -3, CURRENT_TIMESTAMP)");

        List<Alert> raised = service.evaluate();

        assertThat(raised).isEmpty();
        assertThat(alertRepository.countOpen())
                .as("故障还在，只是没人来调 —— 不能当成已恢复")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("阈值边界：恰好等于阈值也算超（>=，不是 >）")
    void triggersAtExactlyThreshold() {
        recordCalls(10, 3); // 30% == 0.3
        assertThat(service.evaluate()).extracting(Alert::alertKey)
                .contains(AlertService.LLM_FAILURE_RATE);
    }

    // ------------------------------------------------------------------
    // 安全裸奔
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写接口匿名可调 + 对外监听 → CRITICAL 安全告警")
    void raisesOnOpenWriteEndpoint() {
        when(posture.writeAuthEnforced()).thenReturn(false);
        when(posture.listeningExternal()).thenReturn(true);
        when(posture.listenAddress()).thenReturn("0.0.0.0");

        assertThat(service.evaluate()).extracting(Alert::alertKey)
                .contains(AlertService.SECURITY_OPEN_WRITE);
        assertThat(alertRepository.findOpen(AlertService.SECURITY_OPEN_WRITE)).isPresent();
    }

    @Test
    @DisplayName("只对外监听但配了密钥 → 不告警（正常生产形态）")
    void noAlertWhenAuthEnforced() {
        when(posture.writeAuthEnforced()).thenReturn(true);
        when(posture.listeningExternal()).thenReturn(true);

        assertThat(service.evaluate()).extracting(Alert::alertKey)
                .doesNotContain(AlertService.SECURITY_OPEN_WRITE);
    }

    // ------------------------------------------------------------------
    // 成本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("当日成本超阈值 → WARNING（防批量任务跑飞）")
    void raisesOnDailyCost() {
        properties.getAlert().setDailyCostYuanThreshold(1.0);
        // 2 元 = 2_000_000 micros
        llmCalls.insert(new LlmCall(null, "harvest", "primary", "m", 100, 100, 200,
                100, 1, "OK", null, 2_000_000L, 2.0, 8.0, null));

        assertThat(service.evaluate()).extracting(Alert::alertKey).contains(AlertService.DAILY_COST);
    }

    @Test
    @DisplayName("阈值配 0 = 不检查成本（有些部署不想被它打扰）")
    void dailyCostCheckCanBeDisabled() {
        properties.getAlert().setDailyCostYuanThreshold(0);
        llmCalls.insert(new LlmCall(null, "harvest", "primary", "m", 100, 100, 200,
                100, 1, "OK", null, 99_000_000L, 2.0, 8.0, null));

        assertThat(service.evaluate()).extracting(Alert::alertKey)
                .doesNotContain(AlertService.DAILY_COST);
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    @Test
    @DisplayName("新告警推 webhook 并标记 notified；推送失败不影响落库")
    void pushesWebhookAndMarksNotified() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        webhook.start();
        properties.getAlert().setWebhookUrl("http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook");

        recordCalls(10, 5);
        service.evaluate();

        assertThat(received.get())
                .as("webhook 应收到 JSON，含 alertKey 与 severity")
                .isNotNull()
                .contains("llm_failure_rate")
                .contains("CRITICAL");
        assertThat(alertRepository.listOpen().get(0).notified())
                .as("推送成功后要标记已通知，避免重复轰炸")
                .isTrue();
    }

    @Test
    @DisplayName("webhook 挂掉时告警仍落库（通知渠道不能拖垮告警本身）")
    void webhookFailureStillPersistsAlert() {
        properties.getAlert().setWebhookUrl("http://127.0.0.1:1/nonexistent");

        recordCalls(10, 5);
        List<Alert> raised = service.evaluate();

        assertThat(raised).hasSize(1);
        assertThat(alertRepository.countOpen()).isEqualTo(1);
        assertThat(alertRepository.listOpen().get(0).notified()).isFalse();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 灌 total 条调用，其中 failed 条是最终失败。 */
    private void recordCalls(int total, int failed) {
        for (int i = 0; i < total; i++) {
            String outcome = i < failed ? "FAILED" : "OK";
            llmCalls.insert(new LlmCall(null, "explain", "primary", "m", 10, 5, 15,
                    100, 1, outcome, null, 60, 2.0, 8.0, null));
        }
    }
}
