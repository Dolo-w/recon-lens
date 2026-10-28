package io.github.dolow.reconlens.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ApprovalConfirmationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计导出：把「谁、什么时候、以什么身份、让机器做了什么」导成能交给别人复核的表格。
 *
 * <p>为什么要有这个接口而不是让人直接查库：审计数据分散在三张表（运行、步骤、确认码），
 * 而"某次审批是谁签的"必须能和"那次运行做了什么"对上。这个接口把三条线按同一种口径摊平成行，
 * 让人<b>不装环境</b>就能复核。
 *
 * <p>三条约束：
 * <ul>
 *   <li><b>只读</b>：这里没有任何写接口，审计通道不该成为第二条修改路径；</li>
 *   <li><b>必须带 X-Operator</b>：导出本身也是一次行为，没有操作人的导出等于没有记录；</li>
 *   <li><b>CSV 防注入</b>：以 {@code = + - @} 开头的单元格前补一个单引号——导出会被 Excel 打开，
 *       而 Excel 会把它们当公式执行。审计报告不该成为攻击载荷。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/audit")
// 运行记录与确认码都属于 Agent 模式：开关关掉时这两张表根本没被创建，控制器也必须一起消失，
// 否则默认启动（recon.agent.enabled=false）会因为缺 bean 直接起不来
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class AuditController {

    private static final String CSV = "csv";

    private final AgentRunRepository runs;
    private final ApprovalConfirmationRepository approvals;
    private final ApiSecurity security;
    private final ObjectMapper json;

    public AuditController(AgentRunRepository runs, ApprovalConfirmationRepository approvals,
                           ApiSecurity security, ObjectMapper json) {
        this.runs = runs;
        this.approvals = approvals;
        this.security = security;
        this.json = json;
    }

    @GetMapping("/runs")
    public ResponseEntity<String> exportRuns(
            @RequestHeader("X-Operator") String operator,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "json") String format) {
        security.requireWrite(apiKey, "导出审计记录");
        List<AgentRunRepository.AgentRun> rows = runs.runs(status, parse(from), parse(to), limit);
        if (CSV.equalsIgnoreCase(format)) {
            List<String> head = List.of("id", "createdAt", "status", "triggerType", "ruleCode", "jobId",
                    "draftVersion", "llmCalls", "llmTokens", "createdBy", "goal", "errorMsg");
            List<List<String>> body = new ArrayList<>();
            for (AgentRunRepository.AgentRun r : rows) {
                body.add(List.of(
                        String.valueOf(r.id()), str(r.createdAt()), str(r.status()), str(r.triggerType()),
                        str(r.ruleCode()), str(r.jobId()), str(r.draftVersion()),
                        String.valueOf(r.llmCalls()), String.valueOf(r.llmTokens()),
                        str(r.createdBy()), str(r.goal()), str(r.errorMsg())));
            }
            return csv(head, body);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", operator);
        out.put("total", rows.size());
        out.put("items", rows.stream().map(this::runView).toList());
        return json(out);
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<String> exportRun(
            @PathVariable long id,
            @RequestHeader("X-Operator") String operator,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam(defaultValue = "json") String format) {
        security.requireWrite(apiKey, "导出审计记录");
        AgentRunRepository.AgentRun run = runs.findRun(id)
                .orElseThrow(() -> new IllegalArgumentException("没有这次运行：" + id));
        List<AgentRunRepository.AgentStep> steps = runs.listSteps(id);
        if (CSV.equalsIgnoreCase(format)) {
            List<String> head = List.of("seq", "phase", "action", "status", "costMs", "tokensEst", "truncated",
                    "digest", "input", "payload");
            List<List<String>> body = new ArrayList<>();
            for (AgentRunRepository.AgentStep s : steps) {
                body.add(List.of(String.valueOf(s.seq()), str(s.phase()), str(s.action()), str(s.status()),
                        str(s.costMs()), str(s.tokensEst()), String.valueOf(s.truncated()), str(s.digest()),
                        str(s.inputJson()), str(s.payload())));
            }
            return csv(head, body);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", operator);
        out.put("run", runView(run));
        out.put("steps", steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.seq());
            m.put("phase", s.phase());
            m.put("action", s.action());
            m.put("status", s.status());
            m.put("costMs", s.costMs());
            m.put("tokensEst", s.tokensEst());
            m.put("truncated", s.truncated());
            m.put("digest", s.digest());
            m.put("input", s.inputJson());
            m.put("payload", s.payload());
            m.put("createdAt", str(s.createdAt()));
            return m;
        }).toList());
        return json(out);
    }

    /**
     * 审批留痕：谁在什么时候领了确认码、有没有用掉、被谁用掉。
     *
     * <p>这条线是"人签过字"的唯一证据，所以它必须和运行记录一样可导出——否则审计只能核对机器做了什么，
     * 核对不了人签了什么。
     */
    @GetMapping("/approvals")
    public ResponseEntity<String> exportApprovals(
            @RequestHeader("X-Operator") String operator,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "json") String format) {
        security.requireWrite(apiKey, "导出审计记录");
        List<ApprovalConfirmationRepository.Confirmation> rows = approvals.list(ruleCode, limit);
        Instant now = Instant.now();
        if (CSV.equalsIgnoreCase(format)) {
            List<String> head = List.of("code", "ruleCode", "ruleVersion", "decision", "createdBy",
                    "createdAt", "expiresAt", "usedAt", "usedBy", "state");
            List<List<String>> body = new ArrayList<>();
            for (ApprovalConfirmationRepository.Confirmation c : rows) {
                body.add(List.of(str(c.code()), str(c.ruleCode()), String.valueOf(c.ruleVersion()),
                        str(c.decision()), str(c.createdBy()), str(c.createdAt()), str(c.expiresAt()),
                        str(c.usedAt()), str(c.usedBy()), state(c, now)));
            }
            return csv(head, body);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operator", operator);
        out.put("total", rows.size());
        out.put("items", rows.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("ruleCode", c.ruleCode());
            m.put("ruleVersion", c.ruleVersion());
            m.put("decision", c.decision());
            m.put("createdBy", c.createdBy());
            m.put("createdAt", str(c.createdAt()));
            m.put("expiresAt", str(c.expiresAt()));
            m.put("usedAt", str(c.usedAt()));
            m.put("usedBy", c.usedBy());
            m.put("state", state(c, now));
            return m;
        }).toList());
        return json(out);
    }

    private static String state(ApprovalConfirmationRepository.Confirmation c, Instant now) {
        if (c.used()) {
            return "USED";
        }
        return c.expired(now) ? "EXPIRED" : "UNUSED";
    }

    private Map<String, Object> runView(AgentRunRepository.AgentRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("goal", r.goal());
        m.put("triggerType", r.triggerType());
        m.put("status", r.status());
        m.put("ruleCode", r.ruleCode());
        m.put("jobId", r.jobId());
        m.put("draftVersion", r.draftVersion());
        m.put("llmCalls", r.llmCalls());
        m.put("llmTokens", r.llmTokens());
        m.put("errorMsg", r.errorMsg());
        m.put("createdBy", r.createdBy());
        m.put("createdAt", str(r.createdAt()));
        m.put("finishedAt", str(r.finishedAt()));
        return m;
    }

    private ResponseEntity<String> json(Object body) {
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计记录序列化失败：" + e.getOriginalMessage(), e);
        }
    }

    private static ResponseEntity<String> csv(List<String> head, List<List<String>> body) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", head.stream().map(AuditController::cell).toList())).append('\n');
        for (List<String> row : body) {
            sb.append(String.join(",", row.stream().map(AuditController::cell).toList())).append('\n');
        }
        // 带 BOM：Excel 打开中文列名不会乱码；同 content-type 用 text/csv 让浏览器直接下载
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body("\uFEFF" + sb);
    }

    /** CSV 单元格：先防公式注入，再按需要加引号。 */
    static String cell(String raw) {
        String v = raw == null ? "" : raw;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        v = v.replace("\r", " ").replace("\n", " ");
        if (v.contains(",") || v.contains("\"") || v.contains("'")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Instant parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("时间要写成 ISO-8601，比如 2026-10-26T00:00:00Z，收到：" + text);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiKeyForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ApiKeyForbiddenException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }
}
