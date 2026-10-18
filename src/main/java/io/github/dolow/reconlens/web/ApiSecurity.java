package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 数据面写接口的密钥校验。
 *
 * <p>为什么单独一个类而不是在每个 Controller 里写 {@code equals}：
 * 审批接口（{@code RuleReviewController}）早就在用 {@link MessageDigest#isEqual} 做常量时间比较，
 * 规则直写接口却是 {@code expected.equals(apiKey)} —— 两处口径不一致，漏掉的那一处就是等时比较缺口。
 * 写接口全部走这里，配了密钥就都拦，没配就都在启动日志里说清楚"现在是不鉴权的"。
 */
@Component
public class ApiSecurity {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurity.class);

    private final ReconProperties properties;

    public ApiSecurity(ReconProperties properties) {
        this.properties = properties;
        if (properties.getSecurity().enforced()) {
            log.info("数据面写接口已启用 X-Api-Key 鉴权（recon.security.api-key 已配置）");
        } else {
            log.warn("未配置 recon.security.api-key：POST /api/jobs、POST /api/models/**、DELETE /api/rules/**"
                    + " 不鉴权。仅建议本机 / 内网使用；对外暴露前请配置该密钥");
        }
    }

    public boolean enforced() {
        return properties.getSecurity().enforced();
    }

    /**
     * 校验写接口的 {@code X-Api-Key}。
     *
     * <p>没配置密钥时放行（并在启动时 WARN 过）—— 这是对旧行为的兼容，
     * 不是"忘记鉴权"：把默认值改成强制，会让所有既有脚本和测试在升级当天全部 403。
     */
    public void requireWrite(String apiKey, String action) {
        if (!enforced()) {
            return;
        }
        String expected = properties.getSecurity().getApiKey().trim();
        if (apiKey == null || !MessageDigest.isEqual(
                apiKey.trim().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiKeyForbiddenException(action);
        }
    }
}
