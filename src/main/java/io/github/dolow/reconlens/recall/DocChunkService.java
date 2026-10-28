package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 口径文档的入库与检索。
 *
 * <p>切片规则刻意简单：按句读（。！？；换行）累积，超过 {@link #CHUNK_CHARS} 就断。
 * 不用固定窗口滑动，因为"一段话被切成两半"比"某段长一点"更难读；
 * 对账口径是条文式的，一句一句读得懂才检索得动。
 *
 * <p>幂等：整篇内容算一次 sha256 作为 doc 指纹，同一篇重复导入直接返回既有 docId，
 * 不产生第二份切片——否则同一份手册导两次，检索结果就会被同一段话刷屏。
 */
public class DocChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocChunkService.class);
    /** 切片上限：一段话超过这么长就断，避免一条切片 4000 字。 */
    static final int CHUNK_CHARS = 400;
    /** 合并下限：条文（每条几十字）要能在句读处断开，否则整篇手册会被并成一坨。 */
    static final int MIN_CHUNK_CHARS = 120;

    private final DocChunkRepository repository;
    private final DocSearch search;

    private final EmbeddingClient embedding;
    private final String embeddingModel;

    public DocChunkService(DocChunkRepository repository, DocSearch search) {
        this(repository, search, null, "");
    }

    public DocChunkService(DocChunkRepository repository, DocSearch search,
                           EmbeddingClient embedding, String embeddingModel) {
        this.repository = repository;
        this.search = search;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel;
    }

    public record IndexResult(String docId, int chunks, boolean alreadyIndexed) {
    }

    public IndexResult index(String title, String source, String metaJson, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文档正文不能为空");
        }
        String docId = shortHash(text);
        int existing = repository.countByDoc(docId);
        if (existing > 0) {
            // 同一篇重复导入：幂等返回，不产生第二份切片
            return new IndexResult(docId, existing, true);
        }
        List<String> chunks = split(text);
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            hashes.add(docId + ":" + (i + 1));
        }
        repository.insertAll(docId, title == null ? "未命名" : title, source == null ? "manual" : source,
                metaJson, chunks, hashes);
        int embedded = embedDocument(docId);
        log.info("doc 入库 docId={} chunks={} source={} 已算向量={}", docId, chunks.size(), source, embedded);
        return new IndexResult(docId, chunks.size(), false);
    }

    public List<DocHit> search(String query, String source, int limit) {
        return search.search(query, source, limit);
    }

    /**
     * 检索 + "这次是怎么查的"。
     *
     * <p>降级链是 {@link DegradingDocSearch}，这里只负责把 OutCome 透出去；
     * 如果装配的不是降级链（单测里可以塞裸 KeywordDocSearch），就报 KEYWORD + degraded=true。
     */
    public DegradingDocSearch.Outcome searchWithModes(String query, String source, int limit) {
        if (search instanceof DegradingDocSearch chain) {
            return chain.searchWithModes(query, source, limit);
        }
        return new DegradingDocSearch.Outcome(search.search(query, source, limit),
                DegradingDocSearch.Mode.KEYWORD, true, "装配的是单层检索（没有降级链）",
                java.util.Set.of(DegradingDocSearch.Mode.KEYWORD));
    }

    /** 评测用：强制某一层。生产调用不要用（会把 N4 的降级链绕开）。 */
    public DegradingDocSearch.Outcome searchForced(String query, String source, int limit,
                                                   DegradingDocSearch.Mode force) {
        if (search instanceof DegradingDocSearch chain) {
            return chain.searchForced(query, source, limit, force);
        }
        return new DegradingDocSearch.Outcome(search.search(query, source, limit),
                DegradingDocSearch.Mode.KEYWORD, true, "装配的是单层检索（没有降级链）",
                java.util.Set.of(DegradingDocSearch.Mode.KEYWORD));
    }

    /** 没配模型时返回 0，一行都不查。 */
    public int embedDocument(String docId) {
        if (embedding == null || !embedding.configured()) {
            return 0;
        }
        int done = 0;
        for (DocChunk chunk : repository.list(null, Integer.MAX_VALUE)) {
            if (!docId.equals(chunk.docId()) || chunk.embedded()) {
                continue;
            }
            // 只嵌正文：标题会稀释正文语义（实测 recall@1 从 80% 掉到 70%），
            // 标题的价值改由 DegradingDocSearch 的 titleBonus 体现。
            var vector = embedding.embed(chunk.content());
            if (vector.isEmpty()) {
                // 一条失败就停：继续算只会得到"一半有向量"的索引，比没有更难看
                log.warn("向量化中断 docId={} ordinal={}，已算 {} 条", docId, chunk.ordinal(), done);
                break;
            }
            repository.saveEmbedding(chunk.id(), vector.get(), embeddingModel.isBlank() ? "unknown" : embeddingModel);
            done++;
        }
        return done;
    }

    public String describeSearch() {
        return search.describe();
    }

    public List<DocChunk> recent(String source, int limit) {
        return repository.list(source, limit);
    }

    public int deleteDoc(String docId) {
        return repository.deleteDoc(docId);
    }

    public int count() {
        return repository.count();
    }

    /** 按句读累积切段；空行也当作断点（手册里空行就是换小节）。 */
    static List<String> split(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            current.append(c);
            boolean boundary = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n';
            if (boundary && current.length() >= MIN_CHUNK_CHARS) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            } else if (boundary && current.length() >= CHUNK_CHARS / 2 && i + 1 < normalized.length()
                    && normalized.charAt(i + 1) == '\n') {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        // 兜底：单句超长（粘了一整段没有标点的文字）时硬切，避免一条切片 4000 字
        List<String> out = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= CHUNK_CHARS * 2) {
                out.add(chunk);
                continue;
            }
            for (int i = 0; i < chunk.length(); i += CHUNK_CHARS) {
                out.add(chunk.substring(i, Math.min(chunk.length(), i + CHUNK_CHARS)));
            }
        }
        return out.isEmpty() ? List.of(normalized) : out;
    }

    static String shortHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
}
