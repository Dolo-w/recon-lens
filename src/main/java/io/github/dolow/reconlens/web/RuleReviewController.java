package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.rule.RuleChange;
import io.github.dolow.reconlens.rule.RuleLifecycleService;
import io.github.dolow.reconlens.store.ApprovalConfirmationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 闸门 3 的审批入口。<b>只存在于 REST</b>，不注册 MCP 工具 ——
 * MCP 工具的调用方就是 Agent，做成工具等于给它开一条无需人参与的发布通道，闸门 3 名存实亡。
 *
 * <p>两道门禁：
 * <ul>
 *   <li>{@code X-Api-Key} 必须等于 {@code recon.review.api-key}；<b>没配就一律拒绝</b>（fail-closed），
 *       因为"忘了配密钥"的后果不该是"谁都能批"；</li>
 *   <li>{@code X-Operator} 必填 —— 审计链上没有操作人的审批等于没有审批。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rule-reviews")
public class RuleReviewController {

    private final RuleLifecycleService lifecycle;
    private final ReconProperties properties;

    public RuleReviewController(RuleLifecycleService lifecycle, ReconProperties properties) {
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    /** 审核页需要的东西：规则 diff（字段级）、当前状态、能不能跳过试算。 */
    @GetMapping("/{code}/{version}")
    public Map<String, Object> review(@PathVariable String code, @PathVariable int version,
                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        requireApiKey(apiKey);
        RuleLifecycleService.Review review = lifecycle.review(code, version);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", review.code());
        view.put("version", review.version());
        view.put("lifecycle", review.lifecycle());
        view.put("baseline", review.baseDescription());
        view.put("specSha256", review.specSha256());
        view.put("reviewedSpecSha", review.reviewedSpecSha());
        view.put("skippable", review.skippable());
        view.put("semantic", review.change().semantic());
        view.put("changedPaths", review.change().changedPaths());
        view.put("spec", review.spec());
        return view;
    }

    @PostMapping("/{code}/{version}/submit")
    public Map<String, Object> submit(@PathVariable String code, @PathVariable int version,
                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                      @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        lifecycle.submit(code, version, operator);
        return Map.of("code", code, "version", version, "lifecycle", "REVIEWING");
    }

    /** 发审批 token：绑定 (code, version, 当前内容哈希)。 */
    @PostMapping("/{code}/{version}/token")
    public Map<String, Object> token(@PathVariable String code, @PathVariable int version,
                                     @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                     @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        requireOperator(operator);
        String token = lifecycle.issueToken(code, version);
        return Map.of("code", code, "version", version, "approvalToken", token);
    }

    /**
     * 签发人工确认码：给"Agent 代办审批"用的那一半凭据。
     *
     * <p>用户在 REST 这一侧点确认拿码（这里带 api-key，本来就是人工通道），
     * 把码转述给 Agent，Agent 调 MCP 的 {@code recon_submit_approval} 时必须带上。
     * 码一次性、{@link io.github.dolow.reconlens.store.ApprovalConfirmationRepository#TTL} 过期、
     * 绑定 (code, version, decision)。
     */
    @PostMapping("/{code}/{version}/confirmation")
    public Map<String, Object> confirmation(@PathVariable String code, @PathVariable int version,
                                            @RequestBody(required = false) DecisionRequest request,
                                            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                            @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        String decision = request == null || request.decision() == null ? "APPROVE" : request.decision();
        ApprovalConfirmationRepository.Confirmation issued =
                lifecycle.issueConfirmation(code, version, decision, operator);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", code);
        view.put("version", version);
        view.put("decision", issued.decision());
        view.put("confirmationCode", issued.code());
        view.put("issuedBy", issued.createdBy());
        view.put("expiresAt", issued.expiresAt());
        view.put("ttlSeconds", ApprovalConfirmationRepository.TTL.toSeconds());
        view.put("hint", "把 confirmationCode 原样转给 Agent，由它调 recon_submit_approval；"
                + "一次确认只能用一次，过期或已用过都会拒绝");
        return view;
    }

    @PostMapping("/{code}/{version}/approve")
    public Map<String, Object> approve(@PathVariable String code, @PathVariable int version,
                                       @RequestBody ApproveRequest request,
                                       @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        // 试算结论只从 recon_rule.trial_* 读：接口不再接受 verdict / coverage。
        // 接受它们等于允许"人工声称 PASS + FULL"，而闸门 2 的全部意义就是不让这句话由人说了算。
        lifecycle.approve(code, version, operator, request.token(), request.note());
        return Map.of("code", code, "version", version, "lifecycle", "ACTIVE");
    }

    /** 跳过试算：只允许"只改名字 / 描述"，资格由服务端字段级 diff 判定（不认自述）。 */
    @PostMapping("/{code}/{version}/skip")
    public Map<String, Object> skip(@PathVariable String code, @PathVariable int version,
                                    @RequestBody SkipRequest request,
                                    @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                    @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        lifecycle.skipTrial(code, version, operator, request.reason());
        return Map.of("code", code, "version", version, "trialVerdict", "SKIPPED");
    }

    @PostMapping("/{code}/{version}/reject")
    public Map<String, Object> reject(@PathVariable String code, @PathVariable int version,
                                      @RequestBody RejectRequest request,
                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                      @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireApiKey(apiKey);
        lifecycle.reject(code, version, operator, request.reason());
        return Map.of("code", code, "version", version, "lifecycle", "REJECTED");
    }

    /**
     * 批准请求。<b>只有 token + 签字说明</b> —— 试算结论不在这里，
     * 它由闸门 2 写进 {@code recon_rule.trial_*}，审批时从库里读。
     */
    public record ApproveRequest(String token, String note) {
    }

    public record SkipRequest(String reason) {
    }

    public record RejectRequest(String reason) {
    }

    public record DecisionRequest(String decision) {
    }

    private void requireApiKey(String apiKey) {
        String configured = properties.getReview().getApiKey();
        if (configured == null || configured.isBlank()) {
            throw new ReviewForbiddenException("未配置 recon.review.api-key，审批接口已关闭"
                    + "（审批是能改\"钱对不对\"判定口径的操作，没配密钥时一律拒绝）");
        }
        if (apiKey == null || !MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), configured.getBytes(StandardCharsets.UTF_8))) {
            throw new ReviewForbiddenException("X-Api-Key 无效");
        }
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ReviewForbiddenException("必须带 X-Operator：审计链上没有操作人的审批等于没有审批");
        }
    }

    /** 审批鉴权失败：403，不是 500 —— 调用方要能区分"没权限"和"服务坏了"。 */
    public static class ReviewForbiddenException extends RuntimeException {
        public ReviewForbiddenException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(ReviewForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ReviewForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "FORBIDDEN", "message", e.getMessage()));
    }

    @ExceptionHandler(RuleException.class)
    public ResponseEntity<Map<String, Object>> badRequest(RuleException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "RULE_REVIEW_REJECTED", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TRIAL_STATE", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_TRIAL_STATE", "message", e.getMessage()));
    }
}
