package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.chat.SessionHit;
import io.github.dolow.reconlens.chat.SessionRecallService;
import io.github.dolow.reconlens.chat.SessionScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨会话检索（ADR-0001 N9）：{@code GET /api/chat/recall?q=&scope=mine|team}。
 *
 * <p>单独一个控制器而不是塞进 {@code ChatSessionController}：后者挂在
 * {@code /api/chat/sessions} 下，把"搜所有会话"塞进"某个会话"的路径下会让人以为
 * 它是会话内检索。URL 本身就是一条文档。
 *
 * <p>默认 {@code scope=mine}。要搜团队范围必须有人真的写下 {@code scope=team}——
 * 这里刻意不提供"配置项默认开启"，因为默认放宽会把人半成品的判断变成全团队可搜。
 */
@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class ChatRecallController {

    private final SessionRecallService recall;

    public ChatRecallController(SessionRecallService recall) {
        this.recall = recall;
    }

    @GetMapping("/recall")
    public Map<String, Object> recall(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam String q,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "10") int limit) {
        SessionScope caller = SessionScope.of(operator);
        SessionRecallService.Scope mode = SessionRecallService.Scope.of(scope);
        List<SessionHit> hits = recall.search(caller, q, mode, limit);

        List<Map<String, Object>> items = new ArrayList<>();
        for (SessionHit hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", hit.sessionId());
            item.put("owner", hit.owner());
            item.put("visibility", hit.visibility());
            item.put("title", hit.title());
            item.put("seq", hit.seq());
            item.put("role", hit.role());
            item.put("snippet", hit.snippet(160));
            item.put("score", hit.score());
            item.put("matched", hit.matched());
            item.put("createdAt", hit.createdAt());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", q);
        body.put("scope", mode.name());
        body.put("description", recall.describe(mode));
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
