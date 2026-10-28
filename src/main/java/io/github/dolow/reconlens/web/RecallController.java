package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.recall.CaseMemoryService;
import io.github.dolow.reconlens.recall.CaseMemory;
import io.github.dolow.reconlens.recall.CaseHit;
import io.github.dolow.reconlens.recall.DocChunk;
import io.github.dolow.reconlens.recall.DocChunkService;
import io.github.dolow.reconlens.recall.DocHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆的对外接口：案例记忆写回/检索、口径文档入库/检索。
 *
 * <p>为什么不放在 {@code DiffController} 里：那是"某条差异的解释"，这里是"记忆本身"，
 * 两者的生命周期不同（差异会随任务删除，记忆应该活得更久）。
 */
@RestController
@RequestMapping("/api")
public class RecallController {

    private final CaseMemoryService cases;
    private final DocChunkService docs;

    /**
     * 是否允许用 {@code ?mode=} 强制检索层。
     *
     * <p>{@code mode} 是评测开关，不是产品功能：它能让调用方绕过生产的自动降级链，
     * 直接问"向量层现在返回什么"。默认关闭——一条能改行为的入参挂在公开读接口上，
     * 就不是"调试开关"，是"后门"：它既能被用来探测未启用的向量层，
     * 也能让同一次检索在两次调用里走不同的代码路径，事后谁对不上账。
     *
     * <p>要用就在评测 profile 里显式打开：{@code recon.recall.force-mode-enabled=true}。
     */
    private final boolean forceModeEnabled;

    public RecallController(CaseMemoryService cases, DocChunkService docs,
                            @Value("${recon.recall.force-mode-enabled:false}") boolean forceModeEnabled) {
        this.cases = cases;
        this.docs = docs;
        this.forceModeEnabled = forceModeEnabled;
    }

    public record CaseRequest(String ruleCode, String diffType, String causeKey, String resolution,
                              String operator, Long diffId, Long jobId, String source,
                              String datasetVersion) {
    }

    public record DocRequest(String title, String source, String meta, String text) {
    }

