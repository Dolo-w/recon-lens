package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.explain.ExplainService.LayerCounters;
import io.github.dolow.reconlens.explain.DiffKnowledgeBase.Snapshot;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 知识层 ROI 报表（v4 验收口径第 1 条：「越用越准可量化」）。
 *
 * <p>命中率计数器一直在跑，但没有出口的指标等于不存在。这里把三层记账拼成一句话：
 * <b>这次运行期间，多少条差异的解释是白捡的（L1 知识库 + L0 模式库），多少条真的花了模型的钱（L2），
 * 多少条只能给模板（L3）</b>。{@code llmCallsSaved} 就是省钱本身：
 * 每一个 L1/L0 命中都对应一次没有发生的模型调用。
 *
 * <p><b>口径诚实</b>：计数是进程生命周期内的累计值，重启归零 —— 看趋势用，不是永久账本。
 * 报表里必须带这句话，否则"命中率 90%"会被读成"永久 90%"。
 */
@Service
public class KnowledgeRoiService {

    private final ExplainService explain;
    private final DiffKnowledgeBase knowledge;

    public KnowledgeRoiService(ExplainService explain, DiffKnowledgeBase knowledge) {
        this.explain = explain;
        this.knowledge = knowledge;
    }

    /** ROI 快照。{@code llmCallsSaved = knowledgeHits + patternHits}：每个零成本层命中 = 一次没发生的模型调用。 */
    public record Roi(boolean knowledgeEnabled, boolean llmEnabled,
                      long lookups, long knowledgeHits, double knowledgeHitRate,
                      long absent, long lowConfidence,
                      long patternHits, long llmExplained, long templateFallbacks,
                      long llmCallsSaved, Map<String, Long> kbBySource,
                      String note) {
    }

    public Roi roi() {
        Snapshot kb = knowledge.snapshot();
        LayerCounters layers = explain.layerCounters();
        long saved = kb.hits() + layers.patternHits();
        String note = "计数为进程启动以来的累计值，重启归零（看趋势，不是永久账本）；"
                + "llmCallsSaved = L1 命中 + L0 联想，即没有发生的模型调用次数";
        return new Roi(knowledge.enabled(), explain.llmAvailable(),
                kb.lookups(), kb.hits(), kb.hitRate(), kb.absent(), kb.lowConfidence(),
                layers.patternHits(), layers.llmExplained(), layers.templateFallbacks(),
                saved, knowledge.sourceCounts(), note);
    }
}
