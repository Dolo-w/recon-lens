package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索降级链的行为测试。
 *
 * <p>这一组用例要守住的是 ADR-0001 N4 的那句"降级链永远有下一层"，以及
 * "降级了要能在返回值里看出来"——因为同一个问题，有关键词层和只有向量层，
 * 答案的可信度不一样，人得知道这次是怎么查的。
 */
class DocSearchChainTest {

    private static JdbcTemplate jdbc;
    private static DocChunkRepository repository;

    @BeforeAll
    static void setUpSchema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:doc_chain;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        repository = new DocChunkRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_doc_chunk");
    }

    /** 可控的假向量：按文本查表，查不到给一个"和谁都不太像"的向量。 */
    private static final class FakeEmbedding implements EmbeddingClient {
        private final Map<String, float[]> table;
        private final boolean configured;

        FakeEmbedding(Map<String, float[]> table, boolean configured) {
            this.table = table;
            this.configured = configured;
        }

        @Override
        public Optional<float[]> embed(String text) {
            float[] v = table.get(text);
            return Optional.ofNullable(v);
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public int dim() {
            return 3;
        }

        @Override
        public String describe() {
            return configured ? "假向量（3 维）" : "未配置向量模型（测试）";
        }
    }

    private static DegradingDocSearch chain(EmbeddingClient client, DocChunkRepository repo) {
        return new DegradingDocSearch(new VectorDocSearch(repo, client),
                new FulltextDocSearch(repo), new KeywordDocSearch(repo), repo);
    }

    private void seed() {
        repository.insertAll("doc-1", "结算口径手册", "manual", null,
                List.of("手续费按 0.6% 计入，汇率进位差额不超过 0.01 元。",
                        "单边缺失要区分我方漏推和对方晚到，先查推送日志。",
                        "重复入账按幂等键去重，同一业务键只保留一条。"),
                List.of("h1", "h2", "h3"));
    }

    @Test
    @DisplayName("没配模型时不去试向量_并且明确说出为什么降级")
    void 没配模型时降级并说明原因() {
        seed();
        DegradingDocSearch search = chain(new FakeEmbedding(Map.of(), false), repository);

        DegradingDocSearch.Outcome outcome = search.searchWithModes("单边缺失", null, 5);

        // 这一句的二元组全落在同一段里 → 全文层的严格 AND 就能命中，还没轮到关键词层
        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.FULLTEXT);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.reason()).contains("未配置向量模型");
        assertThat(outcome.hits()).isNotEmpty();
    }

    @Test
    @DisplayName("配了模型但库里还没有向量_也要降级而不是给空结果")
    void 配了模型但库里没向量时降级() {
        seed();
        DegradingDocSearch search = chain(new FakeEmbedding(Map.of("单边缺失", new float[]{1, 0, 0}), true),
                repository);

        DegradingDocSearch.Outcome outcome = search.searchWithModes("单边缺失", null, 5);

        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.FULLTEXT);
        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.reason()).contains("向量检索没命中");
        assertThat(outcome.hits()).isNotEmpty();
    }

    @Test
    @DisplayName("有向量时用向量层_按余弦排序且不标记降级")
    void 有向量时按余弦排序() {
        seed();
        // 不按 list() 的返回顺序取段：列表的排序口径是"最近的文档在前"，
        // 同一份文档内的段顺序随插入时间变。测试要钉的是"第 N 段"，就该按 ordinal 找。
        List<DocChunk> chunks = repository.list(null, 10);
        DocChunk first = chunks.stream().filter(c -> c.ordinal() == 1).findFirst().orElseThrow();
        DocChunk second = chunks.stream().filter(c -> c.ordinal() == 2).findFirst().orElseThrow();
        // 第 1 段故意给"和查询完全不相关"的向量，第 2 段给"最相关"，验证排序真的用了余弦
        repository.saveEmbedding(first.id(), new float[]{0.2f, 1, 0}, "fake");
        repository.saveEmbedding(second.id(), new float[]{1, 0, 0}, "fake");

        DegradingDocSearch search = chain(new FakeEmbedding(Map.of("手续", new float[]{1, 0, 0}), true),
                repository);
        DegradingDocSearch.Outcome outcome = search.searchWithModes("手续", null, 5);

        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.VECTOR);
        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.reason()).isEmpty();
        // 融合后同一段可能同时拿到向量分与关键词分，分数不再是裸余弦×100
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.hits().get(0).chunk().ordinal()).isEqualTo(2);
        assertThat(outcome.hits().get(0).score()).isGreaterThanOrEqualTo(70);
    }

    @Test
    @DisplayName("向量层不可用_但所有词都在同一段里时走全文层")
    void 所有词同段时走全文层() {
        seed();
        DegradingDocSearch search = chain(new FakeEmbedding(Map.of(), false), repository);

        DegradingDocSearch.Outcome outcome = search.searchWithModes("幂等 去重", null, 5);

        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.FULLTEXT);
        assertThat(outcome.degraded()).isTrue();
        // 融合后"全文命中"不再独占结果：关键词把相关段也带进来是预期行为，
        // 要钉的是"全文那一段排在第一位"（精确锚点加分）。
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.hits().get(0).chunk().ordinal()).isEqualTo(3);
    }

    @Test
    @DisplayName("全文层要求每个词都在同一段_分散在不同段时要退到关键词层")
    void 词分散在不同段时退到关键词层() {
        seed();
        DegradingDocSearch search = chain(new FakeEmbedding(Map.of(), false), repository);

        // "手续费" 只在第 1 段，"推送日志" 只在第 2 段 → 严格 AND 命中 0，关键词层能命中两段
        DegradingDocSearch.Outcome outcome = search.searchWithModes("手续费 推送日志", null, 5);

        assertThat(outcome.mode()).isEqualTo(DegradingDocSearch.Mode.KEYWORD);
        assertThat(outcome.hits()).isNotEmpty();
    }

    @Test
    @DisplayName("向量维度对不上时不参与排序_避免换模型后给出看似正常的错误排名")
    void 维度不一致时跳过该条() {
        seed();
        List<DocChunk> chunks = repository.list(null, 10);
        repository.saveEmbedding(chunks.get(0).id(), new float[]{1, 0}, "old-model");

        DegradingDocSearch search = chain(new FakeEmbedding(Map.of("手续费", new float[]{1, 0, 0}), true),
                repository);
        DegradingDocSearch.Outcome outcome = search.searchWithModes("手续费", null, 5);

        // 唯一一条向量维度是 2，与查询的 3 维不符 → 向量层算不出结果，降级
        assertThat(outcome.mode()).isNotEqualTo(DegradingDocSearch.Mode.VECTOR);
        assertThat(outcome.degraded()).isTrue();
    }


}
