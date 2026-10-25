package io.github.dolow.reconlens.tool;

import io.github.dolow.reconlens.mcp.ReconMcpTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具目录本身的测试：<b>名字唯一、Schema 合法、未知工具报得清楚、必填参数缺失报同一句话</b>。
 *
 * <p>为什么这些值得单测：目录是三个入口共用的，它在构造期出问题会让「控制台能点、会话说没有这个工具、
 * 宿主那边 tools/list 又少一个」同时发生，而这类错在单条链路的测试里都看不见。
 */
class ToolRegistryTest {

    private ReconToolRegistry registry;

    @BeforeEach
    void setUp() {
        // 这份测试只关心"目录本身"：名字、Schema、参数校验文案。
        // 真实工具实现在 ReconMcpTools 里，它的行为已经由 mcp/chat 的测试覆盖，
        // 这里用 mock 把两件事拆开——目录错了和工具错了，报出来的病不一样。
        registry = new ReconToolRegistry(org.mockito.Mockito.mock(ReconMcpTools.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                org.mockito.Mockito.mock(io.github.dolow.reconlens.rule.RuleCatalog.class),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                new io.github.dolow.reconlens.store.TableInspector(
                        new org.springframework.jdbc.core.JdbcTemplate(
                                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                        "jdbc:h2:mem:tool_registry")),
                        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class)),
                org.mockito.Mockito.mock(io.github.dolow.reconlens.store.ReconRepository.class));
    }

    @Test
    void 目录里的名字不重复_而且顺序与MCP的tools_list一致() {
        List<String> names = registry.names();
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).containsExactlyElementsOf(ToolNames.BASE);
    }

    @Test
    void 每个工具都有描述与合法的inputSchema() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ToolSpec spec : registry.specs()) {
            assertThat(spec.description()).as(spec.name()).isNotBlank();
            var schema = mapper.readTree(spec.inputSchema());
            assertThat(schema.path("type").asText()).as(spec.name()).isEqualTo("object");
            assertThat(schema.path("properties").isObject()).as(spec.name()).isTrue();
        }
    }

    @Test
    void 声明了outputSchema的工具_必须真的给出结构化结果() {
        for (ToolSpec spec : registry.specs()) {
            if (spec.outputSchema() != null) {
                assertThat(spec.outputSchema()).as(spec.name()).contains("\"type\":\"object\"");
            }
        }
    }

    @Test
    void 未知工具报得清楚_并把可用名单带上() {
        assertThatThrownBy(() -> registry.invoke("recon_run_typo", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有这个工具：recon_run_typo")
                .hasMessageContaining(ToolNames.RUN);
    }

    @Test
    void 必填参数缺失_报错文案与MCP那套逐字一致() {
        assertThatThrownBy(() -> registry.invoke(ToolNames.JOB_SUMMARY, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("缺少必填参数 jobId");

        assertThatThrownBy(() -> registry.invoke(ToolNames.JOB_SUMMARY, Map.of("jobId", "1O0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数 jobId 必须是整数，收到：1O0");
    }

    @Test
    void Agent工具名与基础工具名不重叠_合起来正好是宿主看到的18个() {
        // 这两处注册（目录 12 + Agent 6）本来就是漂移风险的现场：
        // 名字在这里对不上，宿主 tools/list 与 /api/tools 就会给出不一样的世界
        assertThat(ToolNames.BASE).doesNotContainAnyElementsOf(ToolNames.AGENT);
        assertThat(ToolNames.AGENT).hasSize(6);
        assertThat(ToolNames.BASE).hasSize(12);
        assertThat(ToolNames.BASE.size() + ToolNames.AGENT.size()).isEqualTo(18);
        assertThat(ToolNames.BASE).doesNotContain(ToolNames.RECALL);
        assertThat(ToolNames.AGENT).doesNotContain(ToolNames.RECALL);
        assertThat(registry.names()).doesNotContainAnyElementsOf(ToolNames.AGENT);
    }

    @Test
    void find_能按名字取到_取不到就是空() {
        assertThat(registry.find(ToolNames.LIST_DIFFS)).isPresent();
        assertThat(registry.find("nope")).isEmpty();
    }
}
