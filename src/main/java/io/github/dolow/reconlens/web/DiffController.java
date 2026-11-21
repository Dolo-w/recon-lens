package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.pricing.PriceImpactService;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.lifecycle.DiffLifecycleService;
import io.github.dolow.reconlens.recall.RecallAugmenter;
import io.github.dolow.reconlens.explain.DiffKnowledgeBase;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.DiffLifecycleRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
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
    private final DiffLifecycleService lifecycleService;
    private final DiffLifecycleRepository lifecycleRepository;
    private final PriceImpactService priceImpact;

    public DiffController(ReconRepository repository, ExplainService explainService, RecallAugmenter recall,
                          DiffLifecycleService lifecycleService, DiffLifecycleRepository lifecycleRepository,
                          PriceImpactService priceImpact) {
        this.repository = repository;
        this.explainService = explainService;
        this.recall = recall;
        this.lifecycleService = lifecycleService;
        this.priceImpact = priceImpact;
        this.lifecycleRepository = lifecycleRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long jobId,
                                    @RequestParam(required = false) String diffType,
                                    @RequestParam(defaultValue = "false") boolean withStatus,
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
        // withStatus=true 时逐条带生命周期状态（OPEN/CLAIMED/RESOLVED/EXEMPTED）——
        // 处置视图和对账视图用同一份列表，避免"全局口径"和"台账口径"对不上
        body.put("items", withStatus ? withStatus(items) : items);
        return body;
    }

    /** 差异 + 当前处置状态。状态键固定 lifecycleStatus，没有台账记录 = OPEN。 */
    private List<Map<String, Object>> withStatus(List<DiffRecord> items) {
        Map<Long, io.github.dolow.reconlens.domain.DiffLifecycle.Status> statuses =
                lifecycleRepository.currentStatuses(items.stream().map(DiffRecord::id).toList());
        List<Map<String, Object>> out = new java.util.ArrayList<>(items.size());
        for (DiffRecord d : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.id());
            row.put("jobId", d.jobId());
            row.put("shardId", d.shardId());
            row.put("diffType", d.diffType());
            row.put("severity", d.severity());
            row.put("bizKey", d.bizKey());
            row.put("field", d.field());
            row.put("leftValue", d.leftValue());
            row.put("rightValue", d.rightValue());
            row.put("deltaValue", d.deltaValue());
            row.put("deltaRatio", d.deltaRatio());
            row.put("explanation", d.explanation());
            row.put("lifecycleStatus", statuses.getOrDefault(d.id(),
                    io.github.dolow.reconlens.domain.DiffLifecycle.Status.OPEN).name());
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 差异生命周期（豁免台账）：claim / release / resolve / exempt / unexempt

    /**
     * 单条差异计价：差异 × 价格 = 金额影响。状态类/文本类差异没有金额语义，直接 400。
     */
    @PostMapping("/{id}/price-impact")
    public Map<String, Object> priceImpact(@PathVariable long id,
                                           @RequestBody PriceImpactRequest request) {
        DiffRecord diff = repository.findDiffById(id);
        if (diff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异不存在: id=" + id);
        }
        java.time.Instant at;
        try {
            at = request.at() == null || request.at().isBlank()
                    ? null : java.time.Instant.parse(request.at().trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("at 必须是 ISO-8601 UTC 时间（如 2026-11-15T00:00:00Z）");
        }
        PriceImpactService.PriceImpact impact = priceImpact.impactOf(
                diff, request.priceCode(), request.model(), request.tokenType(), at);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("diffId", impact.diffId());
        body.put("diffType", impact.diffType());
        body.put("bizKey", impact.bizKey());
        body.put("leftTokens", impact.leftTokens().toPlainString());
        body.put("rightTokens", impact.rightTokens().toPlainString());
        body.put("leftAmount", impact.leftAmount().toPlainString());
        body.put("rightAmount", impact.rightAmount().toPlainString());
        body.put("impact", impact.impact().toPlainString());
        body.put("impactNote", "impact = left(我方账单口径) - right(对方口径)；负数=少收，正数=多收");
        body.put("currency", impact.currency());
        body.put("unitPricePerMillion", impact.unitPricePerMillion());
        body.put("effectiveFrom", impact.effectiveFrom());
        body.put("effectiveTo", impact.effectiveTo());
        return body;
    }

    /** 纯参数载体：归因请求体（at 可空，缺省用任务完成时刻）。 */
    public record PriceImpactRequest(String priceCode, String model, String tokenType, String at) {
    }

    /** 执行一次处置动作。RESOLVE 与 EXEMPT 必须带 note（核销原因 / 豁免依据）。 */
    @PostMapping("/{id}/lifecycle")
    public Map<String, Object> act(@PathVariable long id, @RequestBody LifecycleRequest request) {
        io.github.dolow.reconlens.domain.DiffLifecycle.Action action;
        String raw = request.action() == null ? "" : request.action().trim().toUpperCase(java.util.Locale.ROOT);
        try {
            action = io.github.dolow.reconlens.domain.DiffLifecycle.Action.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知 action: " + request.action()
                    + "，可用: CLAIM / RELEASE / RESOLVE / EXEMPT / UNEXEMPT");
        }
        DiffActionSnapshot snapshot = DiffActionSnapshot.of(
                lifecycleService.apply(id, action, request.operator(), request.note(), request.reasonId()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("action", snapshot.action());
        body.put("status", snapshot.status());
        body.put("operator", snapshot.operator());
        body.put("note", snapshot.note());
        body.put("reasonId", snapshot.reasonId());
        body.put("actedAt", snapshot.actedAt());
        return body;
    }

    /** 单条差异的处置历史（append-only 台账，最新在前）。 */
    @GetMapping("/{id}/lifecycle")
    public Map<String, Object> history(@PathVariable long id) {
        List<io.github.dolow.reconlens.domain.DiffLifecycle.DiffAction> items = lifecycleService.history(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", items.isEmpty()
                ? io.github.dolow.reconlens.domain.DiffLifecycle.Status.OPEN.name()
                : items.get(0).status().name());
        body.put("history", items);
        return body;
    }

    /** 闭环率汇总：jobId 为空 = 全库且只算 PROD 任务（与 /summary 同口径）。 */
    @GetMapping("/lifecycle/summary")
    public Map<String, Object> lifecycleSummary(@RequestParam(required = false) Long jobId) {
        DiffLifecycleService.Snapshot s = lifecycleService.summary(jobId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("total", s.total());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        s.byStatus().forEach((k, v) -> byStatus.put(k.name(), v));
        body.put("byStatus", byStatus);
        // closureRate null = 没有差异；0/0 不冒充 0% 也不冒充 100%
        body.put("closureRate", s.closureRate());
        return body;
    }

    private record DiffActionSnapshot(io.github.dolow.reconlens.domain.DiffLifecycle.Action action,
                                      io.github.dolow.reconlens.domain.DiffLifecycle.Status status,
                                      String operator, String note, String reasonId,
                                      java.time.Instant actedAt) {

        static DiffActionSnapshot of(io.github.dolow.reconlens.domain.DiffLifecycle.DiffAction a) {
            return new DiffActionSnapshot(a.action(), a.status(), a.operator(), a.note(), a.reasonId(),
                    a.createdAt());
        }
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

    /** 经验模式库（K2 / L0 层）：机械特征 → 候选假设，跨规则生效，内容在 patterns/*.yml。 */
    @GetMapping("/patterns")
    public Map<String, Object> patterns() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<io.github.dolow.reconlens.explain.pattern.DiffPattern> items = explainService.patterns();
        body.put("total", items.size());
        body.put("items", items.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("family", p.family());
            row.put("name", p.name());
            row.put("hypothesis", p.hypothesis());
            row.put("probeHint", p.probeHint());
            return row;
        }).toList());
        return body;
    }

    /**
     * 单条差异详情：差异本体 + 两侧原始行凭证。凭证是差异落库时刻的整行快照，
     * 源表后来变了也不影响复盘口径；该行没有凭证（如试算旧数据）时为空列表。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        DiffRecord diff = repository.findDiffById(id);
        if (diff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异不存在: id=" + id);
        }
        List<DiffEvidence> evidence = repository.listEvidence(diff.jobId(), diff.shardId(), diff.bizKey());
        Map<String, Object> left = new LinkedHashMap<>();
        Map<String, Object> right = new LinkedHashMap<>();
        for (DiffEvidence e : evidence) {
            Map<String, Object> target = DiffEvidence.LEFT.equals(e.side()) ? left : right;
            target.put("source", e.source());
            target.put("rowJson", e.rowJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", diff.id());
        body.put("jobId", diff.jobId());
        body.put("shardId", diff.shardId());
        body.put("diffType", diff.diffType());
        body.put("severity", diff.severity());
        body.put("bizKey", diff.bizKey());
        body.put("field", diff.field());
        body.put("leftValue", diff.leftValue());
        body.put("rightValue", diff.rightValue());
        body.put("deltaValue", diff.deltaValue());
        body.put("deltaRatio", diff.deltaRatio());
        body.put("explanation", diff.explanation());
        body.put("evidence", Map.of("left", left, "right", right));
        return body;
    }

    /** 人工修正归因的请求体。{@code operator} 必填 —— 不知道谁改的，就没法追溯。 */
    public record AttributionRequest(String cause, String action, String operator) {
    }

    /** 差异处置请求体。{@code operator} 必填；RESOLVE / EXEMPT 时 {@code note} 必填；
     *  {@code reasonId} 可选（定性结论分类 ID，K2 模式库分类法，K1 阶段允许空）。 */
    public record LifecycleRequest(String action, String operator, String note, String reasonId) {
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
