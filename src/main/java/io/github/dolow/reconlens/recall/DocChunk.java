package io.github.dolow.reconlens.recall;

import java.time.Instant;

/** 口径文档的一个切片。切片的粒度是"一段话"，不是"一页"——检索命中后要能整段贴进回答。 */
public record DocChunk(
        long id,
        String docId,
        String title,
        String source,
        String metaJson,
        int ordinal,
        String content,
        String contentHash,
        float[] embedding,
        Instant createdAt) {

    /** 有没有算过向量。库里 embedding 为空就是没算过（没装模型时永远是 false）。 */
    public boolean embedded() {
        return embedding != null && embedding.length > 0;
    }
}
