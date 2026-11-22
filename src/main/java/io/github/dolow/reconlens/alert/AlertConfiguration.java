package io.github.dolow.reconlens.alert;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 告警调度开关。
 *
 * <p><b>为什么单独一个配置类而不是挂在 {@code AgentConfiguration} 上</b>：那个配置类只在
 * {@code recon.agent.enabled=true} 时生效。可观测性是基础设施，不该因为"没开 Agent"就消失 ——
 * 恰恰相反，出问题时最先要知道的就是它。
 */
@Configuration
@EnableScheduling
public class AlertConfiguration {
}
