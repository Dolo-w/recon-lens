package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * P3：把 MCP 从「只能 stdio」扩展到「同时能走 HTTP」（规范的 streamable HTTP 传输）。
 *
 * <p><b>为什么值得做</b>：stdio 要求宿主在自己进程里拉起一个 JVM。本机客户端没问题，
 * 但远程宿主要么得能跑 java、要么得开 SSH 隧道。HTTP 通道让「对账服务在机房、Agent 在别处」
 * 变成一条 URL 的事，而且一个进程能服务多个客户端（stdio 是一对一的，一个 stdin 挂一个宿主）。
 *
 * <p><b>为什么默认关闭</b>：这是<b>多了一个监听入口</b>。默认开启等于给每套现有部署悄悄加了
 * 一个不需要认证的 MCP 端点，而工具里包含 {@code recon_run}（能跑对账、读差异）。
 * 所以和 stdio 一样必须显式打开：{@code --recon.mcp.http.enabled=true}。
 *
 * <p><b>和 stdio 共用一份工具表</b>：都从 {@link McpSpecifications} 生成。各装配一遍迟早出现
 * 「stdio 有 15 个、HTTP 只有 14 个」，而客户端只会说"那个工具不存在"，极难排查。
 *
 * <p><b>不假装有鉴权</b>：MCP 的 HTTP 传输自身不带认证语义，本项目也还没有认证体系（backlog B12）。
 * 真要暴露到公网，前置网关做 mTLS / Basic Auth 是部署方的事；写在这里反而会让人以为它安全。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "recon.mcp.http", name = "enabled", havingValue = "true")
public class McpHttpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpHttpConfiguration.class);

    /**
     * 传输与 servlet 必须是<b>同一个实例</b>：server 绑定的是这个对象的会话表，
     * 而 HTTP 请求也是打到它身上。造两个实例的结果是「POST 有人接、但没有任何工具」——
     * 客户端看到的是 tools/list 返回空数组，而不是报错，最难查。
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpHttpTransport() {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                // 不响应 DELETE：本服务不维护长会话状态，客户端随时可以重连
                .disallowDelete(true)
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpHttpServer(HttpServletStreamableServerTransportProvider transport,
                                       ToolRegistry registry,
                                       List<AgentMcpTools> agentTools) {
        List<McpServerFeatures.SyncToolSpecification> specs =
                McpSpecifications.build(new JacksonMcpJsonMapper(new ObjectMapper()), registry, agentTools);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(McpSpecifications.SERVER_NAME, McpSpecifications.SERVER_VERSION)
                .instructions(McpSpecifications.instructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(specs)
                .build();
        log.info("{} MCP server 已就绪（streamable HTTP）：{} 个工具，端点 /mcp",
                McpSpecifications.SERVER_NAME, specs.size());
        return server;
    }

    /**
     * 挂到 servlet 容器。<b>不能用 {@code @RequestMapping}</b>：MCP 要自己控制
     * {@code GET}（开 SSE 流）与 {@code POST}（JSON-RPC）的响应形态，走 Spring MVC 的返回值
     * 处理会被再包一层，客户端收到的事件流就不对了。
     */
    @Bean
    public ServletRegistrationBean<HttpServlet> mcpHttpServlet(HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServlet> bean = new ServletRegistrationBean<>(transport, "/mcp");
        bean.setName("mcpStreamableHttp");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
