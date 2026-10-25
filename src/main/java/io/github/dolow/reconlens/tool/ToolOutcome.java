package io.github.dolow.reconlens.tool;

import java.util.Map;

/**
 * 一次工具调用的产物：<b>给人看的文本</b> + <b>给程序看的结构化视图</b>。
 *
 * <p>两个字段同时存在不是冗余。MCP 的 {@code outputSchema} 要求有结构化内容，
 * 而模型与人都更依赖自然语言；会话侧渲染的也是文本。谁都不该去解析对方那份。
 *
 * <p>{@code structured} 允许为 {@code null}：不是每个工具都值得声明 {@code outputSchema}
 * （声明了却不给结构化内容反而是不合规的，见 {@code McpToolFactory.ToolResult} 的注释）。
 */
public record ToolOutcome(String text, Map<String, Object> structured) {

    public static ToolOutcome text(String text) {
        return new ToolOutcome(text, null);
    }

    public static ToolOutcome of(String text, Map<String, Object> structured) {
        return new ToolOutcome(text, structured);
    }
}
