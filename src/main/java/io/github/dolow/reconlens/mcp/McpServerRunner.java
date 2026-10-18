package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 把 recon-lens 挂成一个 MCP server（stdio 通道），让 Agent 能直接调对账能力。
 *
 * <p>只在 {@code --spring.profiles.active=mcp}（即 {@code recon.mcp.enabled=true}）时启动，
 * {@code ApplicationRunner} 在主线程阻塞，进程活到客户端关闭 stdin 为止 —— REST 模式完全不受影响。
 *
 * <p>stdio 通道就是 stdout，所以这里有一条硬约束：<b>任何非 JSON-RPC 输出都会污染协议</b>。
 * 项目里没有 System.out 调用，剩下的就是 Spring 启动横幅与日志，分别由
 * {@code application-mcp.yml}（banner-mode=off）和 {@code logback-spring.xml}（日志走 stderr）解决。
 *
 * <p><b>工具数是条件性的</b>：8 个基础工具永远在；{@code recon_diagnose} 不挂开关
 * （它读的是已有任务数据，与"有没有 Agent"无关），所以 Agent 关闭时是 <b>9</b> 个；
 * {@code recon.agent.enabled=true} 时再加 5 个（{@code recon_draft_rule} / {@code recon_trial_status} /
 * {@code recon_register_table} / {@code recon_import_file} / {@code recon_submit_approval}）→ <b>14</b>。
 * 依赖用 {@code List<AgentMcpTools>} 注入：Agent 关闭时是空列表，而不是一个会 NPE 的 null。
 */
