package io.github.dolow.reconlens.web;

/** 数据面写接口的密钥缺失或不匹配。统一由 {@link ApiKeyExceptionHandler} 转成 403。 */
public class ApiKeyForbiddenException extends RuntimeException {

    private final String action;

    public ApiKeyForbiddenException(String action) {
        super("该接口需要 X-Api-Key（值取 recon.security.api-key）：" + action);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
