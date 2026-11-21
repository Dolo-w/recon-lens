package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.pattern.DiffPattern;
import io.github.dolow.reconlens.explain.pattern.PatternLibrary;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 归因入口：单条解释 + 按任务批量补齐。
 *
 * <p><b>分层顺序固定为 知识库 → 模式库 → 大模型 → 内置模板</b>（§6 成本分层 + K2）：
 * <ul>
 *   <li>L1 知识库命中：零 LLM 调用，直接用人确认过/模型沉淀过的归因 ——
 *       有人给过答案的问题，优先用那个答案；</li>
 *   <li>L0 模式库联想：机械特征（delta 比率/方向/类型）命中经验模式 ——
 *       跨规则、跨客户的"账单常见病"，标注"机器联想"，<b>不写回知识库</b>
 *       （机器猜测以 0.5 缓存进去，会变成永远赶不走的中置信度噪声）；</li>
 *   <li>L2 大模型：前两层都没接住才调用，输出写回知识库（{@code source=LLM}）；</li>
 *   <li>L3 模板：兜底，保证调用方永远拿到一句人话。</li>
 * </ul>
 * <p>知识库排在模式库前面是 K2 实现时定下的（与规划稿相反）：
 * 模式假设是"这类形状的病通常是 X"，知识库命中是"这类形状的病在<b>这个客户</b>这里
 * 已经被财务确认过是 Y"——证据更强。模式库的价值在知识库 miss 时兜住跨规则疾病，
 * 顺序反过来会让机器联想抢在人工确认前面。
 *
 * <p><b>批量路径的关键不是"先查零成本层"，而是"命中的那些不进模型的那一批"</b>。
 * 如果只是查到了用缓存、查不到仍然把整批丢给模型，{@code llm_calls} 一点都不会少 ——
 * 看起来有了缓存，成本分文未降。所以这里先分流再拼 batch，
 * {@code ExplainServiceKnowledgeTest} 用"零成本层全部命中时模型调用次数为 0"钉住了它。
 */
@Component
public class ExplainService {

    private static final Logger log = LoggerFactory.getLogger(ExplainService.class);

    /** 归因来自哪一层。留痕用：同一批差异里"多少条是白捡的"要看得见。 */
    public enum Layer {
        KNOWLEDGE, PATTERN, LLM, TEMPLATE
    }

    public record Attribution(String text, Layer layer, String patternId) {
        public Attribution(String text, Layer layer) {
            this(text, layer, null);
        }
    }

    private final RuleBasedExplainer ruleBased;
    private final LlmDiffExplainer llm;
    private final DiffKnowledgeBase knowledge;
    private final PatternLibrary patterns;
    private final ReconEngine engine;
    private final ReconRepository repository;

    /** 分层命中计数（进程生命周期内累计，重启归零）：ROI 报表的原始数据。知识库命中计数在 {@link DiffKnowledgeBase}。 */
    private final AtomicLong patternHits = new AtomicLong();
    private final AtomicLong llmExplained = new AtomicLong();
    private final AtomicLong templateFallbacks = new AtomicLong();

