package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全文层：<b>查询里的每个词都必须在同一段里出现</b>（严格 AND），不做打分。
 *
 * <p>它存在的意义是"精准但脆"：用户原话说的是文档里的话，严格 AND 一次就中，而且结果确定、
 * 好解释（"这段里有你问的每一个词"）。它夹在向量和关键词中间，是因为两头都是"不那么确定"的：
 * 向量不确定是因为语义近邻会把不相干的段落拉进来，关键词不确定是因为两个字的重合太容易撞。
 *
 * <p>它命中 0 条不代表没答案，只代表"没有一段同时提到这些词"——所以没命中时继续往下降级，
 * 而不是直接把空结果当答案。
 */
public class FulltextDocSearch {

    private static final int CANDIDATE_LIMIT = 200;

    private final DocChunkRepository repository;

    public FulltextDocSearch(DocChunkRepository repository) {
        this.repository = repository;
    }

    public List<DocHit> search(String query, String source, int limit) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<DocChunk> chunks = repository.searchAllTerms(terms, source, CANDIDATE_LIMIT);
        List<DocHit> hits = new ArrayList<>();
        for (DocChunk chunk : chunks) {
            hits.add(new DocHit(chunk, 100 - Math.min(chunk.ordinal(), 99), terms));
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }

    /** 用与关键词层同一套切词，避免"同一句话在两层里被切成不同的词"。 */
    static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        for (String term : KeywordDocSearch.terms(query)) {
            String lower = term.toLowerCase(Locale.ROOT).trim();
            if (!lower.isEmpty()) {
                out.add(lower);
            }
        }
        return out;
    }
}
