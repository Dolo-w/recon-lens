package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 差异接口：分页查询、分类汇总、归因。 */
@RestController
@RequestMapping("/api/diffs")
public class DiffController {

    private static final int MAX_LIMIT = 500;

    private final ReconRepository repository;
    private final ExplainService explainService;

    public DiffController(ReconRepository repository, ExplainService explainService) {
        this.repository = repository;
        this.explainService = explainService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long jobId,
                                    @RequestParam(required = false) String diffType,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<DiffRecord> items = repository.listDiffs(jobId, diffType, Math.max(offset, 0), safeLimit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", repository.countDiffs(jobId, diffType));
        body.put("offset", Math.max(offset, 0));
        body.put("limit", safeLimit);
        body.put("items", items);
        return body;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) Long jobId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("total", repository.countDiffs(jobId, null));
        body.put("byType", repository.summaryByType(jobId));
        body.put("bySeverity", repository.summaryBySeverity(jobId));
        body.put("explainer", explainService.llmName());
        return body;
    }

    @PostMapping("/{id}/explain")
    public Map<String, Object> explainOne(@PathVariable long id) {
        String text = explainService.explain(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("explanation", text);
        body.put("explainer", explainService.llmName());
        return body;
    }

    /** 批量补齐：没配大模型时走规则模板，一样不留空。 */
    @PostMapping("/explain")
    public Map<String, Object> explainJob(@RequestParam long jobId,
                                          @RequestParam(defaultValue = "200") int limit) {
        int explained = explainService.explainJob(jobId, Math.min(Math.max(limit, 1), MAX_LIMIT));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("explained", explained);
        body.put("explainer", explainService.llmName());
        return body;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
