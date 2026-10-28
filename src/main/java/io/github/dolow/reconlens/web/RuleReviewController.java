package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleGateRejectedException;
import io.github.dolow.reconlens.rule.RuleChange;
import io.github.dolow.reconlens.rule.RuleLifecycleService;
import io.github.dolow.reconlens.rule.RuleTrialRunner;
import io.github.dolow.reconlens.store.ApprovalConfirmationRepository;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.server.ResponseStatusException;

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
    private final ObjectProvider<RuleTrialRunner> trialRunner;

    public RuleReviewController(RuleLifecycleService lifecycle, ReconProperties properties,
                                ObjectProvider<RuleTrialRunner> trialRunner) {
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.trialRunner = trialRunner;
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

    /**
     * 同步跑一次试算（闸门 2 的触发口）。
     *
     * <p>与 {@code recon_define_rule} 的"落草稿 + 排队试算"不同：这里<b>同步</b>跑完并返回结论。
     * 被闸门 1 拒的时候，把结果说成"任务已投递"等于把一次拒绝伪装成一次提交，所以这里直接
     * 以 {@code 422 + violations[]} 返回（见 {@code GateRejectionHandler}）。
     *
     * <p>agent 未启用时没有实现，回 501 并说明原因：不伪装成"试算中"。
     */
    @PostMapping("/{code}/{version}/trial")
    public Map<String, Object> trial(@PathVariable String code, @PathVariable int version,
                                     @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        requireApiKey(apiKey);
        RuleTrialRunner runner = trialRunner.getIfAvailable();
        if (runner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "试算未启用（recon.agent.enabled=false）：闸门 2 的实现随 Agent 子系统装配");
        }
        RuleTrialRunner.Result result = runner.run(code, version);
        return Map.of("code", code, "version", version, "state", result.state());
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
            throw new ReviewForbiddenException("审批接口已关闭：服务端没配 recon.review.api-key",
                    "启动时加 --recon.review.api-key=你的口令（或环境变量 RECON_REVIEW_API_KEY）再重启。"
                            + "没配密钥时一律拒绝是刻意的 fail-closed，不是配置坏了。");
        }
        if (apiKey == null || !MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8), configured.getBytes(StandardCharsets.UTF_8))) {
            throw new ReviewForbiddenException("X-Api-Key 与服务端配的审批密钥不一致",
                    "页面上「审批密钥」要填启动参数 --recon.review.api-key 的值，一字不差（大小写、空格都算）；"
                            + "不知道当前值就去看启动这个实例的命令行。");
        }
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ReviewForbiddenException("请求没带操作人（X-Operator）",
                    "在页面右上角「操作人」里填一个名字再点一次；用 curl 时加头 "
                            + "-H \"X-Operator: 你的名字\"。审计链上没有操作人的审批等于没有审批。");
        }
    }

    /** 审批鉴权失败：403，不是 500 —— 调用方要能区分"没权限"和"服务坏了"。 */
    public static class ReviewForbiddenException extends RuntimeException {
        private final String hint;

        public ReviewForbiddenException(String message) {
            this(message, null);
        }

        public ReviewForbiddenException(String message, String hint) {
            super(message);
            this.hint = hint;
        }

        public String hint() {
            return hint;
        }
    }

    @ExceptionHandler(ReviewForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(ReviewForbiddenException e,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        // 403 只留在响应里没人看得到原因；日志里记一笔"谁、哪条路径、为什么被拒"
        org.slf4j.LoggerFactory.getLogger(RuleReviewController.class).warn(
                "审批鉴权失败 {} {}：{}（带密钥：{}，带操作人：{}）",
                request.getMethod(), request.getRequestURI(), e.getMessage(),
                request.getHeader("X-Api-Key") != null, request.getHeader("X-Operator") != null);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", e.getMessage());
        if (e.hint() != null) {
            body.put("hint", e.hint());
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * 闸门 1 拒绝回 422（不是下面那个"请求有问题"的 400）。
     *
     * <p>本类的 {@code RuleException} handler 比 advice 优先，而闸门拒绝异常是它的子类，
     * 不在这里显式拦一道就会被降级成 400 —— 调用方于是分不清"改规则"和"改请求"。
     */
    @ExceptionHandler(RuleGateRejectedException.class)
    public ResponseEntity<Map<String, Object>> gateRejected(RuleGateRejectedException e) {
        return GateRejectionHandler.response(e);
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
