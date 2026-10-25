package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ChatSessionRepository;

import java.util.List;
import java.util.Set;

/**
 * 会话的创建、读取、追加与交接（ADR-0001 的 P0 骨架）。
 *
 * <p>这一层只做三件这里必须做的事：<b>把 scope 传下去</b>、<b>把两种失败分清</b>
 * （不存在 / 能看不能写）、<b>把交接写成审计</b>。真正的对话编排（起草、试算、工具调用）
 * 在 ChatRuntime 里，不在这。
 *
 * <p>为什么交接要落 {@code recon_agent_run / recon_agent_step} 而不新开表：会话换了人接手，
 * 是一条需要"谁在什么时候把哪条会话给了谁"的凭证——这正是轨迹表已经在记的东西。
 * 多一张表就多一套查询、权限、归档，而它记的内容和轨迹是同一类。
 */
public class ChatSessionService {

    /** 只认这三个角色：别的值进了库，前端渲染和压缩策略都要另行处理。 */
    private static final Set<String> ROLES = Set.of("user", "assistant", "tool");

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_CONTENT_LENGTH = 20_000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final ChatSessionRepository sessions;
    private final AgentRunRepository runs;

    public ChatSessionService(ChatSessionRepository sessions, AgentRunRepository runs) {
        this.sessions = sessions;
        this.runs = runs;
    }

