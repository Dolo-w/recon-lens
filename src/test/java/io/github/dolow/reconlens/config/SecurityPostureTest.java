package io.github.dolow.reconlens.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清单 3.5：安全态势必须是"能被监控看见的数"，而不是只有启动日志里一条 WARN。
 *
 * <p>这条的价值在逃生门场景：部署者显式设了 {@code require-api-key=false}，服务正常起来，
 * 日志刷过去就没人记得。此时 {@code recon.security.write_auth_enforced=0} 是唯一能让告警系统
 * 抓住"这个实例在裸奔"的信号。
 */
class SecurityPostureTest {

    private static SecurityPosture posture(String apiKey, String reviewKey, String address) {
        ReconProperties properties = new ReconProperties();
        properties.getSecurity().setApiKey(apiKey == null ? "" : apiKey);
        properties.getReview().setApiKey(reviewKey == null ? "" : reviewKey);

        MockEnvironment environment = new MockEnvironment();
        if (address != null) {
            environment.setProperty("server.address", address);
        }
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("meterRegistry", new SimpleMeterRegistry());
        return new SecurityPosture(properties, environment,
                factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    @Test
    @DisplayName("配了密钥 → 指标为 1")
    void enforcedReportsOne() {
        SecurityPosture posture = posture("k", "rk", "127.0.0.1");

        assertThat(posture.writeAuthEnforced()).isTrue();
        assertThat(posture.reviewAuthEnforced()).isTrue();
    }

    @Test
    @DisplayName("没配密钥 → 指标为 0（这是告警该抓的状态）")
    void notEnforcedReportsZero() {
        SecurityPosture posture = posture(null, null, "127.0.0.1");

        assertThat(posture.writeAuthEnforced()).isFalse();
        assertThat(posture.reviewAuthEnforced()).isFalse();
    }

    @Test
    @DisplayName("显式绑非回环地址 → listening_external=1；裸奔组合可被告警规则识别")
    void externalBindingDetected() {
        SecurityPosture posture = posture(null, "rk", "0.0.0.0");

        assertThat(posture.listeningExternal()).isTrue();
        // 告警规则：write_auth_enforced==0 且 listening_external==1 → 裸奔对外
        assertThat(posture.writeAuthEnforced()).isFalse();
        assertThat(posture.listeningExternal()).isTrue();
    }

    @Test
    @DisplayName("绑回环 / 未设置 → listening_external=0（不误报本地开发）")
    void loopbackAndUnsetAreNotExternal() {
        assertThat(posture("k", "rk", "127.0.0.1").listeningExternal()).isFalse();
        assertThat(posture("k", "rk", null).listeningExternal()).isFalse();
        assertThat(posture("k", "rk", null).listenAddress()).isEqualTo("(unspecified)");
    }

    @Test
    @DisplayName("没有 metrics 时也不炸（Actuator 被裁掉的部署照样能跑）")
    void worksWithoutMeterRegistry() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        SecurityPosture posture = new SecurityPosture(new ReconProperties(), new MockEnvironment(),
                factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        assertThat(posture.summary()).contains("写接口鉴权");
    }
}
