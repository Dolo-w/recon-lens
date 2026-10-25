package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索降级链：<b>向量 → 全文 → 关键词</b>（ADR-0001 N4）。
 *
 * <p>为什么要有"链"而不是"选一个实现"：三种方式失效的原因完全不同——
 * 向量失效是"没装模型/模型挂了"，全文失效是"这段手册换了说法"，关键词失效是"用户用词和文档用词不一样"。
 * 前两种是环境问题，最后一种是数据问题；混在一起就只能给出一个"查不到"。
 *
 * <p>降级的两个触发点，语义不一样，所以分开写：
 * <ul>
 *   <li><b>不可用</b>（没配模型、库里没有向量）：直接跳过，不算失败；</li>
 *   <li><b>可用但没命中</b>：向量命中 0 条 → 继续往下试；全文（严格 AND）命中 0 条 → 继续往下试。
 *       严格匹配失败代表"这段手册没这么说"，而关键词（二元组 OR）本来就是为这种情形准备的。</li>
 * </ul>
 *
 * <p>为什么每次返回都带上 {@code mode}/{@code degraded}/{@code reason}：同一个问题，
 * 有模型和没模型给出的答案质量不一样。把"这次是怎么查的"一起返回，人才知道该不该信这个结果。
 */
public class DegradingDocSearch implements DocSearch {

    private static final Logger LOG = LoggerFactory.getLogger(DegradingDocSearch.class);

    public enum Mode { VECTOR, FULLTEXT, KEYWORD }

    /**
     * 一次检索的结果与"它是怎么来的"。
     *
     * @param degraded 有没有降级（不是 VECTOR 就是降级）
     * @param reason   降级原因（人能看懂的一句话；用上向量时为空）
     */
    public record Outcome(List<DocHit> hits, Mode mode, boolean degraded, String reason) {
    }

    private final VectorDocSearch vector;
    private final FulltextDocSearch fulltext;
    private final KeywordDocSearch keyword;
    private final DocChunkRepository repository;

    public DegradingDocSearch(VectorDocSearch vector, FulltextDocSearch fulltext,
                              KeywordDocSearch keyword, DocChunkRepository repository) {
        this.vector = vector;
        this.fulltext = fulltext;
        this.keyword = keyword;
        this.repository = repository;
    }

    @Override
    public List<DocHit> search(String query, String source, int limit) {
        return searchWithModes(query, source, limit).hits();
    }

    /**
     * 融合检索：<b>向量为主、关键词补漏、全文只做精确锚点加分</b>。
     *
     * <p><b>为什么从"降级链"改成"融合"</b>：降级是"前一层没命中才看下一层"，只要向量返回了哪怕一条，
     * 关键词就永远没机会——而实测里向量漏掉的那几条恰好被关键词捞回（20 条口径问法：
     * 向量 recall@3 = 75%，关键词 = 60%，并集 = 90%）。兜底和融合是两件事，前者保可用，后者提质量。
     *
     * <p>权重是拍出来的，但方向有依据：向量负责语义（0.7），关键词负责"用户用词和文档用词一致"（0.3），
     * 全文（严格 AND）只有在用户基本照抄原文时才命中，命中即强信号（0.5）。
     *
     * <p>标题<b>不进向量</b>（它会稀释正文，实测把 recall@1 从 80% 拉到 70%），
     * 但标题命中查询词是强信号，所以做加分成。
     */
    public Outcome searchWithModes(String query, String source, int limit) {
        List<String> reasons = new ArrayList<>();
        Map<Long, DocHit> kept = new LinkedHashMap<>();
        Map<Long, Double> scores = new LinkedHashMap<>();

        boolean usedVector = false;
        if (vector.available()) {
            List<DocHit> hits = vector.search(query, source, limit * 2);
            if (hits.isEmpty()) {
                reasons.add(vector.reason().isEmpty() ? "向量检索没命中" : vector.reason());
            } else {
                // 只有"向量真的给出了候选"才算用上向量：配了模型但库里没向量时，
                // 还是降级语义（degraded=true），否则调用方会以为这次是语义检索。
                usedVector = true;
            }
            merge(kept, scores, hits, 0.7);
        } else {
            reasons.add(vector.reason());
        }

        List<DocHit> keywordHits = keyword.search(query, source, limit * 2);
        merge(kept, scores, keywordHits, 0.3);
        List<DocHit> fulltextHits = fulltext.search(query, source, limit * 2);
        merge(kept, scores, fulltextHits, 0.5);

        List<DocHit> fused = new ArrayList<>();
        for (Map.Entry<Long, Double> entry : scores.entrySet()) {
            DocHit hit = kept.get(entry.getKey());
            double score = entry.getValue() + titleBonus(query, hit.chunk().title());
            fused.add(new DocHit(hit.chunk(), (int) Math.round(score), hit.matched()));
        }
        fused.sort((a, b) -> Integer.compare(b.score(), a.score()));
        if (fused.size() > limit) {
            fused = new ArrayList<>(fused.subList(0, limit));
        }
        if (fused.isEmpty()) {
            reasons.add("向量、关键词、全文都没命中");
        }

        Mode mode = usedVector ? Mode.VECTOR : (fulltextHits.isEmpty() ? Mode.KEYWORD : Mode.FULLTEXT);
        return new Outcome(fused, mode, !usedVector, usedVector ? "" : String.join("；", reasons));
    }

