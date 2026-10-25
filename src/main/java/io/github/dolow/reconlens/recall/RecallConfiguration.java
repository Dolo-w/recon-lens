package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.CaseMemoryRepository;
import io.github.dolow.reconlens.store.DocChunkRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 长期记忆的装配。
 *
 * <p>刻意<b>不挂</b>{@code recon.agent.enabled}：案例与口径检索是"给人看"的能力，
 * 没有 Agent 也应该能用；挂上去会让"关了 agent 连口径都查不到"。
 */
@Configuration(proxyBeanMethods = false)
public class RecallConfiguration {

    @Bean
    public CaseMemoryRepository caseMemoryRepository(JdbcTemplate jdbc) {
        return new CaseMemoryRepository(jdbc);
    }

    @Bean
    public DocChunkRepository docChunkRepository(JdbcTemplate jdbc) {
        return new DocChunkRepository(jdbc);
    }

    /**
     * 检索装配：向量 → 全文 → 关键词（ADR-0001 N4）。
     *
     * <p>装配层负责把三层拼起来，业务代码只看到 {@link DocSearch} 一个接口——
     * 这样"换掉向量实现"或"再插一层"都只改这里。
     */
    @Bean
    public DocSearch docSearch(DocChunkRepository docChunkRepository, EmbeddingClient embeddingClient,
                              @Value("${recon.recall.embedding.model:bge-m3}") String embeddingModel) {
        VectorDocSearch vector = new VectorDocSearch(docChunkRepository, embeddingClient);
        KeywordDocSearch keyword = new KeywordDocSearch(docChunkRepository);
        FulltextDocSearch fulltext = new FulltextDocSearch(docChunkRepository);
        return new DegradingDocSearch(vector, fulltext, keyword, docChunkRepository);
    }

    @Bean
    public CaseMemoryService caseMemoryService(CaseMemoryRepository caseMemoryRepository) {
        return new CaseMemoryService(caseMemoryRepository);
    }

    @Bean
    public DocChunkService docChunkService(DocChunkRepository docChunkRepository, DocSearch docSearch,
                                           EmbeddingClient embeddingClient,
                                           @Value("${recon.recall.embedding.model:bge-m3}") String embeddingModel) {
        return new DocChunkService(docChunkRepository, docSearch, embeddingClient, embeddingModel);
    }

    @Bean
    public RecallAugmenter recallAugmenter(CaseMemoryService caseMemoryService, DocChunkService docChunkService) {
        return new RecallAugmenter(caseMemoryService, docChunkService);
    }
}
