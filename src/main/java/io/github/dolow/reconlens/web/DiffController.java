package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.recall.RecallAugmenter;
import io.github.dolow.reconlens.explain.DiffKnowledgeBase;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
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

/** 差异接口：分页查询、分类汇总、归因。 */
@RestController
@RequestMapping("/api/diffs")
public class DiffController {

    private static final int MAX_LIMIT = 500;

    private final ReconRepository repository;
    private final ExplainService explainService;
    private final RecallAugmenter recall;

    public DiffController(ReconRepository repository, ExplainService explainService, RecallAugmenter recall) {
        this.repository = repository;
        this.explainService = explainService;
        this.recall = recall;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long jobId,
                                    @RequestParam(required = false) String diffType,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int from = Math.max(offset, 0);
        List<DiffRecord> items = repository.listDiffs(jobId, diffType, from, safeLimit);
        long total = repository.countDiffs(jobId, diffType);
        boolean truncated = from + items.size() < total;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("offset", from);
        body.put("limit", safeLimit);
        // ADR-0001 N7：截断必须留翻页出口，而且要是**结构化字段**——
        // 只写在文案里（"共 137 条"）机器翻不动，宿主/前端还得去正则。
        body.put("truncated", truncated);
        body.put("nextOffset", truncated ? from + items.size() : null);
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
        // 归因之后补"同类案例 + 口径依据"；检索失败不影响归因本身
        String recallText = "";
        try {
            DiffRecord diff = repository.findDiff(id).orElse(null);
            if (diff != null) {
                String ruleCode = repository.findJob(diff.jobId()).map(j -> j.ruleCode()).orElse(null);
                // 带上这次任务的数据版本：口径变过的旧案例不该被当成同源经验（ADR-0002）
                String datasetVersion = repository.jobFacts(diff.jobId())
                        .map(ReconRepository.JobFacts::datasetVersion).orElse(null);
                recallText = recall.augment(ruleCode, diff.diffType(), diff.field(), diff.bizKey(),
                        datasetVersion);
            }
        } catch (RuntimeException ignored) {
            // 记忆只是附加信息，检索挂了也要把归因返回
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("explanation", text + recallText);
        body.put("explainer", explainService.llmName());
        body.put("recalled", !recallText.isEmpty());
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

    /**
     * 归因回流：人确认/修正一条差异的原因，写回知识库。
     *
     * <p>这是知识库置信度能升到 0.9 的唯一入口 —— 模型自己只能写 0.5。
     */
    @PostMapping("/{id}/attribution")
    public Map<String, Object> correctAttribution(@PathVariable long id,
                                                  @RequestBody AttributionRequest request) {
        boolean stored = explainService.correct(id, request.cause(), request.action(), request.operator());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("stored", stored);
        body.put("layer", "KNOWLEDGE");
        return body;
    }

    /** 知识库现状：进程内命中率 + 来源分布，用来判断成本是不是真的在降。 */
    @GetMapping("/knowledge")
    public Map<String, Object> knowledge(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        DiffKnowledgeBase.Snapshot snapshot = explainService.knowledgeSnapshot();
        body.put("lookups", snapshot.lookups());
        body.put("hits", snapshot.hits());
        body.put("misses", snapshot.misses());
        body.put("hitRate", snapshot.hitRate());
        body.put("summary", snapshot.describe());
        body.put("recent", explainService.recentKnowledge(Math.min(Math.max(limit, 1), MAX_LIMIT)));
        return body;
    }

    /** 人工修正归因的请求体。{@code operator} 必填 —— 不知道谁改的，就没法追溯。 */
    public record AttributionRequest(String cause, String action, String operator) {
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