    /** 写回一条案例。人给的走 0.9；模型自述的必须显式 source=LLM，落到 0.5。 */
    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> rememberCase(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestBody CaseRequest request) {
        boolean byModel = "LLM".equalsIgnoreCase(request.source());
        if (!byModel && (operator == null || operator.isBlank())) {
            throw new IllegalArgumentException("人确认的案例必须带 X-Operator：没有操作人的记忆等于没有出处");
        }
        long id = byModel
                ? cases.rememberByModel(request.ruleCode(), request.diffType(), request.causeKey(),
                        request.resolution(), request.diffId(), request.jobId(), request.datasetVersion())
                : cases.rememberByHuman(request.ruleCode(), request.diffType(), request.causeKey(),
                        request.resolution(), operator, request.diffId(), request.jobId(),
                        request.datasetVersion());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("confidence", byModel ? CaseMemory.LLM_CONFIDENCE : CaseMemory.HUMAN_CONFIDENCE);
        body.put("source", byModel ? "LLM" : "HUMAN");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/cases")
    public Map<String, Object> listCases(@RequestParam String ruleCode,
                                         @RequestParam(defaultValue = "20") int limit) {
        List<CaseMemory> rows = cases.recent(ruleCode, Math.min(Math.max(limit, 1), 200));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CaseMemory m : rows) {
            items.add(view(m));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleCode", ruleCode);
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    /** 按"同规则同类型"检索，返回已经算过时间衰减的分数。 */
    @GetMapping("/cases/recall")
    public Map<String, Object> recallCases(@RequestParam String ruleCode,
                                           @RequestParam(required = false) String diffType,
                                           @RequestParam(required = false) String datasetVersion,
                                           @RequestParam(defaultValue = "3") int limit) {
        List<CaseHit> hits = cases.recall(ruleCode, diffType == null ? "UNKNOWN" : diffType,
                datasetVersion, Math.min(Math.max(limit, 1), 20));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CaseHit hit : hits) {
            Map<String, Object> item = view(hit.memory());
            item.put("score", Math.round(hit.score() * 1000) / 1000.0);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleCode", ruleCode);
        body.put("diffType", diffType);
        // 把"按哪一版筛的"回显出去：调用方看到 total 变小时要能分清是"没有案例"还是"筛掉了别的版本"
        body.put("datasetVersion", datasetVersion);
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    /** 口径文档入库：按句读切片，同一篇重复导入是幂等的。 */
    @PostMapping("/docs")
    public ResponseEntity<Map<String, Object>> indexDoc(@RequestBody DocRequest request) {
        DocChunkService.IndexResult result = docs.index(request.title(), request.source(), request.meta(), request.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", result.docId());
        body.put("chunks", result.chunks());
        body.put("alreadyIndexed", result.alreadyIndexed());
        return ResponseEntity.status(result.alreadyIndexed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping("/docs")
    public Map<String, Object> listDocs(@RequestParam(required = false) String source,
                                        @RequestParam(defaultValue = "20") int limit) {
        List<DocChunk> rows = docs.recent(source, Math.min(Math.max(limit, 1), 200));
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocChunk c : rows) {
            items.add(view(c));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    /**
     * 口径检索。
     *
     * <p>{@code mode} 是**评测用**开关：{@code vector|fulltext|keyword} 强制走某一层，
     * 不传就走生产的自动降级。没有它就没法回答"向量到底比全文好在哪"——
     * 自动降级下永远只能看到"最后命中哪层"，看不到"换个层会怎样"。
     *
     * <p>但它默认是关的（{@code recon.recall.force-mode-enabled=false}）：关着的时候传
     * {@code mode} 直接 400，而不是"悄悄忽略"——悄悄忽略会让评测脚本以为自己测的是向量层，
     * 实际测的是全文层，出来的对比数字全是错的。
     */
    @GetMapping("/docs/search")
    public Map<String, Object> searchDocs(@RequestParam String q,
                                          @RequestParam(required = false) String source,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(defaultValue = "5") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        io.github.dolow.reconlens.recall.DegradingDocSearch.Mode force = forceMode(mode);
        var outcome = force == null
                ? docs.searchWithModes(q, source, capped)
                : docs.searchForced(q, source, capped, force);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DocHit hit : outcome.hits()) {
            Map<String, Object> item = view(hit.chunk());
            item.put("score", hit.score());
            item.put("matched", hit.matched());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", q);
        body.put("retriever", docs.describeSearch());
        body.put("mode", outcome.mode().name());
        body.put("degraded", outcome.degraded());
        // 一条结果同时被关键词和全文给出过时，mode 只能报"排序赢家"；
        // sources 才是"这批结果是被哪些通道找到的"——降级判断要的是后者。
        body.put("sources", outcome.sources().stream().map(Enum::name).toList());
        body.put("reason", outcome.reason());
        body.put("forced", force != null);
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    @DeleteMapping("/docs/{docId}")
    public Map<String, Object> deleteDoc(@PathVariable String docId) {
        int removed = docs.deleteDoc(docId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docId", docId);
        body.put("removed", removed);
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }

    private static Map<String, Object> view(CaseMemory m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", m.id());
        item.put("ruleCode", m.ruleCode());
        item.put("diffType", m.diffType());
        item.put("causeKey", m.causeKey());
        item.put("resolution", m.resolution());
        item.put("source", m.source());
        item.put("confidence", m.confidence());
        item.put("operator", m.operator());
        item.put("diffId", m.diffId());
        item.put("jobId", m.jobId());
        item.put("datasetVersion", m.datasetVersion());
        item.put("hits", m.hits());
        item.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
        return item;
    }

    /**
     * 评测用的强制模式解析：空值当"不强制"；认不出的值报错；开关关着时任何合法 mode 都拒绝。
     *
     * <p>先认值、再看开关：参数拼错（mode=magic）和"想用评测开关"是两回事，
     * 混在一起会对着一个拼写错误说"生产环境请去掉该参数"。
     *
     * <p>拒绝而不是忽略：评测脚本传了 mode 说明它要测那一层，静默退回生产路径会让
     * "向量 vs 全文"的对比数字建立在错误的前提上——这比接口报错危险得多。
     */
    private io.github.dolow.reconlens.recall.DegradingDocSearch.Mode forceMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        io.github.dolow.reconlens.recall.DegradingDocSearch.Mode parsed = switch (mode.trim().toLowerCase()) {
            case "vector" -> io.github.dolow.reconlens.recall.DegradingDocSearch.Mode.VECTOR;
            case "fulltext" -> io.github.dolow.reconlens.recall.DegradingDocSearch.Mode.FULLTEXT;
            case "keyword" -> io.github.dolow.reconlens.recall.DegradingDocSearch.Mode.KEYWORD;
            default -> throw new IllegalArgumentException("未知检索层：" + mode + "（只认 vector|fulltext|keyword）");
        };
        if (!forceModeEnabled) {
            throw new IllegalArgumentException(
                    "检索层强制开关未启用（recon.recall.force-mode-enabled=false）：mode 只用于评测，"
                            + "生产环境请去掉该参数，走自动降级");
        }
        return parsed;
    }

    private static Map<String, Object> view(DocChunk c) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docId", c.docId());
        item.put("title", c.title());
        item.put("source", c.source());
        item.put("ordinal", c.ordinal());
        item.put("content", c.content());
        return item;
    }
}
