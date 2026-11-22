package io.github.dolow.reconlens.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 大模型调用的 micrometer 指标（清单 P0-1 + 8.4）。
 *
 * <p><b>为什么单独一个类而不是在客户端里直接打点</b>：客户端要能被单元测试直接 {@code new}，
 * 而 {@code MeterRegistry} 是容器 Bean。分开之后，客户端拿一个可选的 recorder，
 * 测试传 {@link #noop()} 即可，不必启动 Spring。
 *
 * <p>指标口径（命名按 micrometer 约定，小写点分）：
 * <ul>
 *   <li>{@code recon.llm.calls} counter，tag：{@code purpose/model/provider/outcome} ——
 *       既能算总量，也能算失败率（{@code outcome="FAILED"}）；</li>
 *   <li>{@code recon.llm.latency} timer，同上 tag —— p95/p99 延迟；</li>
 *   <li>{@code recon.llm.tokens} counter，tag 多一个 {@code kind=input|output} —— 消耗速率与成本斜率。</li>
 * </ul>
 *
 * <p><b>标签基数风险</b>：{@code purpose} 是代码里写死的枚举级常量（explain/chat/draft/harvest），
 * 不是用户输入，所以不会出现高基数。{@code model} 来自配置，也不是用户输入。这条要守住，
 * 一旦有人把差异内容/文件名塞进 tag，指标系统会被打爆。
 */
@Component
public class LlmMetrics {

    private static final String CALLS = "recon.llm.calls";
    private static final String LATENCY = "recon.llm.latency";
    private static final String TOKENS = "recon.llm.tokens";

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 无容器场景（单测/脚本）用的空实现：照样能调，什么也不记。 */
    public static LlmMetrics noop() {
        return new LlmMetrics(new SimpleMeterRegistry());
    }

    public void record(String purpose, String model, String provider, String outcome,
                       long latencyMs, int promptTokens, int completionTokens) {
        safe(() -> {
            Counter.builder(CALLS)
                    .tag("purpose", nz(purpose))
                    .tag("model", nz(model))
                    .tag("provider", nz(provider))
                    .tag("outcome", nz(outcome))
                    .register(registry)
                    .increment();

            Timer.builder(LATENCY)
                    .tag("purpose", nz(purpose))
                    .tag("model", nz(model))
                    .tag("provider", nz(provider))
                    .tag("outcome", nz(outcome))
                    .register(registry)
                    .record(java.time.Duration.ofMillis(Math.max(0, latencyMs)));

            if (promptTokens > 0) {
                Counter.builder(TOKENS)
                        .tag("purpose", nz(purpose))
                        .tag("kind", "input")
                        .register(registry)
                        .increment(promptTokens);
            }
            if (completionTokens > 0) {
                Counter.builder(TOKENS)
                        .tag("purpose", nz(purpose))
                        .tag("kind", "output")
                        .register(registry)
                        .increment(completionTokens);
            }
        });
    }

    /**
     * 打点失败不影响业务：指标是旁路，绝不能让"注册表出了问题"变成"模型调用失败"。
     * 这在关掉 actuator、或者有人误配 registry 时是真实可能的。
     */
    private void safe(Runnable body) {
        try {
            body.run();
        } catch (RuntimeException ignored) {
            // 旁路账，静默
        }
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
