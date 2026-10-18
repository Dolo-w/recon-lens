package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.agent.TrialTaskRegistry;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.rule.RuleLifecycleService;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 工具目录：数量、顺序、结构化输出。
 *
 * <p>这些断言以前不存在 —— 工具数是硬编码在 {@code McpServerRunner} 里的 8 个 add 调用，
 * 谁都能加第 9 个而没人发现。现在目录被抽成可测的方法，数量本身成了一条断言。
 *
 * <p>重点在"条件性"：Agent 关闭时必须是 8 个。否则 {@code recon_draft_rule} 会在一个
 * 没有 orchestrator 的进程里出现，调用时才发现 500。
 */
class McpToolCatalogTest {

    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    private final ReconMcpTools tools = mock(ReconMcpTools.class);

    private static final List<String> BASE_TOOLS = List.of(
            "recon_list_rules", "recon_define_rule", "recon_build_model", "recon_run",
            "recon_job_summary", "recon_list_diffs", "recon_explain_diff", "recon_explain_job",
            "recon_diagnose");

    @Test
    void nineToolsWhenAgentIsDisabled() {
        List<String> names = McpServerRunner.toolNames(runnerWithoutAgent().specifications(jsonMapper));

        assertThat(names).containsExactlyElementsOf(BASE_TOOLS);
    }

    /**
     * 用真实实例而不是 mock：这条断言问的就是"目录里有几个"，
     * 而 mock 的 specifications() 返回 null，addAll(null) 什么也不加 —— 数量断言会假绿/假红。
     */
    @Test
    void fourteenToolsWhenAgentIsEnabled() {
        AgentMcpTools agent = agent(mock(TrialTaskRegistry.class), mock(AgentRunRepository.class),
                mock(RuleRepository.class),
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class));

        List<String> names = McpServerRunner.toolNames(runnerWithAgent(agent).specifications(jsonMapper));

