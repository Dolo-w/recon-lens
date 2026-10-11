package io.github.dolow.reconlens;

import io.github.dolow.reconlens.config.ReconProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties(ReconProperties.class)
public class ReconLensApplication {

    public static void main(String[] args) {
        silenceLogbackStatusInMcpMode(args);
        SpringApplication.run(ReconLensApplication.class, args);
    }

    /**
     * MCP 走 stdio，stdout 就是 JSON-RPC 通道；而 logback 自身的状态信息（版本、configurator 清单等）
     * 默认打印到 stdout，那几十行会直接插进协议流里。必须在 Spring 初始化日志系统之前掐掉，
     * 所以这里用系统属性而不是配置文件里的 statusListener —— 后者生效太晚。
     */
    private static void silenceLogbackStatusInMcpMode(String[] args) {
        if (mcpProfileActive(args)) {
            System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
        }
    }

    private static boolean mcpProfileActive(String[] args) {
        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        for (String arg : args) {
            if (arg.startsWith("--spring.profiles.active=")) {
                profiles = arg.substring("--spring.profiles.active=".length());
            }
        }
        return profiles != null && Arrays.stream(profiles.split(","))
                .map(String::trim)
                .anyMatch("mcp"::equals);
    }
}