    public List<ChatSession> recent(SessionScope scope, int limit) {
        return sessions.recent(scope, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    public ChatSession open(SessionScope scope, String title) {
        String clean = title == null || title.isBlank() ? "未命名会话" : title.trim();
        if (clean.length() > MAX_TITLE_LENGTH) {
            clean = clean.substring(0, MAX_TITLE_LENGTH);
        }
        long id = sessions.create(scope.operator(), Visibility.PRIVATE, clean);
        return sessions.find(scope, id)
                .orElseThrow(() -> new IllegalStateException("刚建的会话读不到：" + id));
    }

    /** 不存在、或不属于你 —— 同一个 404（N8）。 */
    public ChatSession require(SessionScope scope, long sessionId) {
        return sessions.find(scope, sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    public List<ChatTurn> turns(SessionScope scope, long sessionId) {
        require(scope, sessionId);
        return sessions.turns(sessionId);
    }

    /**
     * 取一页轮次（{@code seq < beforeSeq} 的最后 {@code limit} 条）。
     *
     * <p>为什么默认是"最后一页"而不是第一页：人打开会话想看的是刚聊到哪；从头开始翻页，
     * 聊过 200 轮的会话要连点十几下才回到今天。
     *
     * @param beforeSeq 往前翻的游标，{@code null} 表示最后一页
     */
    public TurnPage page(SessionScope scope, long sessionId, Integer beforeSeq, int limit) {
        return page(scope, sessionId, beforeSeq, null, limit);
    }

    /**
     * 取一页轮次。两个方向二选一：
     * <ul>
     *   <li>{@code beforeSeq} / 都不给：往前翻，返回 {@code seq < before} 的最后 {@code limit} 条；</li>
     *   <li>{@code fromSeq}：从头翻，返回 {@code seq >= from} 的最前 {@code limit} 条。</li>
     * </ul>
     *
     * <p>两个方向都给时以 {@code fromSeq} 为准并忽略 {@code beforeSeq}（API 层两个都必填不出来，
     * 这里只做兜底）。响应里同时带 {@code nextBefore} 与 {@code nextAfter}，任一方向翻到中间时
     * 都能接着往两头走。
     */
    public TurnPage page(SessionScope scope, long sessionId, Integer beforeSeq, Integer fromSeq, int limit) {
        require(scope, sessionId);
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (fromSeq != null && fromSeq > 0) {
            List<ChatTurn> raw = sessions.turnsFrom(sessionId, fromSeq, size + 1);
            boolean hasMoreAfter = raw.size() > size;
            List<ChatTurn> page = hasMoreAfter ? raw.subList(0, size) : raw;
            Integer nextAfter = page.isEmpty() ? null : page.get(page.size() - 1).seq() + 1;
            boolean hasBefore = page.isEmpty() ? false : sessions.existsBefore(sessionId, page.get(0).seq());
            Integer nextBefore = page.isEmpty() ? null : page.get(0).seq();
            return new TurnPage(page, hasBefore, hasBefore ? nextBefore : null,
                    hasMoreAfter, hasMoreAfter ? nextAfter : null);
        }
        int cursor = beforeSeq == null || beforeSeq <= 0 ? Integer.MAX_VALUE : beforeSeq;
        // 多取一条用来判断"还有没有更早的"，省掉一次 COUNT
        List<ChatTurn> raw = sessions.turns(sessionId, cursor, size + 1);
        boolean hasMore = raw.size() > size;
        List<ChatTurn> page = hasMore ? raw.subList(raw.size() - size, raw.size()) : raw;
        Integer nextBefore = page.isEmpty() ? null : page.get(0).seq();
        boolean hasAfter = page.isEmpty() ? false : sessions.existsAfter(sessionId, page.get(page.size() - 1).seq());
        Integer nextAfter = page.isEmpty() ? null : page.get(page.size() - 1).seq() + 1;
        return new TurnPage(page, hasMore, hasMore ? nextBefore : null,
                hasAfter, hasAfter ? nextAfter : null);
    }

    /**
     * 一页轮次。
     *
     * @param hasMoreBefore 还有更早的轮次
     * @param nextBefore    往前翻的游标（{@code null} 表示没有更早的）——必须由服务端给，
     *                      让前端自己算 {@code min(seq)} 会在"这一页刚好从 seq=1 开始"时算成 0
     * @param hasMoreAfter  还有更新的轮次（翻到中间时前端据此显示"回到最新"）
     * @param nextAfter     往后翻的游标（{@code null} 表示已经是最新）
     */
    public record TurnPage(List<ChatTurn> turns, boolean hasMoreBefore, Integer nextBefore,
                           boolean hasMoreAfter, Integer nextAfter) {
    }

    /** 写摘要（水位线由 {@code ChatSummaryService} 算，这里只落库并做权限判定）。 */
    public boolean saveSummary(SessionScope scope, long sessionId, String summary, int uptoSeq) {
        requireWritable(scope, sessionId);
        return sessions.saveSummary(scope, sessionId, summary, uptoSeq);
    }

    public ChatTurn append(SessionScope scope, long sessionId, String role, String content) {
        return append(scope, sessionId, role, content, null);
    }

    /**
     * 追加一轮，可带 {@code tool_calls}。
     *
     * <p>为什么工具调用要单独一列而不是拼进正文：正文是给人看的，工具调用是给审计和回放看的。
     * 混在一起，事后想统计"这条会话调过哪些工具"就得正则解析自然语言。
     */
    public ChatTurn append(SessionScope scope, long sessionId, String role, String content, String toolCalls) {
        ChatSession session = requireWritable(scope, sessionId);
        if (role == null || !ROLES.contains(role.trim().toLowerCase())) {
            throw new IllegalArgumentException("未知角色：" + role + "（可选 user / assistant / tool）");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容为空：空轮次会让压缩策略的 token 估算失去意义");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("单轮内容过长（>" + MAX_CONTENT_LENGTH + " 字符）");
        }
        int seq = session.turnCount() + 1;
        boolean written = sessions.appendTurn(scope, sessionId, seq, role.trim().toLowerCase(), content,
                toolCalls, null);
        if (!written) {
            // 条件 UPDATE 没命中：查完到写之间 owner 变了（比如刚被别人交接走）
            throw new SessionNotWritableException(sessionId, scope.operator(), session.operator());
        }
        List<ChatTurn> turns = sessions.turns(sessionId);
        return turns.get(turns.size() - 1);
    }

    /**
     * 保存槽位（覆盖写，带版本比较交换）。
     *
     * <p>不在 {@code append} 里顺手写：一句话可能只更新槽位不产生轮次（比如用户点了"看差异"按钮），
     * 也可能产生轮次但槽位不变（"再念一遍"）。拆开才不用为每种组合写一个特例。
     *
     * @return {@code true} 写成功；{@code false} 表示读到的版本已经被别人改过（并发），
     *         调用方应该重读会话再决定——这里不替调用方猜该保留哪一份
     */
    public boolean saveSlots(SessionScope scope, long sessionId, int expectedRev, String stateJson) {
        requireWritable(scope, sessionId);
        return sessions.updateState(scope, sessionId, expectedRev, stateJson);
    }

    public ChatSession share(SessionScope scope, long sessionId, Visibility visibility) {
        requireWritable(scope, sessionId);
        if (!sessions.setVisibility(scope, sessionId, visibility)) {
            throw new SessionNotFoundException(sessionId);
        }
        return require(scope, sessionId);
    }

    /**
     * 交接：把会话交给另一个人继续问。写一条 {@code SESSION_HANDOVER} 轨迹。
     *
     * <p>为什么必须有这条轨迹：会话里记着"谁说过什么"，但"这条会话本该属于 A 却出现在 B 的列表里"
     * 是一个需要解释的事实。交接不记账，事后就无法回答"谁给她的"。
     */
    public ChatSession handover(SessionScope scope, long sessionId, String toOperator) {
        ChatSession session = requireWritable(scope, sessionId);
        String target = new SessionScope(toOperator).operator(); // 复用同一套非空/长度校验
        if (target.equals(session.operator())) {
            throw new IllegalArgumentException("交接目标与会话当前 owner 相同：" + target);
        }
        if (!sessions.transfer(scope, sessionId, target)) {
            throw new SessionNotFoundException(sessionId);
        }
        recordHandover(session, target);
        // 交接之后原 owner 已经读不到了，必须换新 owner 的 scope 再读一遍
        return sessions.find(SessionScope.of(target), sessionId)
                .orElseThrow(() -> new IllegalStateException("交接后新 owner 读不到会话：" + sessionId));
    }

    private ChatSession requireWritable(SessionScope scope, long sessionId) {
        ChatSession session = require(scope, sessionId);
        if (!session.ownedBy(scope.operator())) {
            throw new SessionNotWritableException(sessionId, scope.operator(), session.operator());
        }
        return session;
    }

    private void recordHandover(ChatSession session, String toOperator) {
        long runId = runs.startRun(
                "会话交接 #" + session.id() + "：" + session.operator() + " → " + toOperator,
                "SESSION_HANDOVER", session.operator());
        String payload = "会话 #" + session.id()
                + "（" + (session.title() == null ? "-" : session.title()) + "，" + session.turnCount() + " 轮）"
                + " owner：" + session.operator() + " → " + toOperator
                + "，可见性：" + Visibility.HANDOVER.name();
        runs.recordStep(runId, 1, "ACT", "handover-session",
                "session=" + session.id(), payload, null, false, "OK", null);
        runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, null, null, null, null);
    }
}