        assertThat(names).hasSize(14)
                .containsAll(BASE_TOOLS)
                .contains("recon_draft_rule", "recon_trial_status", "recon_register_table", "recon_import_file",
                        "recon_submit_approval");
    }

    /** structuredContent 必须与声明了 outputSchema 的工具配套：有 schema 没内容，客户端会判定结果不合规。 */
    @Test
    void toolsThatDeclareAnOutputSchemaReturnStructuredContent() {
        when(tools.runWithView(anyString(), any(), any(), any())).thenReturn(
                new ReconMcpTools.RunOutcome("文本摘要", Map.of(
                        "ruleCode", "BILL_VS_SETTLE", "jobId", 42L, "status", "SUCCESS",
                        "diffRows", 6L, "diffByType", Map.of("AMOUNT_MISMATCH", 2L))));
        when(tools.jobSummaryWithView(anyLong())).thenReturn(
                new ReconMcpTools.RunOutcome("任务摘要", Map.of("jobId", 42L, "status", "SUCCESS")));
        when(tools.diagnose(anyLong())).thenReturn(new ReconMcpTools.DiagnosisView("诊断文本", Map.of(
                "jobId", 42L, "ruleCode", "BILL_VS_SETTLE", "status", "SUCCESS", "verdict", "HEALTHY",
                "summary", "没有发现异常信号", "facts", List.of(), "suggestions", List.of(),
                "samples", List.of())));

        List<McpServerFeatures.SyncToolSpecification> specs = runnerWithoutAgent().specifications(jsonMapper);

        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            boolean declaresOutput = spec.tool().outputSchema() != null;
            McpSchema.CallToolResult result = invokeOrNull(spec);
            if (declaresOutput && result != null) {
                assertThat(result.structuredContent())
                        .as("工具 %s 声明了 outputSchema，就必须给 structuredContent", spec.tool().name())
                        .isNotNull();
                assertThat(structured(result)).isNotEmpty();
                assertThat(result.isError()).isFalse();
            }
        }
    }

    @Test
    void runReturnsBothTextAndStructuredContent() {
        when(tools.runWithView(eq("BILL_VS_SETTLE"), any(), any(), any())).thenReturn(
                new ReconMcpTools.RunOutcome("规则 BILL_VS_SETTLE\n任务 #42", Map.of(
                        "ruleCode", "BILL_VS_SETTLE", "jobId", 42L, "status", "SUCCESS")));

        McpSchema.CallToolResult result = McpServerRunner.invoke(spec("recon_run"),
                Map.of("ruleCode", "BILL_VS_SETTLE"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).anySatisfy(content ->
                assertThat(content).isInstanceOf(McpSchema.TextContent.class));
        assertThat(structured(result))
                .containsEntry("jobId", 42L)
                .containsEntry("ruleCode", "BILL_VS_SETTLE");
    }

    /** 工具内部抛错 → isError=true 的文本，而不是协议层异常。 */
    @Test
    void failuresBecomeErrorTextNotProtocolErrors() {
        when(tools.jobSummaryWithView(999L)).thenThrow(new IllegalArgumentException("任务不存在：999"));

        McpSchema.CallToolResult result = McpServerRunner.invoke(spec("recon_job_summary"),
                Map.of("jobId", 999));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("任务不存在：999");
    }

    @Test
    void draftRuleReturnsARunIdImmediatelyAndQueuesTheWork() {
        TrialTaskRegistry registry = mock(TrialTaskRegistry.class);
        when(registry.submit(any(), any())).thenReturn(77L);
        AgentMcpTools agent = agent(registry, mock(AgentRunRepository.class), mock(RuleRepository.class),
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class));

        McpSchema.CallToolResult result = McpServerRunner.invoke(
                agentSpec(agent, "recon_draft_rule"),
                Map.of("businessDescription", "按订单号比对账单与结算金额", "ruleCode", "BILL_AGENT"));

        assertThat(result.isError()).isFalse();
        assertThat(structured(result)).containsEntry("runId", 77L);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("#77")
                .contains("用户明确表态")
                .as("工具描述里必须写明：通过试算 ≠ 生效").contains("审批");
        verify(registry).submit(any(), any());
    }

    /** 注册工具：preview 不改状态、把 confirm 的动作边界讲清楚；confirm 失败时 registered=false。 */
    @Test
    void registerTablePreviewShowsColumnsAndConfirmBoundary() {
        io.github.dolow.reconlens.agent.TableRegistrar registrar =
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class);
        when(registrar.preview("t_order")).thenReturn(new io.github.dolow.reconlens.agent.TableRegistrar.Preview(
                "t_order", List.of("order_no", "amount"), 1234L, null));
        when(registrar.preview("nope")).thenReturn(new io.github.dolow.reconlens.agent.TableRegistrar.Preview(
                "nope", List.of(), null, "数据源里查不到这张表的元数据（表不存在，或连接账号没有元数据权限）"));
        when(registrar.confirm(eq("nope"), any())).thenReturn(new io.github.dolow.reconlens.agent.TableRegistrar.Preview(
                "nope", List.of(), null, "数据源里查不到这张表的元数据（表不存在，或连接账号没有元数据权限）"));
        AgentMcpTools agent = agent(mock(TrialTaskRegistry.class), mock(AgentRunRepository.class),
                mock(RuleRepository.class), registrar, mock(io.github.dolow.reconlens.agent.FileImporter.class));

        McpSchema.CallToolResult preview = McpServerRunner.invoke(
                agentSpec(agent, "recon_register_table"), Map.of("table", "t_order"));
        assertThat(preview.isError()).isFalse();
        assertThat(structured(preview)).containsEntry("registered", false);
        assertThat(((McpSchema.TextContent) preview.content().get(0)).text())
                .contains("order_no")
                .contains("action=confirm")
                .contains("必须先经用户确认");

        McpSchema.CallToolResult failed = McpServerRunner.invoke(
                agentSpec(agent, "recon_register_table"), Map.of("table", "nope", "action", "confirm"));
        assertThat(structured(failed)).containsEntry("registered", false);
        verify(registrar).confirm(eq("nope"), any());
    }

    @Test
    void trialStatusTellsYouItIsStillRunning() {
        TrialTaskRegistry registry = mock(TrialTaskRegistry.class);
        when(registry.find(88L)).thenReturn(Optional.of(new TrialTaskRegistry.Task(
                88L, "起草规则", TrialTaskRegistry.Status.RUNNING, "BILL_AGENT", null, null,
                Instant.now(), null)));
        AgentMcpTools agent = agent(registry, mock(AgentRunRepository.class), mock(RuleRepository.class),
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class));

        McpSchema.CallToolResult result = McpServerRunner.invoke(
                agentSpec(agent, "recon_trial_status"), Map.of("runId", 88));

        Map<String, Object> structured = structured(result);
        assertThat(structured).containsEntry("inFlight", true).containsEntry("status", "RUNNING");
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("仍在后台执行");
    }

    /** 跑完之后：结论、逐项检查、降级理由、可批性，一个都不能少 —— 否则审核人只能盲签。 */
    @Test
    void trialStatusExposesTheEvidenceAndTheApprovalEntryPoint() {
        TrialTaskRegistry registry = mock(TrialTaskRegistry.class);
        when(registry.find(anyLong())).thenReturn(Optional.empty());
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.findRun(99L)).thenReturn(Optional.of(new AgentRunRepository.AgentRun(
                99L, "起草规则", "API", "SUCCESS", null, false, "BILL_AGENT", null, 1,
                1, 1200, null, "tester", Instant.now(), Instant.now())));
        RuleRepository rules = mock(RuleRepository.class);
        when(rules.findTrial("BILL_AGENT", 1)).thenReturn(Optional.of(new RuleRepository.TrialRecord(
                "PASS", "SAMPLE_PARTIAL", "SAMPLE_TOO_SMALL", null, "digest",
                "{\"checks\":{\"T1\":\"PASS\",\"T5-a\":\"FAIL: 每侧样本行数 100/100\"},"
                        + "\"notes\":[\"注入位置见 canary.detail\"]}")));
        when(rules.findByVersion(anyString(), eq(1))).thenReturn(Optional.empty());
        AgentMcpTools agent = agent(registry, runs, rules, mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class));

        McpSchema.CallToolResult result = McpServerRunner.invoke(
                agentSpec(agent, "recon_trial_status"), Map.of("runId", 99));

        Map<String, Object> structured = structured(result);
        assertThat(structured)
                .containsEntry("verdict", "PASS")
                .containsEntry("coverage", "SAMPLE_PARTIAL")
                .containsEntry("approvalMode", "NEED_SIGN")
                .containsEntry("inFlight", false);
        assertThat(checks(structured)).containsKey("T5-a");
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text)
                .contains("PASS × SAMPLE_PARTIAL")
                .contains("SAMPLE_TOO_SMALL")
                .contains("NEED_SIGN")
                .contains("T5-a")
                .contains("recon_submit_approval")
                .contains("用户明确同意");
    }

    /**
     * 按 ruleCode + version 查询（不带 runId，没有会话，也就没有会话消息）时同样要补齐 outputSchema
     * 声明的必填字段 —— 否则 MCP SDK 校验 structuredContent 时直接报 "未找到所需属性 message"，
     * 工具整条不可用。这条是按真实 E2E 调用踩出来的回归用例。
     */
    @Test
    void trialStatusByRuleCodeAndVersionCarriesTheRequiredMessageField() {
        TrialTaskRegistry registry = mock(TrialTaskRegistry.class);
        when(registry.find(anyLong())).thenReturn(Optional.empty());
        RuleRepository rules = mock(RuleRepository.class);
        when(rules.findTrial("BILL_AGENT", 1)).thenReturn(Optional.of(new RuleRepository.TrialRecord(
                "PASS", "FULL", null, null, "digest", "{\"checks\":{\"T1\":\"PASS\"}}")));
        when(rules.findByVersion(anyString(), eq(1))).thenReturn(Optional.empty());
        AgentMcpTools agent = agent(registry, mock(AgentRunRepository.class), rules,
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class));

        McpSchema.CallToolResult result = McpServerRunner.invoke(
                agentSpec(agent, "recon_trial_status"), Map.of("ruleCode", "BILL_AGENT", "version", 1));

        Map<String, Object> structured = structured(result);
        // outputSchema 的 required 是 ["status","inFlight","message"]，三个都不能少
        assertThat(structured)
                .containsKeys("status", "inFlight", "message")
                .containsEntry("verdict", "PASS")
                .containsEntry("coverage", "FULL");
        assertThat(String.valueOf(structured.get("message"))).isNotBlank();
    }

    /**
     * 审批代办的真实契约（不是"api-key 配过就放行"）：<b>必须带用户从 REST 领取的一次性确认码</b>，
     * 没有码就 fail-closed —— 这正是"曾经 47ms 就能自批一条规则"的补丁。
     */
    @Test
    void submitApprovalFailsClosedWithoutTheUserConfirmationCode() {
        RuleLifecycleService lifecycle = mock(RuleLifecycleService.class);
        RuleRepository rules = mock(RuleRepository.class);
        when(rules.findTrial("BILL_AGENT", 1)).thenReturn(Optional.of(new RuleRepository.TrialRecord(
                "PASS", "FULL", null, null, "digest", "{\"checks\":{}}")));
        AgentMcpTools agent = agent(mock(TrialTaskRegistry.class), mock(AgentRunRepository.class), rules,
                mock(io.github.dolow.reconlens.agent.TableRegistrar.class),
                mock(io.github.dolow.reconlens.agent.FileImporter.class), lifecycle, new ReconProperties());

        McpSchema.CallToolResult refused = McpServerRunner.invoke(
                agentSpec(agent, "recon_submit_approval"),
                Map.of("ruleCode", "BILL_AGENT", "version", 1, "decision", "APPROVED", "confirmedBy", "wang"));
        assertThat(refused.isError()).isTrue();
        assertThat(((McpSchema.TextContent) refused.content().get(0)).text())
                .contains("缺少必填参数 confirmationCode");
        verify(lifecycle, never()).confirmApproval(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                anyString(), anyString(), anyString(), any());

        McpSchema.CallToolResult ok = McpServerRunner.invoke(
                agentSpec(agent, "recon_submit_approval"),
                Map.of("ruleCode", "BILL_AGENT", "version", 1, "decision", "APPROVED",
                        "confirmedBy", "wang", "confirmationCode", "7K3M9Q2F"));
        assertThat(ok.isError()).isFalse();
        assertThat(structured(ok))
                .containsEntry("submitted", true)
                .containsEntry("confirmationConsumed", true)
                .containsEntry("lifecycle", "ACTIVE");
        verify(lifecycle).confirmApproval("BILL_AGENT", 1, "APPROVED", "wang", "7K3M9Q2F", null);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertThat(result.structuredContent()).isNotNull();
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checks(Map<String, Object> structured) {
        assertThat(structured.get("checks")).isInstanceOf(Map.class);
        return (Map<String, Object>) structured.get("checks");
    }

    private McpServerRunner runnerWithoutAgent() {
        return new McpServerRunner(tools, List.of());
    }

    /** 5 参便捷构造：审批依赖用默认值（api-key 未配置 = fail-closed）。 */
    private static AgentMcpTools agent(TrialTaskRegistry registry, AgentRunRepository runs, RuleRepository rules,
                                       io.github.dolow.reconlens.agent.TableRegistrar registrar,
                                       io.github.dolow.reconlens.agent.FileImporter importer) {
        return agent(registry, runs, rules, registrar, importer,
                mock(io.github.dolow.reconlens.rule.RuleLifecycleService.class),
                new io.github.dolow.reconlens.config.ReconProperties());
    }

    private static AgentMcpTools agent(TrialTaskRegistry registry, AgentRunRepository runs, RuleRepository rules,
                                       io.github.dolow.reconlens.agent.TableRegistrar registrar,
                                       io.github.dolow.reconlens.agent.FileImporter importer,
                                       io.github.dolow.reconlens.rule.RuleLifecycleService lifecycle,
                                       io.github.dolow.reconlens.config.ReconProperties properties) {
        return new AgentMcpTools(registry, runs, rules, registrar, importer, lifecycle, properties);
    }

    private McpServerRunner runnerWithAgent(AgentMcpTools agent) {
        return new McpServerRunner(tools, List.of(agent));
    }

    private McpServerFeatures.SyncToolSpecification spec(String name) {
        return runnerWithoutAgent().specifications(jsonMapper).stream()
                .filter(spec -> spec.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具不存在：" + name));
    }

    private McpServerFeatures.SyncToolSpecification agentSpec(AgentMcpTools agent, String name) {
        return runnerWithAgent(agent).specifications(jsonMapper).stream()
                .filter(spec -> spec.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具不存在：" + name));
    }

    /** 只调用参数齐全（或可空）的基础工具；缺必填参数的返回 null 由调用方跳过。 */
    private McpSchema.CallToolResult invokeOrNull(McpServerFeatures.SyncToolSpecification spec) {
        Map<String, Object> args = switch (spec.tool().name()) {
            case "recon_run" -> Map.of("ruleCode", "X");
            case "recon_job_summary" -> Map.of("jobId", 1);
            case "recon_list_diffs" -> Map.of("jobId", 1);
            case "recon_build_model" -> Map.of("ruleCode", "X");
            case "recon_define_rule" -> Map.of("ruleBody", "{}");
            case "recon_explain_diff" -> Map.of("diffId", 1);
            case "recon_explain_job" -> Map.of("jobId", 1);
            case "recon_list_rules" -> Map.of();
            case "recon_diagnose" -> Map.of("jobId", 1);
            default -> null;
        };
        return args == null ? null : McpServerRunner.invoke(spec, args);
    }
}
