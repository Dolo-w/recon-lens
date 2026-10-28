package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * 归一化后分数的放大倍数。
     *
     * <p>为什么还要放大：{@code DocHit.score} 是 {@code int}，归一化后的 0~1 四舍五入会全部变成
     * 0 或 1，排序直接塌掉。乘 100 让三层的贡献落在 0~70/0~30/0~50 这个整数区间里，
     * 既保住了可读性，也没丢精度。
     */
    private static final double SCORE_SCALE = 100.0;

    public enum Mode { VECTOR, FULLTEXT, KEYWORD }

    /**
     * 一次检索的结果与"它是怎么来的"。
     *
     * @param mode     最终入选结果里贡献最大的那一层（回显用，人看一眼就知道该不该信）
     * @param degraded 有没有降级（最终结果里没有向量的贡献就是降级）
     * @param reason   降级原因（人能看懂的一句话；向量参与且有效时为空）
     * @param sources  <b>最终入选的 hit 分别来自哪几层</b>——这是 M12 修的那个错：
     *                 原来只说"这次是 KEYWORD 模式"，可实际命中的既有全文也有关键词，
     *                 调用方无从判断一份结果到底靠的是语义还是字面
     */
    public record Outcome(List<DocHit> hits, Mode mode, boolean degraded, String reason, Set<Mode> sources) {
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
     * <p>权重是拍出来的，但方向有依据，而且<b>必须按"归一化后的相对上限"来理解</b>：
     * 向量 ×1.0（语义主导）、关键词 ×0.35（用户用词与文档用词一致）、全文 ×0.5（严格 AND，
     * 命中即强信号）。三者的和可以超过 1.0，所以一篇只被关键词和全文同时命中的文档
     * 最高能拿 85 分，仍低于一次满分语义命中的 100 分——这就是"向量为主"的量化含义：
     * 字面匹配再多，也压不过一次真正的语义命中（M12 的实测用例就是钉这条）。
     *
     * <p>标题<b>不进向量</b>（它会稀释正文，实测把 recall@1 从 80% 拉到 70%），
     * 但标题命中查询词是强信号，所以做加分成。
     */
    public Outcome searchWithModes(String query, String source, int limit) {
        List<String> reasons = new ArrayList<>();
        Map<Long, DocHit> kept = new LinkedHashMap<>();
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, Mode> bestLayer = new LinkedHashMap<>();
        Map<Long, Double> bestShare = new LinkedHashMap<>();
        // 每条结果被哪些通道"给出来过"（不是它最终靠哪层赢的）
        Map<Long, Set<Mode>> givenBy = new LinkedHashMap<>();

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
            merge(kept, scores, bestLayer, bestShare, givenBy, hits, Mode.VECTOR, 1.0);
        } else {
            reasons.add(vector.reason());
        }

        List<DocHit> keywordHits = keyword.search(query, source, limit * 2);
        merge(kept, scores, bestLayer, bestShare, givenBy, keywordHits, Mode.KEYWORD, 0.35);
        List<DocHit> fulltextHits = fulltext.search(query, source, limit * 2);
        merge(kept, scores, bestLayer, bestShare, givenBy, fulltextHits, Mode.FULLTEXT, 0.5);

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

        // 来源集合取自"最终入选的这几条被哪些通道给出来过"：
        // 跑过全文但一条都没进结果，就不该被标成"全文检索"；
        // 一条结果既被关键词也被全文给出过，两条通道都算数——排序只有一个赢家，
        // 但"它是怎么被找到的"不该被赢家覆盖掉。
        Set<Mode> sources = new LinkedHashSet<>();
        for (DocHit hit : fused) {
            sources.addAll(givenBy.getOrDefault(hit.chunk().id(), Set.of()));
        }
        boolean degraded = !sources.contains(Mode.VECTOR);
        if (degraded && usedVector) {
            reasons.add("向量给出的候选没有进入最终结果");
        }
        if (degraded && !usedVector && !fused.isEmpty()) {
            reasons.add("本次结果全部来自字面匹配（关键词/全文），没有语义参与");
        }
        Mode mode = fused.isEmpty() ? Mode.KEYWORD
                : bestLayer.getOrDefault(fused.get(0).chunk().id(), Mode.KEYWORD);
        return new Outcome(fused, mode, degraded, degraded ? String.join("；", reasons) : "", sources);
    }

    /**
     * 同一 slice 在多层的分数相加；<b>每层先归一到 0~1，再乘权重</b>。
     *
     * <p>为什么必须归一化：三层的原始分数量纲根本不同——向量是余弦相似度（0~1），
     * 关键词是命中二元组的<b>计数</b>（中文字符串能到十几），全文是评分函数的相加结果。
     * 直接相加等于让"计数天然更大的一层"单边决定排序：一篇手册只要多命中两个词，
     * 就能压过语义上明显更近的另一篇。这正是 M12 修的那个缺陷——分数看起来有排序，
     * 实际上排的是"哪一层一直在给大数"。
     *
     * <p>两个边界显式处理：一层里所有 hit 同分时没有"相对高低"，给正分的都按满分算
     * （这一层确实认为它们相关）；全 0 分保持 0。
     */
    private static void merge(Map<Long, DocHit> kept, Map<Long, Double> scores,
                              Map<Long, Mode> bestLayer, Map<Long, Double> bestShare,
                              Map<Long, Set<Mode>> givenBy,
                              List<DocHit> hits, Mode layer, double weight) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        double max = hits.stream().mapToDouble(DocHit::score).max().orElse(0);
        double min = hits.stream().mapToDouble(DocHit::score).min().orElse(0);
        for (DocHit hit : hits) {
            double share = max > min ? (hit.score() - min) / (max - min) : (max > 0 ? 1.0 : 0.0);
            double contribution = share * weight * SCORE_SCALE;
            long id = hit.chunk().id();
            givenBy.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(layer);
            scores.merge(id, contribution, Double::sum);
            if (contribution >= bestShare.getOrDefault(id, -1.0)) {
                bestShare.put(id, contribution);
                bestLayer.put(id, layer);
            }
            DocHit previous = kept.get(id);
            if (previous == null || hit.matched().size() > previous.matched().size()) {
                kept.put(id, hit);
            }
        }
    }

    /**
     * 标题命中加分：查询里的中文二元组有多少出现在标题里，每个 +10，封顶 30。
     *
     * <p>为什么按二元组：中文没有分词器，而业务词多是两字词（汇率、容差、分片）。
     * 标题短，命中一个词通常就意味着"说的就是这篇"。
     *
     * <p>幅度必须与归一化后的权重同量纲（三层合计最高 150）：标题只是一个 tie-break，
     * 不能压过语义命中——否则"标题党"文档会靠标题挤掉真正相关的那篇。
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
        return Math.min(hits * 10, 30);
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
                    !vector.available(), vector.available() ? "" : vector.reason(), Set.of(Mode.VECTOR));
            case FULLTEXT -> new Outcome(fulltext.search(query, source, limit), Mode.FULLTEXT, false, "",
                    Set.of(Mode.FULLTEXT));
            case KEYWORD -> new Outcome(keyword.search(query, source, limit), Mode.KEYWORD, false, "",
                    Set.of(Mode.KEYWORD));
        };
    }

    @Override
    public String describe() {
        return vector.available()
                ? "融合检索（向量 ×1.0 + 关键词 ×0.35 + 全文锚点 ×0.5，各层先归一到 0~1，标题加分；向量不可用时自动降级）"
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
