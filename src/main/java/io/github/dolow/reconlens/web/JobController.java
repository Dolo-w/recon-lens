package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.DatasetFingerprints;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 任务接口：发起对账、查进度、查分片、重跑失败分片。 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    /** 读数据快照用：只要 JSON 到 Map，不需要 Spring 的 ObjectMapper 配置。 */
    private static final ObjectReader SNAPSHOT_READER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .readerFor(new TypeReference<java.util.Map<String, Object>>() { });

    private final ReconEngine engine;
    private final ReconRepository repository;
    private final ExplainService explainService;
    private final RuleCatalog catalog;
    private final ApiSecurity security;
    private final DatasetFingerprints fingerprints;

    public JobController(ReconEngine engine, ReconRepository repository, ExplainService explainService,
                         RuleCatalog catalog, ApiSecurity security, DatasetFingerprints fingerprints) {
        this.engine = engine;
        this.repository = repository;
        this.explainService = explainService;
        this.catalog = catalog;
        this.security = security;
        this.fingerprints = fingerprints;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JobRequest request,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        security.requireWrite(apiKey, "发起对账任务");
        RuleCatalog.Resolved resolved = resolveRule(request);
        String batchId = normalizeBatchId(request.batchId());
        Map<String, Object> body = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(request.sync())) {
            ReconJob job = engine.run(resolved, batchId, request.remark());
            body.put("jobId", job.id());
            body.put("ruleCode", job.ruleCode());
            body.put("ruleVersion", resolved.version());
            body.put("batchId", job.batchId());
            body.put("status", job.status());
            body.put("diffRows", job.diffRows());
            // 失败任务也要把原因带回去：库里的 error_msg 是排障的唯一线索，藏起来等于让人猜。
            if (job.errorMsg() != null && !job.errorMsg().isBlank()) {
                body.put("errorMsg", job.errorMsg());
            }
            return ResponseEntity.ok(body);
        }
        long jobId = engine.runAsync(resolved, batchId, request.remark());
        body.put("jobId", jobId);
        body.put("ruleCode", resolved.spec().code());
        body.put("ruleVersion", resolved.version());
        body.put("batchId", batchId);
        body.put("status", JobStatus.PENDING);
        return ResponseEntity.accepted().body(body);
    }

    /**
     * 批次号是 recon_job 的非空列，缺了就落不了库。以前直接 500 且响应体不带原因，调用方只能去翻日志；
     * 现在缺省自动补一个可读的批次号，并在响应里回显实际用了什么。
     */
    private static String normalizeBatchId(String raw) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        return "api-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    }

    /**
     * 用哪条规则：请求里带了内联规则就用内联的（不落库），否则按 ruleCode 取规则库的最新版本。
     */
    private RuleCatalog.Resolved resolveRule(JobRequest request) {
        if (request.inline()) {
            return request.ruleYaml() != null && !request.ruleYaml().isBlank()
                    ? catalog.inlineYaml(request.ruleYaml(), "请求内联的 YAML 规则")
                    : catalog.inline(request.rule(), "请求内联的 JSON 规则");
        }
        if (request.ruleCode() == null || request.ruleCode().isBlank()) {
            throw new IllegalArgumentException("ruleCode 不能为空（也可以用 rule / ruleYaml 内联一份规则）");
        }
        return catalog.resolve(request.ruleCode());
    }

    @GetMapping
    public List<ReconJob> list(@RequestParam(defaultValue = "20") int limit) {
        return repository.listJobs(Math.min(Math.max(limit, 1), 200));
    }

    /** 任务详情：任务本身 + 每个分片的状态，重跑时能一眼看出哪片挂了。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        ReconJob job = repository.findJob(id)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + id));
        List<ReconShard> shards = repository.listShards(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job);
        body.put("ruleVersion", repository.ruleVersion(id));
        body.put("shards", shards);
        body.put("diffSummary", repository.summaryByType(id));
        body.put("explainer", explainService.llmName());
        // "这个任务读的是哪一份数据"：老任务没有快照，必须说"未记录"而不是留空（ADR-0002 N5）
        String snapshot = repository.dataSnapshot(id).orElse(null);
        body.put("dataSnapshot", snapshot == null ? Map.of("recorded", false) : readSnapshot(snapshot));
        if (snapshot != null) {
            List<String> drift = fingerprints.drift(snapshot);
            if (!drift.isEmpty()) {
                body.put("dataDrift", drift);
            }
        }
        return body;
    }

    /**
     * 库里存的是 JSON 字符串，返回给调用方必须是对象。
     *
     * <p>直接塞字符串的后果：调用方拿到的是 {@code "{\"left\":…}"} 这一坨，得自己再解一次 ——
     * 接口的意义就是把"库里怎么存的"和"外面怎么用"分开。
     */
    private Map<String, Object> readSnapshot(String raw) {
        try {
            return SNAPSHOT_READER.readValue(raw);
        } catch (Exception e) {
            return Map.of("recorded", true, "raw", raw);
        }
    }

    /**
     * 任务比较：只在同一规则、同一版本下才给"可比"结论（ADR-0002 D7）。
     *
     * <p>本阶段还没有 {@code recon_dataset}/{@code NEW_SCOPE}，所以"同规则同版本"是唯一条件；
     * 一旦接上数据集版本，这里要再加"同一数据集名 + 不是 NEW_SCOPE"——<b>宁可现在答"不可比"，
     * 也不要让两个口径不同的任务看起来能相减</b>。
     */
    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam long a, @RequestParam long b) {
        ReconRepository.JobFacts first = repository.jobFacts(a)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + a));
        ReconRepository.JobFacts second = repository.jobFacts(b)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + b));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("a", facts(first));
        body.put("b", facts(second));

        List<String> why = new ArrayList<>();
        if (!first.ruleCode().equals(second.ruleCode())) {
            why.add("规则不同：" + first.ruleCode() + " vs " + second.ruleCode());
        } else if (!Objects.equals(first.ruleVersion(), second.ruleVersion())) {
            why.add("规则版本不同：v" + first.ruleVersion() + " vs v" + second.ruleVersion()
                    + "（版本之间可能换了维度或容差，结果不可直接相减）");
        }
        boolean comparable = why.isEmpty();
        body.put("comparable", comparable);
        body.put("reason", comparable ? "同规则同版本" : String.join("；", why));
        if (comparable) {
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("leftRows", second.leftRows() - first.leftRows());
            delta.put("rightRows", second.rightRows() - first.rightRows());
            delta.put("differences", second.diffRows() - first.diffRows());
            body.put("delta", delta);
            List<String> notes = new ArrayList<>();
            if (first.dataSnapshot() == null || second.dataSnapshot() == null) {
                notes.add("有一次任务没记数据快照（加这列之前建的），两次数据是否同源无法确认");
            }
            if (!notes.isEmpty()) {
                body.put("notes", notes);
            }
        }
        return body;
    }

    private Map<String, Object> facts(ReconRepository.JobFacts f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", f.id());
        m.put("ruleCode", f.ruleCode());
        m.put("ruleVersion", f.ruleVersion());
        m.put("status", f.status());
        m.put("leftRows", f.leftRows());
        m.put("rightRows", f.rightRows());
        m.put("matchedRows", f.matchedRows());
        m.put("differences", f.diffRows());
        m.put("createdAt", f.createdAt());
        m.put("dataRecorded", f.dataSnapshot() != null);
        return m;
    }

    /** 断点续跑：默认只重跑失败分片，已成功的分片不会重复计算。 */
    @PostMapping("/{id}/retry")
    public ReconJob retry(@PathVariable long id,
                          @RequestParam(defaultValue = "true") boolean onlyFailed,
                          @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        security.requireWrite(apiKey, "重跑失败分片");
        if (repository.findJob(id).isEmpty()) {
            throw new NotFoundException("任务不存在: " + id);
        }
        return engine.retry(id, onlyFailed);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 闸门 1 拒绝回 422：语义是"改规则"，与下面"改请求"的 400 分开。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(io.github.dolow.reconlens.dsl.RuleGateRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleGateRejected(
            io.github.dolow.reconlens.dsl.RuleGateRejectedException e) {
        return GateRejectionHandler.response(e);
    }

    /** 规则体不合法（和规则文件同一套校验）→ 400，别让它变成 500。 */
    @org.springframework.web.bind.annotation.ExceptionHandler({RuleException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /** 引擎/存储层抛出来的运行期错误：状态码还是 500，但至少把原因告诉调用方（默认 500 只有 timestamp/status/path）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleEngineError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    /** 极简的 404 语义，避免为了一个状态码引入额外依赖。 */
    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }
}
