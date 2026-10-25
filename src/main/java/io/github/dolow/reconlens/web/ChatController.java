package io.github.dolow.reconlens.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.ChatEvent;
import io.github.dolow.reconlens.chat.ChatFormService;
import io.github.dolow.reconlens.chat.ChatForms;
import io.github.dolow.reconlens.chat.ChatRuntime;
import io.github.dolow.reconlens.chat.ChatSession;
import io.github.dolow.reconlens.chat.ChatSessionService;
import io.github.dolow.reconlens.chat.SessionNotFoundException;
import io.github.dolow.reconlens.chat.SessionNotWritableException;
import io.github.dolow.reconlens.chat.SessionScope;
import io.github.dolow.reconlens.chat.plan.ChatPlanner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内建会话的唯一入口：{@code POST /api/chat}，SSE 回推。
 *
 * <p>为什么是 SSE 而不是 WebSocket 或轮询：一次请求对应一次回答，中间只需要服务端单向推几步
 * （识别到哪个动作、在调哪个工具、正文分片）。SSE 就是"一个长响应的 HTTP"，浏览器
 * {@code EventSource} 原生支持，Nginx 也不用改配置——引进 WebSocket 是为了一条单向流付出双向协议的代价。
 *
 * <p>为什么要 {@code X-Operator} 必填：会话是按人隔离的（ADR-0001 D8）。这里没有登录，
 * 所以操作人只能由调用方声明——<b>但要声明，不能默认</b>。默认成 {@code anonymous} 的后果是
 * 所有人共用一个会话列表，那比不做隔离更糟：看起来隔离了。
 */
@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class ChatController {

    private static final long TIMEOUT_MS = 180_000L;

    private final ChatRuntime runtime;
    private final ChatFormService forms;
    private final ChatSessionService sessions;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper json;
    private final ChatPlanner planner;

    public ChatController(ChatRuntime runtime, ChatFormService forms, ChatSessionService sessions,
                          @Qualifier("chatExecutor") ThreadPoolTaskExecutor executor, ObjectMapper json,
                          ChatPlanner planner) {
        this.runtime = runtime;
        this.forms = forms;
        this.sessions = sessions;
        this.executor = executor;
        this.planner = planner;
        this.json = json;
    }

    /**
     * 说一句话。{@code sessionId} 为空时新建一个会话（标题取消息前 20 字）。
     *
     * <p>先用普通方法把会话校验一遍再开 SSE：会话不属于你时应该是一个干净的 404，
     * 而不是"流已经 200 了，第一个事件说没有权限"——后者前端很难处理，日志也难对。
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader("X-Operator") String operator,
                           @RequestBody ChatRequest request) {
        SessionScope scope = SessionScope.of(operator);
        String message = request.message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息为空");
        }
        long sessionId = request.sessionId() != null
                ? request.sessionId()
                : sessions.open(scope, titleOf(message)).id();
        sessions.require(scope, sessionId); // 404 在建流之前抛出

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        executor.execute(() -> {
            try {
                runtime.handle(scope, sessionId, message, event -> send(emitter, event));
                emitter.complete();
            } catch (UncheckedIOException e) {
                // 客户端断开：不是服务端错误，别再往已关闭的流里写
                emitter.completeWithError(e);
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(json.writeValueAsString(event.data()), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 表单清单：无 key 用户靠它画出按钮和输入框。
     *
     * <p>不需要 {@code X-Operator}：这是**接口的元数据**（有哪些动作、各要哪些参数），
     * 和 OpenAPI 文档同一性质；真正的动作执行才需要声明操作人。
     */
    /**
     * 现在用哪一层在理解意图。
     *
     * <p>单独开这个查询口，是因为"模型到底有没有生效"不能靠猜：配了 key 但网关挂了、
     * 熔断了、模型名写错了，页面上的表现都是一样的——都还能用，只是理解得笨一点。
     * 排障时第一个要看的就是这个接口。
     */
    @GetMapping("/planner")
    public Map<String, Object> planner() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planner", planner.describe());
        body.put("modelEnabled", planner.describe().contains("模型路由：OpenAI"));
        return body;
    }

    @GetMapping("/forms")
    public Map<String, Object> forms() {
        return Map.of("items", ChatForms.all());
    }

    /**
     * 表单提交：{@code {action, values}} → 与会话完全相同的动作执行，返回 JSON 而不是 SSE。
     *
     * <p>为什么表单不走 SSE：表单是一次点击换一段结果，分成 18 个 delta 推到浏览器只是让
     * 前端多写一次拼接。SSE 留给"一句话"那种需要边想边说的场景。
     */
    @PostMapping("/actions")
    public Map<String, Object> submitAction(@RequestHeader("X-Operator") String operator,
                                            @RequestBody ActionRequest request) {
        ChatFormService.Result result = forms.submit(SessionScope.of(operator), request.sessionId(),
                request.action(), request.values());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", result.sessionId());
        body.put("action", result.action().name());
        body.put("text", result.text());
        body.put("tools", result.tools());
        body.put("slots", result.slots());
        body.put("slotsSaved", result.slotsSaved());
        return body;
    }

    private static String titleOf(String message) {
        String clean = message.strip().replaceAll("\\s+", " ");
        return clean.length() <= 20 ? clean : clean.substring(0, 20);
    }

    /** 请求体。{@code sessionId} 为空 = 新建会话。 */
    public record ChatRequest(Long sessionId, String message) {
    }

    /** 表单提交体：{@code values} 的键就是 {@link io.github.dolow.reconlens.chat.ChatForm.Field#name()}。 */
    public record ActionRequest(Long sessionId, String action, Map<String, String> values) {
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SessionNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", "会话不存在或不属于当前操作人"));
    }

    @ExceptionHandler(SessionNotWritableException.class)
    public ResponseEntity<Map<String, Object>> notWritable(SessionNotWritableException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }
}
