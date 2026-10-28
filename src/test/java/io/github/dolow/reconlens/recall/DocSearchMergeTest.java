package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M12：融合检索的分数口径。
 *
 * <p>这一组用例盯的不是"哪条排第一"，而是<b>分数本身能不能表达高低</b>。
 * 修复前的实现把三层的原始分直接乘权重相加：向量是 0~100 的余弦、关键词是 1~20 的命中计数、
 * 全文是评分函数的结果，量纲根本不同；再乘以 0.7/0.3/0.5 之后又用 {@code (int) Math.round}
 * 收敛，于是"关键词命中 2 次"和"命中 3 次"都会落到同一个整数分上，排序退化成插入顺序。
 *
 * <p>修复后每层先 min-max 归一到 0~1，再乘权重（×1.0 / ×0.35 / ×0.5）并放大 100 倍，
 * 于是"层内差异"和"层间权重"都能体现在同一个整数上，且向量的满分（100）仍然压得住
 * 关键词 + 全文同时命中的 85 分。
 */
class DocSearchMergeTest {

    private static DocChunk chunk(long id) {
        return new DocChunk(id, "doc-" + id, "无标题", "manual", null, 1, "正文 " + id, "h" + id, null,
                Instant.now());
    }

    private static DocHit hit(long id, int score) {
        return new DocHit(chunk(id), score, List.of("查询"));
    }

    private static DegradingDocSearch chain(VectorDocSearch vector, DocChunkRepository repository) {
        FulltextDocSearch fulltext = mock(FulltextDocSearch.class);
        KeywordDocSearch keyword = mock(KeywordDocSearch.class);
        when(fulltext.search(any(), any(), anyInt())).thenReturn(List.of());
        when(keyword.search(any(), any(), anyInt())).thenReturn(List.of());
        return new DegradingDocSearch(vector, fulltext, keyword, repository);
    }

    @Test
    void 同一层里的原始计数差多少_分数就要差多少() {
        VectorDocSearch vector = mock(VectorDocSearch.class);
        when(vector.available()).thenReturn(false);
        when(vector.reason()).thenReturn("未配置向量模型（测试）");
        DocChunkRepository repository = mock(DocChunkRepository.class);
        DegradingDocSearch search = chain(vector, repository);
        // 关键词层内部：命中 3 次 / 2 次 / 1 次
        when(searchKeyword(search).search(any(), any(), anyInt()))
                .thenReturn(List.of(hit(1, 3), hit(2, 2), hit(3, 1)));

        DegradingDocSearch.Outcome outcome = search.searchWithModes("查询", null, 5);

        // 关键词层权重 0.35：3 次→35 分、2 次→17.5→18 分、1 次→0 分（旧实现把前两条压成同一个整数）
        assertThat(outcome.hits()).extracting(DocHit::score).containsExactly(35, 18, 0);
        assertThat(outcome.hits()).extracting(h -> h.chunk().id()).containsExactly(1L, 2L, 3L);
        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.KEYWORD);
        assertThat(outcome.sources()).containsExactly(DegradingDocSearch.Mode.KEYWORD);
    }

    @Test
    void 一次语义命中压得住关键词加全文同时命中() {
        VectorDocSearch vector = mock(VectorDocSearch.class);
        when(vector.available()).thenReturn(true);
        when(vector.search(any(), any(), anyInt())).thenReturn(List.of(hit(10, 100)));
        DocChunkRepository repository = mock(DocChunkRepository.class);
        DegradingDocSearch search = chain(vector, repository);
        when(searchKeyword(search).search(any(), any(), anyInt())).thenReturn(List.of(hit(11, 5)));
        when(searchFulltext(search).search(any(), any(), anyInt())).thenReturn(List.of(hit(11, 2)));

        DegradingDocSearch.Outcome outcome = search.searchWithModes("查询", null, 5);

        // 语义满分 100；另一条被关键词（35）+ 全文（50）同时命中，85 —— 仍然排在后面
        assertThat(outcome.hits()).extracting(h -> h.chunk().id()).containsExactly(10L, 11L);
        assertThat(outcome.hits()).extracting(DocHit::score).containsExactly(100, 85);
        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.VECTOR);
        assertThat(outcome.sources()).contains(DegradingDocSearch.Mode.VECTOR,
                DegradingDocSearch.Mode.KEYWORD, DegradingDocSearch.Mode.FULLTEXT);
        assertThat(outcome.degraded()).isFalse();
    }

    @Test
    void 来源集合取自最终入选的结果_而不是这次跑过哪几层() {
        VectorDocSearch vector = mock(VectorDocSearch.class);
        when(vector.available()).thenReturn(false);
        when(vector.reason()).thenReturn("未配置向量模型（测试）");
        DocChunkRepository repository = mock(DocChunkRepository.class);
        DegradingDocSearch search = chain(vector, repository);
        when(searchKeyword(search).search(any(), any(), anyInt())).thenReturn(List.of(hit(11, 5)));
        when(searchFulltext(search).search(any(), any(), anyInt())).thenReturn(List.of(hit(11, 2)));

        DegradingDocSearch.Outcome outcome = search.searchWithModes("查询", null, 5);

        // 全文贡献 50 > 关键词 35，所以 mode 是 FULLTEXT；但两者都进了结果，来源就得是两个
        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.FULLTEXT);
        assertThat(outcome.sources()).containsExactlyInAnyOrder(DegradingDocSearch.Mode.FULLTEXT,
                DegradingDocSearch.Mode.KEYWORD);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.reason()).contains("未配置向量模型");
    }

    /** 从装配好的检索链里掏出被 mock 的那两层：只为在一个测试里同时钉住"层内归一化"与"层间权重"。 */
    private static KeywordDocSearch searchKeyword(DegradingDocSearch search) {
        return (KeywordDocSearch) field(search, "keyword");
    }

    private static FulltextDocSearch searchFulltext(DegradingDocSearch search) {
        return (FulltextDocSearch) field(search, "fulltext");
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
