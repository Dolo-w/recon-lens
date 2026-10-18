package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.DiffKnowledgeRepository.Entry;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 归因入口：单条解释 + 按任务批量补齐。
 *
 * <p><b>分层顺序固定为 知识库 → 大模型 → 内置模板</b>（§6 成本分层）：
 * <ul>
 *   <li>知识库命中：零 LLM 调用，直接用人确认过/模型沉淀过的归因；</li>
 *   <li>未命中：调大模型，成功则<b>写回知识库</b> —— 这是成本递减的唯一动力；</li>
 *   <li>都不行：落回模板，保证调用方永远拿到一句人话，而不是空字符串。</li>
 * </ul>
 *
 * <p><b>批量路径的关键不是"先查知识库"，而是"命中的那些不进模型的那一批"</b>。
 * 如果只是查到了用 KB、查不到仍然把整批丢给模型，{@code llm_calls} 一点都不会少 ——
 * 看起来有了知识库，成本分文未降。所以这里先分流再拼 batch，
 * {@code ExplainServiceKnowledgeTest} 用"KB 全部命中时模型调用次数为 0"钉住了它。
 */
@Component
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    /** 归因来自哪一层。留痕用：同一批差异里"多少条是白捡的"要看得见。 */
    public enum Layer {
        KNOWLEDGE, LLM, TEMPLATE
    }

    public record Attribution(String text, Layer layer) {
    }

    private final RuleBasedExplainer ruleBased;
    private final LlmDiffExplainer llm;
    private final DiffKnowledgeBase knowledge;
    private final ReconEngine engine;
    private final ReconRepository repository;

    public ExplainService(RuleBasedExplainer ruleBased, LlmDiffExplainer llm, DiffKnowledgeBase knowledge,
                          ReconEngine engine, ReconRepository repository) {
        this.ruleBased = ruleBased;
        this.llm = llm;
        this.knowledge = knowledge;
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
        Attribution attribution = attribute(rule, job.ruleCode(), diff);
        repository.updateExplanation(diffId, attribution.text());
        return attribution.text();
    }

    /** 给某任务尚未解释的差异批量补解释，返回补齐条数。 */
    public int explainJob(long jobId, int limit) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));
        List<DiffRecord> pending = repository.listUnexplainedDiffs(jobId, limit);
        if (pending.isEmpty()) {
            return 0;
        }
        RuleSpec rule = engine.ruleForJob(job);

        // 第一步：过知识库。命中的直接定稿，不参与后面的模型批处理。
        Map<Long, String> settled = new LinkedHashMap<>();
        List<DiffRecord> needLlm = new ArrayList<>();
        for (DiffRecord diff : pending) {
            Optional<Entry> hit = knowledge.lookup(job.ruleCode(), diff);
            if (hit.isPresent()) {
                settled.put(diff.id(), hit.get().render());
            } else {
                needLlm.add(diff);
            }
        }

        // 第二步：只把没命中的送进模型。全部命中时这一次调用根本不会发生。
        Map<Long, String> batch = llm.available() && !needLlm.isEmpty()
                ? llm.explainBatch(rule, needLlm)
                : Map.of();

        int explained = 0;
        int fromModel = 0;
        for (DiffRecord diff : pending) {
            String text = settled.get(diff.id());
            if (text == null) {
                String generated = batch.get(diff.id());
                if (generated != null && !generated.isBlank()) {
                    text = generated;
                    fromModel++;
                    // 写回知识库：下一批同类差异就不用再问模型了（验收 A6）
                    knowledge.remember(job.ruleCode(), diff, generated);
                } else {
                    // 模板结论不写回知识库：它是免费的、随时能重算的。
                    // 一旦缓存进库，这个形状就永久停在模板水平 —— 模型再也不会被问到，
                    // 等于用"省一次不花钱的计算"换掉了"以后所有同类差异都只有模板质量"。
                    text = ruleBased.explain(rule, diff);
                }
            }
            // 大模型没覆盖到的（没配 key、请求失败、漏答）统一用模板兜底，保证不留空
            repository.updateExplanation(diff.id(), text);
            explained++;
        }
        log.info("任务 #{} 补齐 {} 条差异解释：知识库 {} 条，大模型 {} 条（{}），其余模板兜底；知识库累计 {}",
                jobId, explained, settled.size(), fromModel, llm.available() ? "已启用" : "未启用",
                knowledge.snapshot().describe());
        return explained;
    }

    public boolean llmAvailable() {
        return llm.available();
    }

    public String llmName() {
        return llm.available() ? llm.name() : ruleBased.name();
    }

    /** 知识库当前状态，给指标/界面用。 */
    public DiffKnowledgeBase.Snapshot knowledgeSnapshot() {
        return knowledge.snapshot();
    }

    /** 知识库最近条目，给运营看"学到了什么"。 */
    public List<Entry> recentKnowledge(int limit) {
        return knowledge.recent(limit);
    }

    /**
     * 归因回流：运营确认或修正某条差异的归因，写回知识库（{@code source=HUMAN}, 置信度 0.9）。
     *
     * <p>这一步是整个成本分层里<b>唯一不可替代的人工动作</b>：
     * 模型能自己写 0.5 的条目，但只有人能把它升到 0.9。没有这一步，
     * 知识库里全是模型的自我复读，命中率再高也只是"把同一个猜测复述得更快"。
     */
    public boolean correct(long diffId, String cause, String action, String operator) {
        DiffRecord diff = repository.findDiff(diffId)
                .orElseThrow(() -> new IllegalArgumentException("差异不存在: " + diffId));
        ReconJob job = repository.findJob(diff.jobId())
                .orElseThrow(() -> new IllegalStateException("差异所属任务丢失: " + diff.jobId()));
        boolean taught = knowledge.teach(job.ruleCode(), diff, cause, action, operator);
        if (taught) {
            // 人改了归因，这条差异的说明也得跟着改 —— 否则界面上还是模型那句
            String text = action == null || action.isBlank() ? cause : cause + " 建议动作：" + action;
            repository.updateExplanation(diffId, text);
        }
        return taught;
    }

    /**
     * 零成本归因：只查知识库，未命中用模板。<b>不调模型、不落库、不写回</b>。
     *
     * <p>给"任务诊断"这类场景用：诊断一次要看几条差异，如果每条都触发一次模型调用，
     * 诊断工具就会变成最贵的接口 —— 而它恰恰是运维随手点的那一个。
     */
    public String cheapExplain(RuleSpec rule, String ruleCode, DiffRecord diff) {
        return knowledge.lookup(ruleCode, diff)
                .map(Entry::render)
                .orElseGet(() -> ruleBased.explain(rule, diff));
    }

    /** 分层归因，返回文本与来源层 —— 调用方需要知道这次省没省下模型调用。 */
    Attribution attribute(RuleSpec rule, String ruleCode, DiffRecord diff) {
        Optional<Entry> hit = knowledge.lookup(ruleCode, diff);
        if (hit.isPresent()) {
            return new Attribution(hit.get().render(), Layer.KNOWLEDGE);
        }
        if (llm.available()) {
            String text = llm.explain(rule, diff);
            if (text != null && !text.isBlank()) {
                knowledge.remember(ruleCode, diff, text);
                return new Attribution(text, Layer.LLM);
            }
        }
        return new Attribution(ruleBased.explain(rule, diff), Layer.TEMPLATE);
    }
}
