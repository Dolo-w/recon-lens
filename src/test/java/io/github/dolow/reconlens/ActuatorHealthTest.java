package io.github.dolow.reconlens;

import io.github.dolow.reconlens.llm.LlmMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 清单 P0-1 / 8.4 / 8.6：可观测性三件套（健康检查 + micrometer 指标 + Prometheus 抓取）。
 *
 * <p>这类"基础设施接线"最容易出的问题是：配置写了但没生效、依赖加了但 Bean 没配上。
 * 只有真的发一次 HTTP 才能证明它通了——所以这里走真实 Spring 上下文，不 mock。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:actuator_health;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private LlmMetrics llmMetrics;

    @Test
    @DisplayName("/actuator/health 返回 UP —— 编排系统靠它判断实例能不能接流量")
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("liveness / readiness 分开暴露 —— 滚动重启不会误杀还在收尾的实例")
    void exposesLivenessAndReadinessProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("默认不吐内部细节（没接认证时 show-details=when_authorized 等于不泄漏）")
    void healthDetailsAreNotPublicByDefault() throws Exception {
        String body = mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .as("只应有 status，不应带 db/diskSpace 等组件明细")
                .doesNotContain("components");
    }

    @Test
    @DisplayName("LLM 调用打点后能在 registry 里查到 —— 指标不是配了就有，要有数据流")
    void llmMetricsAreRegistered() {
        llmMetrics.record("explain", "deepseek-chat", "primary", "OK", 120, 100, 40);

        assertThat(registry.find("recon.llm.calls")
                .tag("purpose", "explain").counter())
                .as("调用计数器必须真的注册上")
                .isNotNull();
        assertThat(registry.find("recon.llm.calls")
                .tag("purpose", "explain").counter().count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(registry.find("recon.llm.latency")
                .tag("purpose", "explain").timer())
                .as("延迟直方图/计时器必须真的注册上")
                .isNotNull();
        assertThat(registry.find("recon.llm.tokens")
                .tag("kind", "input").counter())
                .as("token 消耗速率是成本斜率，必须单独可观测")
                .isNotNull();
    }

    @Test
    @DisplayName("Prometheus 端点能抓到自定义指标（监控系统读的就是这个格式）")
    void prometheusEndpointExposesLlmMetrics() throws Exception {
        llmMetrics.record("harvest", "deepseek-chat", "primary", "OK", 200, 500, 100);

        String text = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(registry)
                .as("registry 必须是 Prometheus 实现；退化成 SimpleMeterRegistry 时 /actuator/prometheus 会 404")
                .isInstanceOf(io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class);
        assertThat(text).contains("recon_llm_calls");
        assertThat(text).contains("recon_llm_latency");
    }
}
