package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
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
 * <p><b>工具数是条件性的</b>：12 个基础工具永远在；{@code recon.agent.enabled=true} 时再加 6 个
 * （{@code recon_draft_rule} / {@code recon_trial_status} / {@code recon_register_table} /
 * {@code recon_import_file} / {@code recon_import_status} / {@code recon_submit_approval}）→ <b>18</b>。
 * 合并发生在 {@code tool.ToolCatalog}（{@code @Primary} 的唯一目录），这里只负责把它翻译成
 * {@code tools/list} —— <b>不再自己拼一份</b>，否则控制台与宿主又会各说各的数量。
 */
@Component
@ConditionalOnProperty(prefix = "recon.mcp", name = "enabled", havingValue = "true")
public class McpServerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

    private final ReconMcpTools tools;
    private final ToolRegistry registry;

    /**
     * @param registry 唯一的工具目录（{@code tool.ToolCatalog}）：基础工具与 Agent 工具已经合并好，
     *                 这里<b>不再</b>额外注入 {@code AgentMcpTools} —— 那样就等于又开了一条注册路径。
     */
    public McpServerRunner(ReconMcpTools tools, ToolRegistry registry) {
        this.tools = tools;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        CountDownLatch stdinClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(jsonMapper, eofAware(System.in, stdinClosed), System.out);

        List<McpServerFeatures.SyncToolSpecification> specs = specifications(jsonMapper);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(McpSpecifications.SERVER_NAME, McpSpecifications.SERVER_VERSION)
                .instructions(McpSpecifications.instructions())
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

        log.info("{} MCP server 已就绪（stdio）：{} 个工具 {}", McpSpecifications.SERVER_NAME, specs.size(),
                server.listTools().stream().map(McpSchema.Tool::name).toList());
        startStdoutWatchdog();
        stdinClosed.await();
        log.info("stdin 已关闭，MCP server 退出");
        server.closeGracefully();
        // 必须显式退出：stdio 模式下这个进程是宿主的一个"工具"，宿主关掉 stdin 等于卸载工具，
        // 它没有理由继续活着。但进程里还有非守护线程——导入线程池是**故意**非守护的
        // （web 模式下导入不能被中断），Spring 的 @Scheduled 默认也是非守护。于是 main 返回后
        // JVM 不会自己退，实测变成"宿主已退出、这边还在跑"的僵尸（2026-10-24 MCP 宿主冒烟实测）。
        // System.exit 仍会触发 Spring 的 shutdown hook，Hikari 等资源照常关闭。
        System.exit(0);
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

    /** 目录里有什么就给什么：顺序即 {@code tools/list} 的顺序。 */
    List<McpServerFeatures.SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        return McpSpecifications.build(jsonMapper, registry);
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
