package io.github.dolow.reconlens.tool;

import java.util.Map;

/**
 * 一个工具的定义：名字、两段描述（给模型看）、输入/输出 JSON Schema，以及真正干活的那个函数。
 *
 * <p><b>为什么把 Schema 也放在这里</b>：工具最容易"腐坏"的地方不是实现，而是三处副本不同步 ——
 * MCP 的 {@code tools/list}、会话路由认识的动作、控制台按钮上的表单。名字与 schema 只要各写一份，
 * 早晚会出现"客户端提示要填 jobId，服务端却按 job 号之外的键去取"这类只有用户能发现的错。
 */
public record ToolSpec(String name,
                       String description,
                       String inputSchema,
                       String outputSchema,
                       Handler handler) {

    /** 工具实现。参数是 MCP 风格的原样 Map，解析交给 {@link ToolArgs}。 */
    @FunctionalInterface
    public interface Handler {
        ToolOutcome call(Map<String, Object> args);
    }

    public ToolOutcome invoke(Map<String, Object> args) {
        return handler.call(args == null ? Map.of() : args);
    }
}