    public ExplainService(RuleBasedExplainer ruleBased, LlmDiffExplainer llm, DiffKnowledgeBase knowledge,
                          PatternLibrary patterns, ReconEngine engine, ReconRepository repository) {
        this.ruleBased = ruleBased;
        this.llm = llm;
        this.knowledge = knowledge;
        this.patterns = patterns;
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

        // 第一步：过零成本层（L1 知识库 → L0 模式库）。命中的直接定稿，不参与后面的模型批处理。
        Map<Long, String> settled = new LinkedHashMap<>();
        List<DiffRecord> needLlm = new ArrayList<>();
        int fromPatterns = 0;
        for (DiffRecord diff : pending) {
            Optional<Entry> hit = knowledge.lookup(job.ruleCode(), diff);
            if (hit.isPresent()) {
                settled.put(diff.id(), hit.get().render());
                continue;
            }
            Optional<DiffPattern> pattern = matchPattern(diff);
            if (pattern.isPresent()) {
                settled.put(diff.id(), patternText(pattern.get()));
                fromPatterns++;
                patternHits.incrementAndGet();
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
                    llmExplained.incrementAndGet();
                    // 写回知识库：下一批同类差异就不用再问模型了（验收 A6）
                    knowledge.remember(job.ruleCode(), diff, generated);
                } else {
                    // 模板结论不写回知识库：它是免费的、随时能重算的。
                    // 一旦缓存进库，这个形状就永久停在模板水平 —— 模型再也不会被问到，
                    // 等于用"省一次不花钱的计算"换掉了"以后所有同类差异都只有模板质量"。
                    text = ruleBased.explain(rule, diff);
                    templateFallbacks.incrementAndGet();
                }
            }
            // 大模型没覆盖到的（没配 key、请求失败、漏答）统一用模板兜底，保证不留空
            repository.updateExplanation(diff.id(), text);
            explained++;
        }
        log.info("任务 #{} 补齐 {} 条差异解释：知识库 {} 条，模式库 {} 条，大模型 {} 条（{}），其余模板兜底；知识库累计 {}",
                jobId, explained, settled.size() - fromPatterns, fromPatterns, fromModel,
                llm.available() ? "已启用" : "未启用",
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

    /** 归因分层的四层命中计数（进程生命周期内累计，重启归零）：ROI 报表用。 */
    public record LayerCounters(long knowledgeHits, long patternHits, long llmExplained, long templateFallbacks) {
    }

    public LayerCounters layerCounters() {
        return new LayerCounters(knowledge.snapshot().hits(), patternHits.get(),
                llmExplained.get(), templateFallbacks.get());
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
     * 零成本归因：只查 L1 知识库 + L0 模式库，未命中用模板。<b>不调模型、不落库、不写回</b>。
     *
     * <p>给"任务诊断"这类场景用：诊断一次要看几条差异，如果每条都触发一次模型调用，
     * 诊断工具就会变成最贵的接口 —— 而它恰恰是运维随手点的那一个。
     */
    public String cheapExplain(RuleSpec rule, String ruleCode, DiffRecord diff) {
        return knowledge.lookup(ruleCode, diff)
                .map(Entry::render)
                .orElseGet(() -> matchPattern(diff)
                        .map(p -> {
                            patternHits.incrementAndGet();
                            return patternText(p);
                        })
                        .orElseGet(() -> {
                            templateFallbacks.incrementAndGet();
                            return ruleBased.explain(rule, diff);
                        }));
    }

    /** 分层归因，返回文本、来源层与模式 ID —— 调用方需要知道这次省没省下模型调用。 */
    Attribution attribute(RuleSpec rule, String ruleCode, DiffRecord diff) {
        Optional<Entry> hit = knowledge.lookup(ruleCode, diff);
        if (hit.isPresent()) {
            return new Attribution(hit.get().render(), Layer.KNOWLEDGE);
        }
        Optional<DiffPattern> pattern = matchPattern(diff);
        if (pattern.isPresent()) {
            patternHits.incrementAndGet();
            return new Attribution(patternText(pattern.get()), Layer.PATTERN, pattern.get().id());
        }
        if (llm.available()) {
            String text = llm.explain(rule, diff);
            if (text != null && !text.isBlank()) {
                llmExplained.incrementAndGet();
                knowledge.remember(ruleCode, diff, text);
                return new Attribution(text, Layer.LLM);
            }
        }
        templateFallbacks.incrementAndGet();
        return new Attribution(ruleBased.explain(rule, diff), Layer.TEMPLATE);
    }

    private Optional<DiffPattern> matchPattern(DiffRecord diff) {
        List<DiffPattern> hits = patterns.match(diff);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * 模式假设的呈现口径：假设 + "机器联想"标注 + 核对建议。
     * <b>"机器联想"四个字不能省</b> —— 它是给财务的信号：这是统计规律，
     * 不是这单业务的实证结论（实证结论来自知识库的人工确认）。
     */
    private String patternText(DiffPattern pattern) {
        StringBuilder sb = new StringBuilder(pattern.hypothesis());
        sb.append("（机器联想 [模式库:").append(pattern.id()).append(']');
        if (pattern.probeHint() != null && !pattern.probeHint().isBlank()) {
            sb.append("，建议核对：").append(pattern.probeHint());
        }
        sb.append('）');
        return sb.toString();
    }

    /** 模式库全量内容（MCP / REST 展示）。 */
    public List<DiffPattern> patterns() {
        return patterns.all();
    }
}
