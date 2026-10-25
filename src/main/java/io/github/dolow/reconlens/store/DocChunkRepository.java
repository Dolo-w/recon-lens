package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.recall.DocChunk;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 口径文档切片的读写。检索本身在 {@code recall/KeywordDocSearch}，这里只提供数据。 */
public class DocChunkRepository {

    private static final String COLUMNS = "id, doc_id, title, source, meta_json, ordinal, content, content_hash, embedding, created_at";

    private final JdbcTemplate jdbc;

    public DocChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(String docId, String title, String source, String metaJson,
                          List<String> chunks, List<String> hashes) {
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            batch.add(new Object[]{docId, title, source, metaJson, i + 1, chunks.get(i), hashes.get(i)});
        }
        jdbc.batchUpdate("INSERT INTO recon_doc_chunk (doc_id, title, source, meta_json, ordinal, content, content_hash)"
                + " VALUES (?,?,?,?,?,?,?)", batch);
    }

    public boolean hashExists(String contentHash) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_doc_chunk WHERE content_hash = ?",
                Integer.class, contentHash);
        return n != null && n > 0;
    }

    /** 元数据过滤 + 逐词 LIKE。返回的是候选行，相关度打分在检索实现里做。 */
    public List<DocChunk> searchCandidates(List<String> terms, String source, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM recon_doc_chunk WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            args.add(source);
        }
        sql.append(" AND (");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("LOWER(content) LIKE ?");
            args.add("%" + terms.get(i).toLowerCase() + "%");
        }
        sql.append(") ORDER BY ordinal ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    /**
     * 最近的文档分段。
     *
     * <p>排序必须<b>完全确定</b>：{@code created_at} 只到秒，同一批插入的几段往往同秒，
     * 只按它排等于让数据库决定顺序 —— 同一份数据两次查询给出不同的"第 1 段"，
     * 上层按位置取（测试、分页、快照）就会时对时错。所以补 {@code doc_id, ordinal} 做定序。
     */
    public List<DocChunk> list(String source, int limit) {
        if (source == null || source.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM recon_doc_chunk"
                            + " ORDER BY created_at DESC, doc_id ASC, ordinal ASC LIMIT ?",
                    this::map, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_doc_chunk WHERE source = ?"
                + " ORDER BY created_at DESC, doc_id ASC, ordinal ASC LIMIT ?", this::map, source, limit);
    }

    public int countByDoc(String docId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_doc_chunk WHERE doc_id = ?",
                Integer.class, docId);
        return n == null ? 0 : n;
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_doc_chunk", Integer.class);
        return n == null ? 0 : n;
    }

    public int deleteDoc(String docId) {
        return jdbc.update("DELETE FROM recon_doc_chunk WHERE doc_id = ?", docId);
    }

    /** 严格 AND：每个词都要在同一段里出现。命中 0 条是正常结果（见 FulltextDocSearch）。 */
    public List<DocChunk> searchAllTerms(List<String> terms, String source, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM recon_doc_chunk WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            args.add(source);
        }
        for (String term : terms) {
            sql.append(" AND LOWER(content) LIKE ?");
            args.add("%" + term.toLowerCase() + "%");
        }
        sql.append(" ORDER BY ordinal ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    /** 向量检索的候选：只读算过向量的行。没装模型时这个查询返回空，等于自动降级。 */
    public List<DocChunk> vectorCandidates(String source, int limit) {
        if (source == null || source.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM recon_doc_chunk WHERE embedding IS NOT NULL"
                    + " ORDER BY created_at DESC, doc_id ASC, ordinal ASC LIMIT ?", this::map, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM recon_doc_chunk WHERE embedding IS NOT NULL"
                + " AND source = ? ORDER BY created_at DESC, doc_id ASC, ordinal ASC LIMIT ?", this::map, source, limit);
    }

    public int countWithEmbedding() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_doc_chunk WHERE embedding IS NOT NULL",
                Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 把一行算好的向量写回去。
     *
     * <p>为什么按 id 单独更新而不是批量：算向量是要调外部模型的，一条失败不该连累整批；
     * 逐条写回，成功的就留下，失败的下次重建索引再算。
     */
    public void saveEmbedding(long id, float[] vector, String model) {
        byte[] blob = toBlob(vector);
        jdbc.update("UPDATE recon_doc_chunk SET embedding = ?, dim = ?, model = ? WHERE id = ?",
                blob, vector.length, model, id);
    }

    private static byte[] toBlob(float[] vector) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    static float[] fromBlob(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(blob)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[blob.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    private DocChunk map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp ts = rs.getTimestamp("created_at");
        Instant created = ts == null ? null : ts.toInstant();
        return new DocChunk(rs.getLong("id"), rs.getString("doc_id"), rs.getString("title"),
                rs.getString("source"), rs.getString("meta_json"), rs.getInt("ordinal"),
                rs.getString("content"), rs.getString("content_hash"),
                fromBlob(rs.getBytes("embedding")), created);
    }
}
