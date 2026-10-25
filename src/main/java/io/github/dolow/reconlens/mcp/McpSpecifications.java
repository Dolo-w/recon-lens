package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link ToolRegistry} 翻译成 MCP 的 {@code tools/list}。
 *
 * <p>抽出来是因为 P3 多了一条传输通道（streamable HTTP）：stdio 与 HTTP 两个 server
 * 实例必须给出<b>同一份</b>工具表。如果各自装配一遍，迟早出现"stdio 有 15 个、HTTP 有 14 个"，
 * 而这类不一致在客户端看来只是"那个工具不存在"，极难排查。
 */
public final class McpSpecifications {

    private McpSpecifications() {
    }

    /**
     * @param agentTools 0 或 1 个（Agent 关闭时注入空列表）
     */
    public static List<McpServerFeatures.SyncToolSpecification> build(
            McpJsonMapper jsonMapper, ToolRegistry registry, List<AgentMcpTools> agentTools) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>(base(jsonMapper, registry));
        if (agentTools != null) {
            agentTools.forEach(agent -> specs.addAll(agent.specifications(jsonMapper)));
        }
        return specs;
    }

    public static List<McpServerFeatures.SyncToolSpecification> base(
            McpJsonMapper jsonMapper, ToolRegistry registry) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (ToolSpec spec : registry.specs()) {
            specs.add(McpToolFactory.tool(jsonMapper, spec.name(), spec.description(),
                    spec.inputSchema(), spec.outputSchema(),
                    a -> {
                        ToolOutcome outcome = spec.invoke(a);
                        return outcome.structured() == null
                                ? McpToolFactory.ToolResult.text(outcome.text())
                                : McpToolFactory.ToolResult.of(outcome.text(), outcome.structured());
                    }));
        }
        return specs;
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
