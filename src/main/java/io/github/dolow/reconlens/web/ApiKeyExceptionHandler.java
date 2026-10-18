package io.github.dolow.reconlens.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 让"缺密钥"返回一句能照着做的 403，而不是 Spring 默认那页没有 message 的 JSON。 */
@RestControllerAdvice
public class ApiKeyExceptionHandler {

    @ExceptionHandler(ApiKeyForbiddenException.class)
    public ResponseEntity<Map<String, Object>> onForbidden(ApiKeyForbiddenException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "api_key_required");
        body.put("action", e.action());
        body.put("message", "该接口需要 X-Api-Key（值取 recon.security.api-key）：" + e.action()
                + "。没配置 recon.security.api-key 时这些写接口不鉴权，配置后就一律要带密钥");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
