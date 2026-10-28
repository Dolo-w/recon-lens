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
}
