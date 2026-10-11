package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 归因入口：单条解释 + 按任务批量补齐。
 * <p>策略固定为「能用大模型就用，用完失败马上落回模板」，所以调用方永远拿到一句人话。
 */
@Component
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    private final RuleBasedExplainer ruleBased;
    private final LlmDiffExplainer llm;
    private final ReconEngine engine;
    private final ReconRepository repository;

    public ExplainService(RuleBasedExplainer ruleBased, LlmDiffExplainer llm,
                          ReconEngine engine, ReconRepository repository) {
        this.ruleBased = ruleBased;
        this.llm = llm;
        this.engine = engine;
        this.repository = repository;
    }

    /** 解释一条差异并落库，返回解释文本。 */
    public String explain(long diffId) {
        DiffRecord diff = repository.findDiff(diffId)
                .orElseThrow(() -> new IllegalArgumentException("差异不存在: " + diffId));
        ReconJob job = repository.findJob(diff.jobId())
                .orElseThrow(() -> new IllegalStateException("差异所属任务丢失: " + diff.jobId()));
        RuleSpec rule = engine.ruleForJob(job);
        String text = choose(rule, diff);
        repository.updateExplanation(diffId, text);
        return text;
    }

    /** 给某任务尚未解释的差异批量补解释，返回补齐条数。 */
    public int explainJob(long jobId, int limit) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        RuleSpec rule = engine.ruleForJob(job);
        List<DiffRecord> pending = repository.listUnexplainedDiffs(jobId, limit);
        if (pending.isEmpty()) {
            return 0;
        }

        Map<Long, String> batch = llm.available() ? llm.explainBatch(rule, pending) : Map.of();
        int explained = 0;
        for (DiffRecord diff : pending) {
            String text = batch.get(diff.id());
            // 大模型没覆盖到的（没配 key、请求失败、漏答）统一用模板兜底，保证不留空
            repository.updateExplanation(diff.id(),
                    text == null || text.isBlank() ? ruleBased.explain(rule, diff) : text);
            explained++;
        }
        log.info("任务 #{} 补齐 {} 条差异解释（大模型{}）", jobId, explained, llm.available() ? "已启用" : "未启用");
        return explained;
    }

    public boolean llmAvailable() {
        return llm.available();
    }

    public String llmName() {
        return llm.available() ? llm.name() : ruleBased.name();
    }

    private String choose(RuleSpec rule, DiffRecord diff) {
        if (llm.available()) {
            String text = llm.explain(rule, diff);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return ruleBased.explain(rule, diff);
    }
}
