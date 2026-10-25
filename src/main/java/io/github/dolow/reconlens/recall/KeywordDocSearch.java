package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.DocChunkRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关键词检索：中文按二元组切，英文按空格切，逐词 LIKE。
 *
 * <p>为什么不用 FULLTEXT：H2 没有，MySQL 有但分词器对中文默认按空格，
 * 中文整段进不了索引；二元组 + LIKE 是唯一"两边都能跑、行为一致"的做法。
 * 代价是扫行，所以<b>先按元数据收窄</b>，且只取候选上限 200 行再打分——
 * 口径文档是几十到几百篇，这个量级 LIKE 完全够用；真到上万篇再换向量（backlog B5）。
 */
public class KeywordDocSearch implements DocSearch {

    private static final int CANDIDATE_LIMIT = 200;

    private final DocChunkRepository repository;

    public KeywordDocSearch(DocChunkRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<DocHit> search(String query, String source, int limit) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<DocChunk> candidates = repository.searchCandidates(terms, source, CANDIDATE_LIMIT);
        List<DocHit> hits = new ArrayList<>();
        for (DocChunk chunk : candidates) {
            String content = chunk.content() == null ? "" : chunk.content().toLowerCase(Locale.ROOT);
            List<String> matched = new ArrayList<>();
            for (String term : terms) {
                if (content.contains(term.toLowerCase(Locale.ROOT))) {
                    matched.add(term);
                }
            }
            if (!matched.isEmpty()) {
                // 命中词数为主，切片越靠前越优先（同一篇文档里先出现的一般是定义）
                int score = matched.size() * 10 - Math.min(chunk.ordinal(), 9);
                hits.add(new DocHit(chunk, score, matched));
            }
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    @Override
    public String describe() {
        return "关键词检索（中文二元组 + LIKE，元数据过滤在前）";
    }

    /**
     * 切词：中文连续片段切成二元组（"账单口径" → 账单/单口/口径），
     * 单字片段原样保留；英文数字按空白与标点切。
     *
     * <p>不用分词器是因为没有一份"对账领域词典"，硬切反而会把"对账单"切成"对账/账单"之外的噪音。
     */
    static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();
        for (char c : query.toCharArray()) {
            if (isCjk(c)) {
                flushLatin(latin, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                latin.append(c);
            } else {
                flushCjk(cjk, out);
                flushLatin(latin, out);
            }
        }
        flushCjk(cjk, out);
        flushLatin(latin, out);
        return new ArrayList<>(out);
    }

    private static void flushCjk(StringBuilder buf, Set<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String run = buf.toString();
        buf.setLength(0);
        if (run.length() == 1) {
            out.add(run);
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            out.add(run.substring(i, i + 2));
        }
    }

    private static void flushLatin(StringBuilder buf, Set<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String word = buf.toString();
        buf.setLength(0);
        if (word.length() >= 2) {
            out.add(word);
        }
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
