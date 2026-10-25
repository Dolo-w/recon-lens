package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 余弦相似度检索：<b>查的向量与库里的向量都从 BLOB 读出来，比较在内存里做</b>。
 *
 * <p>为什么不做成向量数据库：ADR-0001 D6 定了不引新中间件，口径文档的量级（几百到几千段）
 * 一次全读出来算余弦是毫秒级；等真的到十万段再谈 ANN，那时换实现也不影响接口。
 *
 * <p>为什么"有一条算不出来就整批放弃"：批量算到一半失败会给出一个偏的排名，
 * 而偏的排名看起来和正常排名一样，没人会发现。宁可返回空让上层降级。
 */
public class VectorDocSearch {

    private static final int MAX_CANDIDATES = 2000;

    private final DocChunkRepository repository;
    private final EmbeddingClient client;

    public VectorDocSearch(DocChunkRepository repository, EmbeddingClient client) {
        this.repository = repository;
        this.client = client;
    }

    /** 这一层现在能不能用。不可用时 {@link #reason()} 说明为什么。 */
    public boolean available() {
        return client.configured();
    }

    public String reason() {
        if (!client.configured()) {
            return client.describe();
        }
        return "";
    }

    public List<DocHit> search(String query, String source, int limit) {
        Optional<float[]> queryVector = client.embed(query);
        if (queryVector.isEmpty()) {
            return List.of();
        }
        List<DocChunk> candidates = repository.vectorCandidates(source, MAX_CANDIDATES);
        if (candidates.isEmpty()) {
            return List.of();
        }
        float[] q = queryVector.get();
        List<DocHit> hits = new ArrayList<>();
        for (DocChunk chunk : candidates) {
            float[] v = chunk.embedding();
            if (v == null || v.length != q.length) {
                continue;
            }
            double cos = cosine(q, v);
            if (cos <= 0.0) {
                continue;
            }
            hits.add(new DocHit(chunk, (int) Math.round(cos * 100), List.of()));
        }
        hits.sort(Comparator.comparingInt(DocHit::score).reversed());
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
