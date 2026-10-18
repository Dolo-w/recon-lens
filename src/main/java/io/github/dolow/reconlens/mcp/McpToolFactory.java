package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.engine.Errors;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * MCP 工具的装配零件：把"注册一个工具"从 {@code McpServerRunner} 里抽出来，好让 Agent 工具也能复用。
 *
 * <p>抽出来的直接原因是 {@code AgentMcpTools}：它只在 {@code recon.agent.enabled=true} 时存在，
 * 而 {@code McpServerRunner} 是常驻的。两个类都要造工具，装配代码不能各写一份 ——
 * 一旦分开写，{@code structuredContent} 的格式化、错误包装、参数解析很快就会出现两套行为，
 * 而"两处算同一个值"正是这个项目一直在防的事。
 *
 * <p><b>错误一律变成 {@code isError=true} 的文本</b>：MCP 客户端里抛异常等于把工具调用变成协议错误，
 * 模型只能看到"failed"；而带上 {@link Errors#describe} 的文本能让它自己换个参数重试。
 */
public final class McpToolFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolFactory.class);

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

    public static McpServerFeatures.SyncToolSpecification tool(McpJsonMapper jsonMapper, String name,
                                                               String description, String inputSchema,
                                                               Function<Map<String, Object>, ToolResult> body) {
        return tool(jsonMapper, name, description, inputSchema, null, body);
    }

    public static McpServerFeatures.SyncToolSpecification tool(McpJsonMapper jsonMapper, String name,
                                                               String description, String inputSchema,
                                                               String outputSchema,
                                                               Function<Map<String, Object>, ToolResult> body) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(jsonMapper, inputSchema);
        if (outputSchema != null && !outputSchema.isBlank()) {
            builder.outputSchema(jsonMapper, outputSchema);
        }
        McpSchema.Tool tool = builder.build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        ToolResult result = body.apply(args);
                        McpSchema.CallToolResult.Builder out = McpSchema.CallToolResult.builder()
                                .addTextContent(result.text())
                                .isError(false);
                        if (result.structured() != null && !result.structured().isEmpty()) {
                            out.structuredContent(result.structured());
                        }
                        return out.build();
                    } catch (Exception e) {
                        log.warn("MCP 工具 {} 调用失败：{}", name, Errors.describe(e));
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("调用失败：" + Errors.describe(e))
                                .isError(true)
                                .build();
                    }
                })
                .build();
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