@Component
@ConditionalOnProperty(prefix = "recon.mcp", name = "enabled", havingValue = "true")
public class McpServerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);
    private static final String SERVER_NAME = "recon-lens";
    private static final String SERVER_VERSION = "0.1.0";

    private final ReconMcpTools tools;
    private final List<AgentMcpTools> agentTools;

    /**
     * @param agentTools 0 或 1 个。用 {@code List} 而不是 {@code ObjectProvider}：
     *                   Spring 对空集合的注入是天然安全的，而测试里也不必去 mock 一个
     *                   带 default 方法的 provider（那样拿到的是 null 而不是空流）
     */
    public McpServerRunner(ReconMcpTools tools, List<AgentMcpTools> agentTools) {
        this.tools = tools;
        this.agentTools = agentTools == null ? List.of() : List.copyOf(agentTools);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        CountDownLatch stdinClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(jsonMapper, eofAware(System.in, stdinClosed), System.out);

        List<McpServerFeatures.SyncToolSpecification> specs = specifications(jsonMapper);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("配置化对账引擎：先 recon_list_rules 看有哪些规则，再 recon_run 跑对账（返回任务号），"
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
                        + "（decision=APPROVED/REJECTED，confirmedBy 填用户标识），未审批的规则 recon_run 用不了。")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (Exception e) {
                log.debug("关闭 MCP server 时忽略异常：{}", e.toString());
            }
        }, "mcp-shutdown"));

        log.info("{} MCP server 已就绪（stdio）：{} 个工具 {}", SERVER_NAME, specs.size(),
                server.listTools().stream().map(McpSchema.Tool::name).toList());
        startStdoutWatchdog();
        stdinClosed.await();
        log.info("stdin 已关闭，MCP server 退出");
        server.closeGracefully();
    }

    /**
     * stdout 失效看门狗：宿主侧管道断裂（宿主崩溃/重启）后，写往 stdout 的 JSON-RPC
     * 响应会静默失败 —— {@link System#out} 是 PrintStream，它把 IOException 吞掉只置
     * error 标志，不抛异常，进程就成了"活着但回包无人读"的僵尸（2026-10-18 事故里
     * 注册确认调用的响应正是这样丢的）。每 5 秒探一次 {@code checkError()}，发现失效
     * 就记日志并退出，让宿主的拉起逻辑接手，绝不带病滞留。
     */
    private static void startStdoutWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.out.checkError()) {
                    log.error("检测到 stdout 已失效（宿主侧管道断裂），响应无法送达，进程退出以免成为僵尸");
                    System.exit(1);
                }
            }
        }, "mcp-stdout-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 基础工具 + （Agent 开启时）Agent 工具。顺序即 {@code tools/list} 的顺序。 */
    List<McpServerFeatures.SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>(baseSpecifications(jsonMapper));
        agentTools.forEach(agent -> specs.addAll(agent.specifications(jsonMapper)));
        return specs;
    }

    private List<McpServerFeatures.SyncToolSpecification> baseSpecifications(McpJsonMapper jsonMapper) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        specs.add(McpToolFactory.tool(jsonMapper, "recon_list_rules",
                "列出规则库里的对账规则（版本、两侧数据源、对账维度、比对字段、分片与重试配置）。",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                a -> McpToolFactory.ToolResult.text(tools.listRules())));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_define_rule",
                "定义或修改一条对账规则：提交规则体（JSON 或 YAML，结构与规则文件一致），落为草稿并自动跑影子试算。"
                        + "草稿不生效 —— 必须把试算结论讲给用户，用户明确同意后调 recon_submit_approval 审批，通过后才能 recon_run。",
                """
                {"type":"object","properties":{
                  "ruleBody":{"type":"string","description":"规则体：JSON 或 YAML 文本，字段见 recon_list_rules 的输出（code/left/right/compare/missing/shard/options）"},
                  "operator":{"type":"string","description":"可选：操作人标识，记入规则版本历史"}},
                 "required":["ruleBody"],"additionalProperties":false}
                """,
                a -> McpToolFactory.ToolResult.text(
                        tools.defineRule(McpToolFactory.text(a, "ruleBody", true),
                                McpToolFactory.text(a, "operator", false)))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_build_model",
                "预处理物化：按规则声明的粒度和过滤条件把两侧数据提前汇总/窄化成模型表（带 biz_key 索引，不建主键 —— 重复业务键要留给引擎报 DUPLICATE_KEY），并注册一条读模型表的模型规则（规则 code 加后缀 __MODEL）。"
                        + "适合源数据变化慢、对账跑得频繁的场景（1000 万行实测每轮 21.5s → 之后 7.8~15.3ms，见 docs/benchmark.md §3.10）；"
                        + "代价是模型表与源表可能不同步，源数据变了要重建。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码，取自 recon_list_rules"},
                  "shards":{"type":"integer","description":"可选：模型表分片数（1~1024），默认沿用规则的分片数。两侧用同一个桶函数，保证同一个业务键落在同一片"},
                  "operator":{"type":"string","description":"可选：操作人标识，记入模型表元信息"}},
                 "required":["ruleCode"],"additionalProperties":false}
                """,
                a -> {
                    int shards = (int) McpToolFactory.integer(a, "shards", 0, false);
                    return McpToolFactory.ToolResult.text(tools.buildModel(
                            McpToolFactory.text(a, "ruleCode", true), shards <= 0 ? null : shards,
                            McpToolFactory.text(a, "operator", false)));
                }));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_run",
                "按规则跑一次对账（同步等待完成），返回任务摘要与差异分类；任务号可用于后续查明细和归因。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码，取自 recon_list_rules（与 ruleJson 二选一）"},
                  "ruleJson":{"type":"string","description":"可选：内联一份规则体（JSON 或 YAML）直接跑，不落规则库，适合临时换维度试算"},
                  "batchId":{"type":"string","description":"可选：业务批次号，便于检索与重跑对账"},
                  "remark":{"type":"string","description":"可选：备注"}},
                 "additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string"},
                  "ruleVersion":{"type":"integer","description":"内联规则时没有版本"},
                  "jobId":{"type":"integer"},
                  "batchId":{"type":"string"},
                  "status":{"type":"string"},
                  "leftRows":{"type":"integer"},
                  "rightRows":{"type":"integer"},
                  "matchedRows":{"type":"integer"},
                  "diffRows":{"type":"integer"},
                  "diffByType":{"type":"object","additionalProperties":{"type":"integer"}},
                  "diffBySeverity":{"type":"object","additionalProperties":{"type":"integer"}},
                  "errorMsg":{"type":"string"}},
                 "required":["ruleCode","jobId","status"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.RunOutcome outcome = tools.runWithView(
                            McpToolFactory.text(a, "ruleCode", false),
                            McpToolFactory.text(a, "ruleJson", false),
                            McpToolFactory.text(a, "batchId", false),
                            McpToolFactory.text(a, "remark", false));
                    return McpToolFactory.ToolResult.of(outcome.text(), outcome.view());
                }));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_job_summary",
                "查看某个对账任务的状态、扫描量、差异分类与分片明细。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"recon_run 返回的任务号"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer"},
                  "ruleCode":{"type":"string"},
                  "batchId":{"type":"string"},
                  "status":{"type":"string"},
                  "shardCount":{"type":"integer"},
                  "shardDone":{"type":"integer"},
                  "leftRows":{"type":"integer"},
                  "rightRows":{"type":"integer"},
                  "matchedRows":{"type":"integer"},
                  "diffRows":{"type":"integer"},
                  "diffByType":{"type":"object","additionalProperties":{"type":"integer"}},
                  "diffBySeverity":{"type":"object","additionalProperties":{"type":"integer"}},
                  "failedShards":{"type":"array","items":{"type":"integer"}},
                  "durationMs":{"type":"integer"},
                  "errorMsg":{"type":"string"}},
                 "required":["jobId","status"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.RunOutcome outcome =
                            tools.jobSummaryWithView(McpToolFactory.integer(a, "jobId", 0, true));
                    return McpToolFactory.ToolResult.of(outcome.text(), outcome.view());
                }));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_list_diffs",
                "分页查看某个任务的差异明细（业务键、字段、左右值、相对偏差、已有归因）。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "diffType":{"type":"string","description":"可选：只看某一类差异，如 AMOUNT_MISMATCH / MISSING_RIGHT / DUPLICATE_KEY"},
                  "offset":{"type":"integer","description":"可选：跳过多少条，默认 0"},
                  "limit":{"type":"integer","description":"可选：返回条数，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                a -> McpToolFactory.ToolResult.text(tools.listDiffs(
                        McpToolFactory.integer(a, "jobId", 0, true),
                        McpToolFactory.text(a, "diffType", false),
                        (int) McpToolFactory.integer(a, "offset", 0, false),
                        (int) McpToolFactory.integer(a, "limit", 50, false)))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_explain_diff",
                "给单条差异生成归因说明（LLM 可用时用 LLM，不可用时自动降级为规则模板）。",
                """
                {"type":"object","properties":{
                  "diffId":{"type":"integer","description":"差异编号，来自 recon_list_diffs"}},
                 "required":["diffId"],"additionalProperties":false}
                """,
                a -> McpToolFactory.ToolResult.text(
                        tools.explainDiff(McpToolFactory.integer(a, "diffId", 0, true)))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_explain_job",
                "给整个任务的差异批量补归因，并回显前几条结果。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "limit":{"type":"integer","description":"可选：最多处理多少条，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                a -> McpToolFactory.ToolResult.text(tools.explainJob(
                        McpToolFactory.integer(a, "jobId", 0, true),
                        (int) McpToolFactory.integer(a, "limit", 50, false)))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_diagnose",
                "任务级诊断：一次返回「哪里不对 + 疑似原因 + 建议动作」。比让模型自己串 recon_job_summary → "
                        + "recon_list_diffs → recon_explain_job 四次调用更省、更稳定 —— 判断留在服务端，结论可复现。"
                        + "归因抽样走知识库/模板，不消耗大模型。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"recon_run 返回的任务号"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer"},
                  "ruleCode":{"type":"string"},
                  "status":{"type":"string"},
                  "verdict":{"type":"string","description":"HEALTHY / ATTENTION / BROKEN"},
                  "summary":{"type":"string"},
                  "facts":{"type":"array","items":{"type":"object","properties":{
                      "kind":{"type":"string"},"detail":{"type":"string"}},
                      "required":["kind","detail"],"additionalProperties":false}},
                  "suggestions":{"type":"array","items":{"type":"object","properties":{
                      "action":{"type":"string"},"reason":{"type":"string"}},
                      "required":["action","reason"],"additionalProperties":false}},
                  "samples":{"type":"array","items":{"type":"object","properties":{
                      "diffId":{"type":"integer"},"diffType":{"type":"string"},"severity":{"type":"string"},
                      "bizKey":{"type":"string"},"explanation":{"type":"string"}},
                      "required":["diffId","diffType","severity","bizKey","explanation"],
                      "additionalProperties":false}}},
                 "required":["jobId","status","verdict","summary"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.DiagnosisView view =
                            tools.diagnose(McpToolFactory.integer(a, "jobId", 0, true));
                    return McpToolFactory.ToolResult.of(view.text(), view.view());
                }));

        return specs;
    }

    /** 包一层 System.in，读到 EOF 就放行主线程退出；不额外消费任何字节。 */
    private static InputStream eofAware(InputStream source, CountDownLatch stdinClosed) {
        return new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value == -1) {
                    stdinClosed.countDown();
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read == -1) {
                    stdinClosed.countDown();
                }
                return read;
            }
        };
    }

    /** 供测试断言顺序与数量：不启动 server 也能看到 {@code tools/list} 会给出什么。 */
    static List<String> toolNames(List<McpServerFeatures.SyncToolSpecification> specs) {
        return specs.stream().map(spec -> spec.tool().name()).toList();
    }

    /** 测试用：把参数 Map 直接喂给某个工具的 handler。 */
    static McpSchema.CallToolResult invoke(McpServerFeatures.SyncToolSpecification spec,
                                           Map<String, Object> args) {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(spec.tool().name(), args);
        return spec.callHandler().apply(null, request);
    }
}
