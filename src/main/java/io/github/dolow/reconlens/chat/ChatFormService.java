package io.github.dolow.reconlens.chat;

import java.util.List;
import java.util.Map;

/**
 * 表单提交：把 {@code {action, values}} 变成一次和会话完全相同的动作执行。
 *
 * <p>存在的理由只有一个：{@link ChatRuntime#handle} 的输入是**自然语言**，而表单的输入是
 * **结构化参数**。把两者硬凑成一条路径，要么让路由去解析 JSON（多一层无用转换），
 * 要么让表单去拼中文句子（用户点按钮却要生成一句人话）。所以分成两个入口、一套实现：
 * 都走 {@link ChatRuntime#submit}，槽位、闸门、审计一模一样。
 *
 * <p>这里**不做任何参数猜测**：表单上没填就是没填，交给下游按槽位回落或反问。
 * 唯一的前置校验是"必填项不能空"——那是表单自己的契约（例如起草规则必须给正文）。
 */
public class ChatFormService {

    /** 一次表单提交的结果。 */
    public record Result(long sessionId, ChatIntent action, String text, List<String> tools,
                         ChatSlots slots, boolean slotsSaved) {
    }

    private final ChatSessionService sessions;
    private final ChatRuntime runtime;

    public ChatFormService(ChatSessionService sessions, ChatRuntime runtime) {
        this.sessions = sessions;
        this.runtime = runtime;
    }

    public Result submit(SessionScope scope, Long sessionId, String action, Map<String, String> rawValues) {
        ChatForm form = ChatForms.of(action)
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知动作：" + action + "。可选：" + ChatForms.all().stream()
                                .map(item -> item.action().name()).toList()));
        Map<String, String> values = rawValues == null ? Map.of() : rawValues;

        for (ChatForm.Field field : form.fields()) {
            String value = values.get(field.name());
            if (field.required() && (value == null || value.isBlank())) {
                throw new IllegalArgumentException("缺少必填项：" + field.label() + "（" + field.name() + "）");
            }
        }

        long target = sessionId == null
                ? sessions.open(scope, form.label()).id()
                : sessionId;
        ChatIntentRouter.Call call = toCall(form.action(), values);
        ChatRuntime.Transcript transcript = runtime.submit(scope, target, call);
        return new Result(target, form.action(), transcript.text(), transcript.tools(),
                transcript.slots(), transcript.slotsSaved());
    }

    /**
     * 表单字段 → 路由结果。
     *
     * <p>数字字段解析失败时**报错而不是当成没填**：把 "1O0"（字母 O）读成空，会让用户以为
     * 会话忘了他的任务号，而真实原因是打错了一个字。
     */
    static ChatIntentRouter.Call toCall(ChatIntent action, Map<String, String> values) {
        return new ChatIntentRouter.Call(
                action,
                blankToNull(values.get("ruleCode")),
                number(values, "jobId"),
                number(values, "diffId"),
                blankToNull(values.get("diffType")),
                values.get("ruleBody"),
                blankToNull(values.get("table")));
    }

    private static Long number(Map<String, String> values, String name) {
        String raw = blankToNull(values.get(name));
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("「" + name + "」要填数字，收到：" + raw);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
