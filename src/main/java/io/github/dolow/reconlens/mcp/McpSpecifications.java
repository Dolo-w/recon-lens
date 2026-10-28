package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.engine.Errors;
import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link ToolRegistry} 翻译成 MCP 的 {@code tools/list}——<b>全项目唯一的翻译点</b>。
 *
 * <p>抽出来是因为 P3 多了一条传输通道（streamable HTTP）：stdio 与 HTTP 两个 server
 * 实例必须给出<b>同一份</b>工具表。如果各自装配一遍，迟早出现"stdio 有 15 个、HTTP 有 14 个"，
 * 而这类不一致在客户端看来只是"那个工具不存在"，极难排查。
 *
 * <p>2026-10-26 之后它还兼一条职责：Agent 专属工具也走这里。<b>目录只有一个</b>
 * （{@code tool.ToolCatalog}），{@code /api/tools}、会话路由、stdio 宿主、HTTP 宿主都读它，
 * 不再有"基础工具注一遍、Agent 工具另注一遍"的可能。
 */
public final class McpSpecifications {

    private static final Logger log = LoggerFactory.getLogger(McpSpecifications.class);

    private McpSpecifications() {
    }

    /** 目录里有什么，{@code tools/list} 就给什么，顺序都不变。 */
    public static List<McpServerFeatures.SyncToolSpecification> build(
            McpJsonMapper jsonMapper, ToolRegistry registry) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (ToolSpec spec : registry.specs()) {
            specs.add(toMcp(jsonMapper, spec));
        }
        return specs;
    }

    /**
     * 一个 {@link ToolSpec} → 一条 MCP 工具。
     *
     * <p>三件事必须在这里做，且只能在这里做一次：{@code outputSchema} 与 {@code structuredContent} 成对出现、
     * 失败转成 {@code isError=true} 的文本、参数原样传下去（解析是工具自己的事）。
     */
    static McpServerFeatures.SyncToolSpecification toMcp(McpJsonMapper jsonMapper, ToolSpec spec) {
        McpSchema.Tool.Builder builder = McpSchema.Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(jsonMapper, spec.inputSchema());
        if (spec.outputSchema() != null && !spec.outputSchema().isBlank()) {
            builder.outputSchema(jsonMapper, spec.outputSchema());
        }
        McpSchema.Tool tool = builder.build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        ToolOutcome outcome = spec.invoke(args);
                        McpSchema.CallToolResult.Builder out = McpSchema.CallToolResult.builder()
                                .addTextContent(outcome.text())
                                .isError(false);
                        if (outcome.structured() != null && !outcome.structured().isEmpty()) {
                            out.structuredContent(outcome.structured());
                        }
                        return out.build();
                    } catch (Exception e) {
                        log.warn("MCP 工具 {} 调用失败：{}", spec.name(), Errors.describe(e));
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("调用失败：" + Errors.describe(e))
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    /** 给 {@code instructions} 用：两个通道必须说同一段话。 */
    public static String instructions() {
        return "配置化对账引擎：先 recon_list_rules 看有哪些规则，再 recon_run 跑对账（返回任务号），"
                + "然后用 recon_list_diffs / recon_explain_job 看差异与归因。"
                + "要换对账维度或比对字段就调 recon_define_rule 提交新版本"
                + "（落为草稿并自动跑影子试算，审批通过才生效，不需要改代码或重启）；"
                + "只想临时试算可以用 recon_run 的 ruleJson 内联一份规则。"
                + "如果同一份数据要反复对账，可以先用 recon_build_model 把它物化成模型表，之后每轮只读模型表。"
                + "启用 Agent 后还可以用 recon_draft_rule 把一句需求起草成规则草稿"
                + "（后台起草 + 影子试算，用 recon_trial_status 轮询）；"
                + "数据结构不同的新接入方，先用 recon_register_table 预览（action=preview）并经人确认"
                + "（action=confirm）注册他们的表，再起草规则；"
                + "只有 CSV/Excel 文件的接入方，把文件放进导入目录后用 recon_import_file 走同样的两段式，"
                + "导入会建表灌数并自动注册；"
                + "规则生效必须经人审批：把试算结论讲给用户，用户同意后用 recon_submit_approval 代办"
                + "（decision=APPROVED/REJECTED，confirmedBy 填用户标识），未审批的规则 recon_run 用不了。";
    }

    public static final String SERVER_NAME = "recon-lens";
    public static final String SERVER_VERSION = "0.1.0";
}