    /** 同一 slice 在多层的分数相加；保留 matched 最多的那条用于回显。 */
    private static void merge(Map<Long, DocHit> kept, Map<Long, Double> scores, List<DocHit> hits, double weight) {
        for (DocHit hit : hits) {
            long id = hit.chunk().id();
            scores.merge(id, hit.score() * weight, Double::sum);
            DocHit previous = kept.get(id);
            if (previous == null || hit.matched().size() > previous.matched().size()) {
                kept.put(id, hit);
            }
        }
    }

    /**
     * 标题命中加分：查询里的中文二元组有多少出现在标题里，每个 +5，封顶 20。
     *
     * <p>为什么按二元组：中文没有分词器，而业务词多是两字词（汇率、容差、分片）。
     * 标题短，命中一个词通常就意味着"说的就是这篇"。
     */
    private static double titleBonus(String query, String title) {
        if (query == null || title == null || title.isBlank()) {
            return 0;
        }
        String q = query.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        String t = title.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9]", "");
        int hits = 0;
        for (int i = 0; i + 2 <= q.length(); i++) {
            if (t.contains(q.substring(i, i + 2))) {
                hits++;
            }
        }
        return Math.min(hits * 5, 20);
    }

    /**
     * 评测用：强制走某一层，<b>不走自动降级</b>。
     *
     * <p>为什么需要它：自动降级是生产行为（N4 不变量），但"向量到底比全文好多少"必须
     * 在同一批问题上分别打通三层才比得出来。生产路径永远传 {@code force=null}，
     * 参数只出现在评测接口上——不能让它变成"绕过降级"的后门。
     */
    public Outcome searchForced(String query, String source, int limit, Mode force) {
        if (force == null) {
            return searchWithModes(query, source, limit);
        }
        return switch (force) {
            case VECTOR -> new Outcome(vector.search(query, source, limit), Mode.VECTOR,
                    !vector.available(), vector.available() ? "" : vector.reason());
            case FULLTEXT -> new Outcome(fulltext.search(query, source, limit), Mode.FULLTEXT, false, "");
            case KEYWORD -> new Outcome(keyword.search(query, source, limit), Mode.KEYWORD, false, "");
        };
    }

    @Override
    public String describe() {
        return vector.available()
                ? "融合检索（向量 0.7 + 关键词 0.3 + 全文锚点 0.5，标题加分；向量不可用时自动降级）"
                : "关键词 + 全文融合（未配置向量模型，自动降级）";
    }

    /** 建索引后要能算向量：谁负责这一环由上层决定，这里只暴露"能不能算"。 */
    public boolean canEmbed() {
        return vector.available();
    }

    public int countWithEmbedding() {
        return repository.countWithEmbedding();
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
