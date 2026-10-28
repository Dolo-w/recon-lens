package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolSpec;

import java.util.Map;
import java.util.function.Function;

/**
 * 造一条工具定义的零件：{@code ((name, 描述, in/out schema, 实现) → ToolSpec)}。
 *
 * <p><b>只产出 {@link ToolSpec}，不再产出 MCP 的 {@code SyncToolSpecification}</b>。
 * 以前它两头都造：基础工具走 {@code ToolRegistry}，Agent 工具走这里直接翻成 MCP 对象，
 * 于是同一个工具在「控制台的 {@code /api/tools}」和「宿主的 {@code tools/list}」里成了两份，
 * 数量对不上（12 对 18），谁都能在一边加一个而另一边不知道。
 * 现在翻译只发生在 {@link McpSpecifications} 一处，这里只负责把参数解析出来。
 *
 * <p><b>错误一律变成 {@code isError=true} 的文本</b>（在 {@code McpSpecifications} 里统一处理）：
 * MCP 客户端里抛异常等于把工具调用变成协议错误，模型只能看到 "failed"；
 * 而带上 {@code Errors#describe} 的文本能让它自己换个参数重试。
 */
public final class McpToolFactory {

    private McpToolFactory() {
    }

    /**
     * 一次工具调用的产物。
     *
     * @param structured 有没有 {@code outputSchema} 就必须有没有它：声明了 schema 却不给结构化内容，
     *                   客户端会拿到一个对不上的结果（规范要求两者同时出现）
     */
    public record ToolResult(String text, Map<String, Object> structured) {

        public static ToolResult text(String text) {
            return new ToolResult(text, null);
        }

        public static ToolResult of(String text, Map<String, Object> structured) {
            return new ToolResult(text, structured == null ? Map.of() : structured);
        }
    }

    public static ToolSpec tool(String name, String description, String inputSchema,
                                Function<Map<String, Object>, ToolResult> body) {
        return tool(name, description, inputSchema, null, body);
    }

    public static ToolSpec tool(String name, String description, String inputSchema, String outputSchema,
                                Function<Map<String, Object>, ToolResult> body) {
        return new ToolSpec(name, description, inputSchema, outputSchema, args -> {
            ToolResult result = body.apply(args == null ? Map.of() : args);
            return ToolOutcome.of(result.text(), result.structured());
        });
    }

    public static String text(Map<String, Object> args, String key, boolean required) {
        Object raw = args.get(key);
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("缺少必填参数 " + key);
            }
            return null;
        }
        return value;
    }

    /** 可选整数：{@code null} 表示调用方没传（与"传了 0"是两件事）。 */
    public static Integer optionalInteger(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        return (int) integer(args, key, 0, true);
    }

    /**
     * 往结构化视图里放一个值，<b>null 直接跳过</b>。
     *
     * <p>这不是"图省事"：{@code structuredContent} 会被客户端按 {@code outputSchema} 校验，
     * 而 JSON Schema 里 {@code "type":"integer"} 不接受 {@code null}。
     * 少一个字段是"这次没有值"，写一个 null 是"这次结果不合规"—— 前者客户端能处理，后者直接报错。
     */
    public static void put(Map<String, Object> view, String key, Object value) {
        if (value != null) {
            view.put(key, value);
        }
    }

    public static long integer(Map<String, Object> args, String key, long fallback, boolean required) {
        Object raw = args.get(key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("缺少必填参数 " + key);
            }
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 必须是整数，收到：" + raw);
        }
    }
}
