package io.github.dolow.reconlens.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 安全态势的可观测出口（清单 3.5）。
 *
 * <p>{@link StartupSafety} 决定"能不能起来"，这个类回答"起来之后现在是什么状态"。
 * 两件事缺一不可：拒绝启动挡住了最坏情况，但如果部署者显式开了逃生门（
 * {@code require-api-key=false}），服务会带着开放写接口运行 —— 此时必须有一个
 * <b>能被监控告警的数值</b>，而不是只有启动日志里一条 WARN（那条 WARN 在日志轮转后就找不到了）。
 *
 * <p>暴露的指标：
 * <ul>
 *   <li>{@code recon.security.write_auth_enforced}：1 = 写接口强制校验密钥，0 = 匿名可调；</li>
 *   <li>{@code recon.security.review_auth_enforced}：同上，针对规则审批接口；</li>
 *   <li>{@code recon.security.listening_external}：1 = 绑定了非回环地址（对外可达）。</li>
 * </ul>
 *
 * <p>告警规则建议：{@code write_auth_enforced == 0 且 listening_external == 1} → 立即告警。
 * 这个组合就是"裸奔对外"的定义。
 */
@Component
public class SecurityPosture {

    private final boolean writeAuthEnforced;
    private final boolean reviewAuthEnforced;
    private final boolean listeningExternal;
    private final String listenAddress;

    public SecurityPosture(ReconProperties properties, Environment environment,
                           ObjectProvider<MeterRegistry> registryProvider) {
        this.writeAuthEnforced = properties.getSecurity().enforced();
        this.reviewAuthEnforced = properties.getReview().getApiKey() != null
                && !properties.getReview().getApiKey().isBlank();

        String address = environment.getProperty("server.address", "").trim();
        this.listenAddress = address.isEmpty() ? "(unspecified)" : address;
        // 未设置 = Spring Boot 默认绑 0.0.0.0，但那是"本地开发"最常见形态，
        // 判成对外会让告警天天响；显式绑非回环才算部署者表达了对外意图。
        this.listeningExternal = !address.isEmpty() && !StartupSafety.isLoopback(address);

        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return; // 没接 metrics 的部署照样能跑，只是少这三个数
        }
        Gauge.builder("recon.security.write_auth_enforced", this,
                        p -> p.writeAuthEnforced ? 1.0 : 0.0)
                .description("1=写接口强制校验密钥, 0=匿名可调")
                .register(registry);
        Gauge.builder("recon.security.review_auth_enforced", this,
                        p -> p.reviewAuthEnforced ? 1.0 : 0.0)
                .description("1=规则审批接口强制校验密钥, 0=一律拒绝/未上锁")
                .register(registry);
        Gauge.builder("recon.security.listening_external", this,
                        p -> p.listeningExternal ? 1.0 : 0.0)
                .description("1=显式绑定了非回环地址")
                .register(registry);
    }

    public boolean writeAuthEnforced() {
        return writeAuthEnforced;
    }

    public boolean reviewAuthEnforced() {
        return reviewAuthEnforced;
    }

    public boolean listeningExternal() {
        return listeningExternal;
    }

    public String listenAddress() {
        return listenAddress;
    }

    /** 给 health 详情/诊断端点用的一句话。 */
    public String summary() {
        return "写接口鉴权=" + (writeAuthEnforced ? "已强制" : "匿名可调")
                + "；审批接口鉴权=" + (reviewAuthEnforced ? "已强制" : "未配置(拒绝)")
                + "；监听地址=" + listenAddress;
    }
}
