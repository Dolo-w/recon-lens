package io.github.dolow.reconlens.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 启动自检的口径：生产档必须上锁，开发档必须能起。
 *
 * <p>这些用例守的不是"某个开关的默认值"，而是<b>报错的方向</b>：
 * 配错时起不来（fail fast），而不是起来了然后静默地匿名可调、或者让机器生成的规则免审生效。
 */
class StartupSafetyTest {

    private static ReconProperties properties(String apiKey) {
        ReconProperties properties = new ReconProperties();
        properties.getSecurity().setApiKey(apiKey == null ? "" : apiKey);
        return properties;
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void 生产档缺密钥时启动失败() {
        StartupSafety safety = new StartupSafety(properties(null), environment("prod"));

        IllegalStateException e = assertThrows(IllegalStateException.class, safety::afterPropertiesSet);

        assertTrue(e.getMessage().contains("recon.security.api-key"), e.getMessage());
        assertTrue(e.getMessage().contains("prod"), "报错里要带上是哪个 profile：" + e.getMessage());
    }

    @Test
    void 生产档两把锁都配了才能起() {
        ReconProperties properties = properties("k-1");
        properties.getReview().setApiKey("review-key");

        new StartupSafety(properties, environment("prod")).afterPropertiesSet();
    }

    @Test
    void 生产档只配数据面密钥不配审批密钥就起不来() {
        // 用户口径：置为生产就必填审批 key —— 缺它时闸门 3 永远过不去，规则只能停在 DRAFT，
        // 让服务"起来但批不了"比直接起不来更容易被忽略
        ReconProperties properties = properties("k-1");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupSafety(properties, environment("prod")).afterPropertiesSet());

        assertTrue(e.getMessage().contains("recon.review.api-key"), e.getMessage());
        assertTrue(e.getMessage().contains("两把锁"), e.getMessage());
    }

    @Test
    void 开发档缺密钥仍然能起() {
        StartupSafety safety = new StartupSafety(properties(null), environment());

        safety.afterPropertiesSet();
    }

    @Test
    void 显式要求密钥时开发档也会失败() {
        ReconProperties properties = properties(null);
        properties.getSecurity().setRequireApiKey(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupSafety(properties, environment()).afterPropertiesSet());

        assertTrue(e.getMessage().contains("recon.security.api-key"), e.getMessage());
    }

    @Test
    void 生产档不允许模型规则免审生效() {
        ReconProperties properties = properties("k-1");
        properties.getModel().setRequireApproval(false);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupSafety(properties, environment("prod")).afterPropertiesSet());

        assertTrue(e.getMessage().contains("require-approval"), e.getMessage());
    }

    @Test
    void 开发档允许模型规则免审但要能起() {
        ReconProperties properties = properties("k-1");
        properties.getModel().setRequireApproval(false);

        new StartupSafety(properties, environment()).afterPropertiesSet();
    }

    @Test
    void 生产标记按profile名里的prod识别() {
        assertTrue(StartupSafety.isProduction(java.util.List.of("production")));
        assertTrue(StartupSafety.isProduction(java.util.List.of("prod-mysql")));
        assertTrue(StartupSafety.isProduction(java.util.List.of("mysql", "PROD")));
        assertFalse(StartupSafety.isProduction(java.util.List.of()));
        assertFalse(StartupSafety.isProduction(java.util.List.of("mysql")));
        assertFalse(StartupSafety.isProduction(java.util.List.of("mcp", "test")));
    }

    // ------------------------------------------------------------------
    // 清单 3.5 补强：默认档（无 profile）但对外监听 —— 真实事故场景
    // ------------------------------------------------------------------

    @Test
    void 开发档显式绑定对外地址且没密钥时启动失败() {
        // 事故原型：客户把 jar 拷到服务器 java -jar，没设 profile、没配 key，
        // 绑了 0.0.0.0。改之前只打一条 WARN，服务带着匿名写接口对外跑起来。
        MockEnvironment environment = environment();
        environment.setProperty("server.address", "0.0.0.0");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupSafety(properties(null), environment).afterPropertiesSet());

        assertTrue(e.getMessage().contains("server.address"), e.getMessage());
        assertTrue(e.getMessage().contains("recon.security.api-key"), e.getMessage());
    }

    @Test
    void 开发档绑回环地址仍能起() {
        // 127.0.0.1 / localhost 明确表示"只本机可访问"，是开发常态，不该拦住
        for (String address : new String[]{"127.0.0.1", "localhost", "::1"}) {
            MockEnvironment environment = environment();
            environment.setProperty("server.address", address);
            new StartupSafety(properties(null), environment).afterPropertiesSet();
        }
    }

    @Test
    void 开发档未设置监听地址仍能起_缺省不按对外判() {
        // Spring Boot 默认绑 0.0.0.0，若把"未设置"也判成对外，所有本地开发都会起不来
        new StartupSafety(properties(null), environment()).afterPropertiesSet();
    }

    @Test
    void 开发档对外监听但显式开了逃生门仍能起() {
        MockEnvironment environment = environment();
        environment.setProperty("server.address", "0.0.0.0");
        ReconProperties properties = properties(null);
        properties.getSecurity().setRequireApiKey(false);

        new StartupSafety(properties, environment).afterPropertiesSet();
    }

    @Test
    void 对外监听时配了密钥就能起() {
        MockEnvironment environment = environment();
        environment.setProperty("server.address", "0.0.0.0");
        ReconProperties properties = properties("k-1");
        properties.getReview().setApiKey("review-key");

        new StartupSafety(properties, environment).afterPropertiesSet();
    }

    @Test
    void 回环地址判定() {
        assertTrue(StartupSafety.isLoopback("127.0.0.1"));
        assertTrue(StartupSafety.isLoopback("127.1.2.3"));
        assertTrue(StartupSafety.isLoopback("LocalHost"));
        assertTrue(StartupSafety.isLoopback("[::1]"));
        assertFalse(StartupSafety.isLoopback("0.0.0.0"));
        assertFalse(StartupSafety.isLoopback("10.0.0.8"));
        assertFalse(StartupSafety.isLoopback("recon.example.com"));
    }
}
