package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.store.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 跨会话检索：<b>默认只搜自己的会话，{@code team} 必须显式传</b>（ADR-0001 N9）。
 *
 * <p>为什么默认不是 team：会话里出现的东西包括"我刚试的这条规则可能有问题"、"这个客户的差异
 * 我怀疑是刷单"。默认可见会把这类半成品判断变成全团队可见，而人不会因为"技术上能搜到"就改自己说话的方式。
 * 所以这里和 {@code visibility} 用一个相反的默认值：会话默认 PRIVATE，检索默认我只搜我。
 *
 * <p>为什么不做向量：会话轮次是短句、专有名词多（规则名、任务号、diffType），
 * 二元组匹配在这类数据上比语义检索更准，也更便宜（ADR-0001 D3「能键匹配就别做语义」）。
 *
 * <p>为什么 team 也不搜别人的 PRIVATE：这不是权限策略问题，是数据模型问题——
 * PRIVATE 的语义就是"除了我没人能读"，检索不能是后门。
 */
public class SessionRecallService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRecallService.class);
    private static final int MAX_LIMIT = 50;
    private static final int SNIPPET_CHARS = 160;

    public enum Scope {
        /** 只看自己的会话（默认）。 */
        MINE,
        /** 自己 + 别人共享/交接出来的会话，每条命中带 owner。 */
        TEAM;

        public static Scope of(String raw) {
            if (raw == null || raw.isBlank()) {
                return MINE;
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            return switch (v) {
                case "MINE", "SELF", "OWN" -> MINE;
                case "TEAM", "ALL" -> TEAM;
                default -> throw new IllegalArgumentException(
                        "scope 只能是 mine 或 team，收到：" + raw + "（默认 mine，team 要求显式传参）");
            };
        }
    }

    private final ChatSessionRepository repository;

    public SessionRecallService(ChatSessionRepository repository) {
        this.repository = repository;
    }

    /**
     * @param scope 传 null 等于 {@link Scope#MINE}；要搜团队必须有人真的写下 team
     */
    public List<SessionHit> search(SessionScope caller, String query, Scope scope, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索词不能为空");
        }
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<SessionHit> hits = repository.recall(caller, terms, scope == Scope.TEAM, capped);
        LOG.info("会话检索 operator={} scope={} terms={} hits={}",
                caller.operator(), scope, terms, hits.size());
        return hits;
    }

    public String describe(Scope scope) {
        return scope == Scope.TEAM
                ? "团队范围：我的会话 + 共享（SHARED）与会话交接（HANDOVER）出来的别人的会话；别人的 PRIVATE 永远搜不到"
                : "只看我自己的会话（默认）；要搜团队范围请显式传 scope=team";
    }

    static int snippetChars() {
        return SNIPPET_CHARS;
    }

    /**
     * 中文切二元组、英文按非字母数字切。和口径文档检索用同一套规则，理由也一样：
     * 对账场景的文本没有一个靠谱的分词器，"两字窗"是最便宜的召回手段。
     */
    static List<String> terms(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (isCjk(c)) {
                flushWord(word, out);
                run.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                run.setLength(0);
                word.append(c);
            } else {
                flushRun(run, out);
                flushWord(word, out);
            }
        }
        flushRun(run, out);
        flushWord(word, out);
        return new ArrayList<>(out);
    }

    private static void flushRun(StringBuilder run, LinkedHashSet<String> out) {
        if (run.length() == 1) {
            out.add(run.toString());
        } else if (run.length() > 1) {
            for (int i = 0; i + 1 < run.length(); i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        run.setLength(0);
    }

    private static void flushWord(StringBuilder word, LinkedHashSet<String> out) {
        if (word.length() >= 2) {
            out.add(word.toString().toLowerCase(Locale.ROOT));
        }
        word.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
