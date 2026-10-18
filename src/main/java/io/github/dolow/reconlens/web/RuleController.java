package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则接口：既可以看"当前实例能跑哪些对账"，也可以直接定义规则 ——
 * 使用方自定义对账维度和要比的字段，改完立刻生效，不需要改代码或重启。
 *
 * <ul>
 *   <li>{@code GET /api/rules} 最新版本列表</li>
 *   <li>{@code GET /api/rules/{code}} 某条规则的最新版本 + 版本历史</li>
 *   <li>{@code POST /api/rules} 定义/修改规则（JSON；Content-Type 含 yaml 时按 YAML 解析），落为新版本</li>
 *   <li>{@code DELETE /api/rules/{code}} 下线规则（历史任务与快照不受影响）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private static final Logger log = LoggerFactory.getLogger(RuleController.class);

    private final RuleCatalog catalog;
    private final ReconProperties properties;
    private final ApiSecurity security;

    public RuleController(RuleCatalog catalog, ReconProperties properties, ApiSecurity security) {
        this.catalog = catalog;
        this.properties = properties;
        this.security = security;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return catalog.allResolved().stream().map(RuleController::describe).toList();
    }

    @GetMapping("/{code}")
    public Map<String, Object> detail(@PathVariable String code) {
        if (!catalog.exists(code)) {
            throw new RuleNotFoundException(code);
        }
        RuleCatalog.Resolved resolved = catalog.resolve(code);
        Map<String, Object> view = new LinkedHashMap<>(describe(resolved));
        view.put("versions", catalog.versions(code).stream().map(RuleController::versionView).toList());
        return view;
    }

    /**
     * 定义或修改规则。规则体就是规则文件里的那份 YAML/JSON（见 docs/rule-dsl.md），
     * 修改 = 追加一个新版本，其它人正在跑的任务仍按各自创建时的版本执行。
     *
     * <p><b>这条通路直接落 ACTIVE，不过闸门 1/2/3</b>，所以默认关闭
     * （{@code recon.rules.legacy-define-enabled=false}），打开时也必须带
     * {@code X-Api-Key}（值取 {@code recon.review.api-key}）。它保留下来只是为了
     * 不打断既有 CI / 运维脚本，不是"规则写入的正常路径"。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> define(@RequestBody String body,
                                                      @RequestHeader(value = "Content-Type", required = false) String contentType,
                                                      @RequestHeader(value = "X-Operator", required = false) String operator,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        requireLegacyDefineAllowed(apiKey, operator);
        String who = operator == null || operator.isBlank() ? "api" : operator;
        RuleCatalog.Resolved resolved = contentType != null && contentType.toLowerCase().contains("yaml")
                ? catalog.defineYaml(body, "API 提交的 YAML 规则", who)
                : catalog.defineJson(body, "API 提交的 JSON 规则", who);
        log.warn("LEGACY_DEFINE 规则直写生效：{} v{}（操作人 {}）—— 该版本未经过草稿/试算/审批",
                resolved.spec().code(), resolved.version(), who);
        return ResponseEntity.status(HttpStatus.CREATED).body(describe(resolved));
    }

    /**
     * 三重判定，任何一关过不去都拒绝（不是"记个日志继续"）：
     * 开关打开、系统配了密钥、请求带的密钥与配置一致。
     *
     * <p>为什么"开关打开但没配密钥"也拒绝：那等于把 boolean 当成安全边界。
     * 开关的语义是"我知道这条通路有风险，仍然要开"，密钥的语义是"只有我知道怎么开"。
     */
    private void requireLegacyDefineAllowed(String apiKey, String operator) {
        if (!properties.getRules().isLegacyDefineEnabled()) {
            throw new LegacyDefineDisabledException("POST /api/rules 已停用（recon.rules.legacy-define-enabled=false）："
                    + "这条通路会绕过草稿 → 试算 → 审批直接生效。请改用草稿流程"
                    + "（Agent 起草或直接写 draft 版本后提交审核）；确需兼容旧脚本，"
                    + "打开该开关并配置 recon.review.api-key。");
        }
        String expected = properties.getReview().getApiKey();
        if (expected == null || expected.isBlank()) {
            throw new LegacyDefineDisabledException("recon.rules.legacy-define-enabled=true 但 "
                    + "recon.review.api-key 未配置：拒绝执行。开关本身不是鉴权，"
                    + "没配密钥的直写通路等于对所有人开放。");
        }
        if (apiKey == null || !java.security.MessageDigest.isEqual(
                apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new LegacyDefineDisabledException("缺少或错误的 X-Api-Key：规则直写同样需要鉴权"
                    + "（操作人 " + (operator == null ? "未提供" : operator) + "）");
        }
    }

    @DeleteMapping("/{code}")
    public Map<String, Object> disable(@PathVariable String code,
                                       @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        security.requireWrite(apiKey, "下线规则 " + code);
        int affected = catalog.disable(code);
        if (affected == 0) {
            throw new RuleNotFoundException(code);
        }
        return Map.of("code", code, "disabledVersions", affected);
    }

    private static Map<String, Object> describe(RuleCatalog.Resolved resolved) {
        RuleSpec rule = resolved.spec();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", rule.code());
        if (resolved.version() != null) {
            view.put("version", resolved.version());
        }
        view.put("name", rule.name());
        view.put("description", rule.description());
        view.put("left", rule.left().describe());
        view.put("right", rule.right().describe());
        view.put("keyField", rule.left().keyField());
        if (rule.left().derivedKey()) {
            view.put("leftDimension", rule.left().dimension());
            view.put("leftAggregated", rule.left().aggregated());
        }
        if (rule.right().derivedKey()) {
            view.put("rightDimension", rule.right().dimension());
            view.put("rightAggregated", rule.right().aggregated());
        }
        view.put("shardCount", rule.shard().count());
        view.put("parallelism", rule.shard().effectiveParallelism());
        view.put("maxAttempts", rule.maxAttempts());
        view.put("compare", rule.compare().stream().map(RuleController::fieldView).toList());
        view.put("missing", Map.of(
                "leftMissingDiffType", rule.missing().leftMissingDiffType(),
                "rightMissingDiffType", rule.missing().rightMissingDiffType(),
                "severity", rule.missing().severity()));
        view.put("duplicate", Map.of(
                "diffType", rule.duplicate().diffType(),
                "severity", rule.duplicate().severity()));
        return view;
    }

    private static Map<String, Object> versionView(RuleRepository.RuleRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", row.version());
        view.put("enabled", row.enabled());
        view.put("createdBy", row.createdBy());
        view.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        return view;
    }

    private static Map<String, Object> fieldView(FieldRule field) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("field", field.field());
        view.put("type", field.type());
        view.put("tolerance", field.tolerance());
        view.put("tolerancePercent", field.tolerancePercent());
        view.put("ignoreCase", field.ignoreCase());
        view.put("diffType", field.diffType());
        view.put("severity", field.severity());
        return view;
    }

    /** 规则体不合法（和规则文件同一套校验）→ 400，提示里带"改哪里"。 */
    @ExceptionHandler(RuleException.class)
    public ResponseEntity<Map<String, Object>> onRuleError(RuleException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(RuleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 规则直写被拒 → 403（不是 400：请求本身没问题，是这条通路被关了）。 */
    @ExceptionHandler(LegacyDefineDisabledException.class)
    public ResponseEntity<Map<String, Object>> onLegacyDefineDisabled(LegacyDefineDisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", e.getMessage(), "remediation", "走草稿 → 试算 → 审批"));
    }

    static class RuleNotFoundException extends RuntimeException {
        RuleNotFoundException(String code) {
            super("规则不存在: " + code);
        }
    }

    static class LegacyDefineDisabledException extends RuntimeException {
        LegacyDefineDisabledException(String message) {
            super(message);
        }
    }
}
