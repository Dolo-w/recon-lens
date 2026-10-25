package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.chat.ChatSession;
import io.github.dolow.reconlens.chat.ChatSessionService;
import io.github.dolow.reconlens.chat.ChatSummaryService;
import io.github.dolow.reconlens.chat.ChatTurn;
import io.github.dolow.reconlens.chat.SessionNotFoundException;
import io.github.dolow.reconlens.chat.SessionNotWritableException;
import io.github.dolow.reconlens.chat.SessionScope;
import io.github.dolow.reconlens.chat.Visibility;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话接口：列表 / 新建 / 读一轮历史 / 追加 / 改可见性 / 交接。
 *
 * <p><b>{@code X-Operator} 必填</b>，与审批接口同一个口径。理由也一样：会话按 operator 隔离，
 * 不署名的请求落库就变成"一条谁都认领不了的会话"，而且隔离本身也就失效了。
 *
 * <p><b>为什么不挂 {@code X-Api-Key}</b>：{@code ApiSecurity} 管的是数据面写接口（触发对账、重建模型、
 * 删规则），它们会改变客户的数据与规则库；会话只写自己的上下文，危害面是"别人的对话框"。
 * 真要上鉴权，正确的做法是把 operator 从 header 换成认证主体，而不是再加一把共享密钥。
 *
 * <p>整个控制器跟着 {@code recon.agent.enabled} 装配：会话的消费方是 ChatRuntime，关掉 Agent 时
 * 这几个端点没有意义（见 {@code ChatConfiguration} 的说明）。
 */
@RestController
@RequestMapping("/api/chat/sessions")
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class ChatSessionController {

    private final ChatSessionService chatSessions;
    private final ChatSummaryService summaries;
    private final io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts;

    public ChatSessionController(ChatSessionService chatSessions, ChatSummaryService summaries,
                                 io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts) {
        this.chatSessions = chatSessions;
        this.summaries = summaries;
        this.chatDrafts = chatDrafts;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "X-Operator", required = false) String operator,
                                    @RequestParam(defaultValue = "20") int limit) {
        SessionScope scope = scope(operator);
        List<ChatSession> items = chatSessions.recent(scope, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operator", scope.operator());
        body.put("items", items);
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestBody(required = false) CreateRequest request) {
        ChatSession session = chatSessions.open(scope(operator), request == null ? null : request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("session", session));
    }

    /**
     * 会话详情：会话本身 + 一页轮次。
     *
     * <p>{@code limit} 默认 50：上限（200）对渲染是灾难，而 50 已经够看清"刚聊了什么"。
     * {@code before} 是往前翻的游标（不传就是最后一页），{@code from} 是"从头翻"的起点；
     * 返回值带 {@code turnsHasMore}/{@code nextBefore} 与 {@code turnsHasMoreAfter}/{@code nextAfter}，
     * 前端不需要自己去猜"还有没有更早/更新的"。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@RequestHeader(value = "X-Operator", required = false) String operator,
                                      @PathVariable long id,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) Integer before,
                                      @RequestParam(required = false) Integer from) {
        SessionScope scope = scope(operator);
        ChatSession session = chatSessions.require(scope, id);
        ChatSessionService.TurnPage page = chatSessions.page(scope, id, before, from, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session", session);
        body.put("turns", page.turns());
        body.put("turnsHasMore", page.hasMoreBefore());
        body.put("nextBefore", page.nextBefore());
        body.put("turnsHasMoreAfter", page.hasMoreAfter());
        body.put("nextAfter", page.nextAfter());
        return body;
    }

    /**
     * 这个会话起草过哪些规则版本。
     *
     * <p>「第二条改一下」「刚才那条」靠的就是这里的顺序与 label。把账本开出来，人和程序用的是
     * 同一份事实，而不是让模型凭上下文猜——猜错的代价是改错规则版本。
     */
    @GetMapping("/{id}/drafts")
    public Map<String, Object> drafts(@RequestHeader(value = "X-Operator", required = false) String operator,
                                      @PathVariable long id) {
        SessionScope scope = scope(operator);
        chatSessions.require(scope, id);
        List<io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft> items = chatDrafts.list(id);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft draft = items.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i + 1);
            row.put("ruleCode", draft.ruleCode());
            row.put("version", draft.ruleVersion());
            row.put("label", draft.label());
            row.put("createdAt", draft.createdAt());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", id);
        body.put("items", rows);
        return body;
    }

    /**
     * 手动压一次摘要。
     *
     * <p>自动触发要聊到 2000 token 才发生，排查/演示时等不起，所以留一条能当场验证的路径。
     * 它写的是同一张表的同一列，不产生"另一份摘要"。
     */
    @PostMapping("/{id}/summary")    public Map<String, Object> summarize(@RequestHeader(value = "X-Operator", required = false) String operator,
                                         @PathVariable long id) {
        SessionScope scope = scope(operator);
        String summary = summaries.summarizeNow(scope, id);
        ChatSession session = chatSessions.require(scope, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("summaryUptoSeq", session.summaryUptoSeq());
        return body;
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<Map<String, Object>> append(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @PathVariable long id,
            @RequestBody AppendRequest request) {
        ChatTurn turn = chatSessions.append(scope(operator), id, request.role(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("turn", turn));
    }

    @PostMapping("/{id}/visibility")
    public Map<String, Object> share(@RequestHeader(value = "X-Operator", required = false) String operator,
                                     @PathVariable long id,
                                     @RequestBody VisibilityRequest request) {
        Visibility target = Visibility.of(request.visibility());
        return Map.of("session", chatSessions.share(scope(operator), id, target));
    }

    /**
     * 交接。目标人写在 body 里而不是 header：这是<b>对另一个人权限的授予</b>，
     * 写在 header 里容易和"当前操作人"混成一个字段。
     */
    @PostMapping("/{id}/handover")
    public Map<String, Object> handover(@RequestHeader(value = "X-Operator", required = false) String operator,
                                        @PathVariable long id,
                                        @RequestBody HandoverRequest request) {
        return Map.of("session", chatSessions.handover(scope(operator), id, request.toOperator()));
    }

    private static SessionScope scope(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("缺少 X-Operator：会话按 operator 隔离，未署名不落库");
        }
        return SessionScope.of(operator);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SessionNotFoundException e) {
        // 不区分"不存在"与"不属于你"，也不回显标题（标题里常有客户名与账期，见 SessionNotFoundException）
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会话不存在或不属于当前操作人"));
    }

    @ExceptionHandler(SessionNotWritableException.class)
    public ResponseEntity<Map<String, Object>> notWritable(SessionNotWritableException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    public record CreateRequest(String title) {
    }

    public record AppendRequest(String role, String content) {
    }

    public record VisibilityRequest(String visibility) {
    }

    public record HandoverRequest(String toOperator) {
    }
}
