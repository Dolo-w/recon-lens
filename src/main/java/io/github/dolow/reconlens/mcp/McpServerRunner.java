package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.engine.Errors;
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
import java.util.function.Function;

/**
 * 把 recon-lens 挂成一个 MCP server（stdio 通道），让 Agent 能直接调对账能力。
 *
 * <p>只在 {@code --spring.profiles.active=mcp}（即 {@code recon.mcp.enabled=true}）时启动，
 * {@code ApplicationRunner} 在主线程阻塞，进程活到客户端关闭 stdin 为止 —— REST 模式完全不受影响。
 *
 * <p>stdio 通道就是 stdout，所以这里有一条硬约束：<b>任何非 JSON-RPC 输出都会污染协议</b>。
 * 项目里没有 System.out 调用，剩下的就是 Spring 启动横幅与日志，分别由
 * {@code application-mcp.yml}（banner-mode=off）和 {@code logback-spring.xml}（日志走 stderr）解决。
 */
@Component
@ConditionalOnProperty(prefix = "recon.mcp", name = "enabled", havingValue = "true")
public class McpServerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);
    private static final String SERVER_NAME = "recon-lens";
    private static final String SERVER_VERSION = "0.1.0";

    private final ReconMcpTools tools;

    public McpServerRunner(ReconMcpTools tools) {
        this.tools = tools;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        CountDownLatch stdinClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(jsonMapper, eofAware(System.in, stdinClosed), System.out);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("配置化对账引擎：先 recon_list_rules 看有哪些规则，再 recon_run 跑对账（返回任务号），"
                        + "然后用 recon_list_diffs / recon_explain_job 看差异与归因。"
                        + "要换对账维度或比对字段就调 recon_define_rule 提交新版本（立即生效，不需要改代码或重启）；"
                        + "只想临时试算可以用 recon_run 的 ruleJson 内联一份规则。")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specifications(jsonMapper))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (Exception e) {
                log.debug("关闭 MCP server 时忽略异常：{}", e.toString());
            }
        }, "mcp-shutdown"));

        log.info("{} MCP server 已就绪（stdio）：{}", SERVER_NAME,
                server.listTools().stream().map(McpSchema.Tool::name).toList());
        stdinClosed.await();
        log.info("stdin 已关闭，MCP server 退出");
        server.closeGracefully();
    }

    private List<McpServerFeatures.SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        specs.add(tool(jsonMapper, "recon_list_rules",
                "列出规则库里的对账规则（版本、两侧数据源、对账维度、比对字段、分片与重试配置）。",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                a -> tools.listRules()));

        specs.add(tool(jsonMapper, "recon_define_rule",
                "定义或修改一条对账规则：提交规则体（JSON 或 YAML，结构与规则文件一致），立刻落为新版本并对后续任务生效，不需要改代码或重启。",
                """
                {"type":"object","properties":{
                  "ruleBody":{"type":"string","description":"规则体：JSON 或 YAML 文本，字段见 recon_list_rules 的输出（code/left/right/compare/missing/shard/options）"},
                  "operator":{"type":"string","description":"可选：操作人标识，记入规则版本历史"}},
                 "required":["ruleBody"],"additionalProperties":false}
                """,
                a -> tools.defineRule(text(a, "ruleBody", true), text(a, "operator", false))));

        specs.add(tool(jsonMapper, "recon_run",
                "按规则跑一次对账（同步等待完成），返回任务摘要与差异分类；任务号可用于后续查明细和归因。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码，取自 recon_list_rules（与 ruleJson 二选一）"},
                  "ruleJson":{"type":"string","description":"可选：内联一份规则体（JSON 或 YAML）直接跑，不落规则库，适合临时换维度试算"},
                  "batchId":{"type":"string","description":"可选：业务批次号，便于检索与重跑对账"},
                  "remark":{"type":"string","description":"可选：备注"}},
                 "additionalProperties":false}
                """,
                a -> tools.runReconciliation(text(a, "ruleCode", false), text(a, "ruleJson", false),
                        text(a, "batchId", false), text(a, "remark", false))));

        specs.add(tool(jsonMapper, "recon_job_summary",
                "查看某个对账任务的状态、扫描量、差异分类与分片明细。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"recon_run 返回的任务号"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                a -> tools.jobSummary(integer(a, "jobId", 0, true))));

        specs.add(tool(jsonMapper, "recon_list_diffs",
                "分页查看某个任务的差异明细（业务键、字段、左右值、相对偏差、已有归因）。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "diffType":{"type":"string","description":"可选：只看某一类差异，如 AMOUNT_MISMATCH / MISSING_RIGHT / DUPLICATE_KEY"},
                  "offset":{"type":"integer","description":"可选：跳过多少条，默认 0"},
                  "limit":{"type":"integer","description":"可选：返回条数，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                a -> tools.listDiffs(integer(a, "jobId", 0, true), text(a, "diffType", false),
                        (int) integer(a, "offset", 0, false), (int) integer(a, "limit", 50, false))));

        specs.add(tool(jsonMapper, "recon_explain_diff",
                "给单条差异生成归因说明（LLM 可用时用 LLM，不可用时自动降级为规则模板）。",
                """
                {"type":"object","properties":{
                  "diffId":{"type":"integer","description":"差异编号，来自 recon_list_diffs"}},
                 "required":["diffId"],"additionalProperties":false}
                """,
                a -> tools.explainDiff(integer(a, "diffId", 0, true))));

        specs.add(tool(jsonMapper, "recon_explain_job",
                "给整个任务的差异批量补归因，并回显前几条结果。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "limit":{"type":"integer","description":"可选：最多处理多少条，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                a -> tools.explainJob(integer(a, "jobId", 0, true), (int) integer(a, "limit", 50, false))));

        return specs;
    }

    private McpServerFeatures.SyncToolSpecification tool(McpJsonMapper jsonMapper, String name, String description,
                                                         String jsonSchema, Function<Map<String, Object>, String> body) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(jsonMapper, jsonSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(body.apply(args))
                                .isError(false)
                                .build();
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

    private static String text(Map<String, Object> args, String key, boolean required) {
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

    private static long integer(Map<String, Object> args, String key, long fallback, boolean required) {
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
}
