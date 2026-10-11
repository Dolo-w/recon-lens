package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务接口：发起对账、查进度、查分片、重跑失败分片。 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final ReconEngine engine;
    private final ReconRepository repository;
    private final ExplainService explainService;

    public JobController(ReconEngine engine, ReconRepository repository, ExplainService explainService) {
        this.engine = engine;
        this.repository = repository;
        this.explainService = explainService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JobRequest request) {
        if (request.ruleCode() == null || request.ruleCode().isBlank()) {
            throw new IllegalArgumentException("ruleCode 不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(request.sync())) {
            ReconJob job = engine.run(request.ruleCode(), request.batchId(), request.remark());
            body.put("jobId", job.id());
            body.put("status", job.status());
            body.put("diffRows", job.diffRows());
            return ResponseEntity.ok(body);
        }
        long jobId = engine.runAsync(request.ruleCode(), request.batchId(), request.remark());
        body.put("jobId", jobId);
        body.put("status", JobStatus.PENDING);
        return ResponseEntity.accepted().body(body);
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
        body.put("shards", shards);
        body.put("diffSummary", repository.summaryByType(id));
        body.put("explainer", explainService.llmName());
        return body;
    }

    /** 断点续跑：默认只重跑失败分片，已成功的分片不会重复计算。 */
    @PostMapping("/{id}/retry")
    public ReconJob retry(@PathVariable long id,
                          @RequestParam(defaultValue = "true") boolean onlyFailed) {
        if (repository.findJob(id).isEmpty()) {
            throw new NotFoundException("任务不存在: " + id);
        }
        return engine.retry(id, onlyFailed);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 极简的 404 语义，避免为了一个状态码引入额外依赖。 */
    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }
}
