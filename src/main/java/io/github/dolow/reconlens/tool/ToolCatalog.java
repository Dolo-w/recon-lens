package io.github.dolow.reconlens.tool;

import io.github.dolow.reconlens.mcp.AgentMcpTools;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>唯一的工具目录</b>：基础工具 + （Agent 开启时的）Agent 专属工具，一次合并，全项目只有这一份。
 *
 * <p>为什么要有它：2026-10-26 之前工具是"两处注册"——{@code ReconToolRegistry} 给
 * {@code /api/tools} 和会话路由，{@code mcp.AgentMcpTools} 给 MCP 宿主，于是同一个进程里
 * 两个数字同时成立：控制台说 12 个、宿主说 18 个。两边都"对"，只是没人知道哪个才算数。
 * 更糟的是这种漂移不报错：模型照着 18 个去调，控制台那边显示 12 个，用户只会觉得"这个功能坏了"。
 *
 * <p><b>{@code @Primary} 是这条约束的落点</b>：任何注入 {@code ToolRegistry} 的地方
 * （{@code /api/tools}、{@code ChatRuntime}、stdio 与 HTTP 两个 MCP server）拿到的都是这一份，
 * 不存在"某处忘了合并"的可能。名字重复直接在启动时炸掉，而不是让后注册的那个静默覆盖前一个。
 *
 * <p>Agent 工具用 {@code List<AgentMcpTools>} 注入：Agent 关闭时是空列表（不是 null），
 * 与 {@code McpServerRunner} 原来的做法一致，也省得测试去 mock 一个带 default 方法的 provider。
 */
@Component
@Primary
public class ToolCatalog implements ToolRegistry {

    private final List<ToolSpec> specs;

    public ToolCatalog(ReconToolRegistry base, List<AgentMcpTools> agents) {
        List<ToolSpec> merged = new ArrayList<>(base.specs());
        for (AgentMcpTools agent : agents == null ? List.<AgentMcpTools>of() : agents) {
            merged.addAll(agent.specs());
        }
        assertUnique(merged);
        this.specs = List.copyOf(merged);
    }

    /**
     * 重名即启动失败。
     *
     * <p>为什么不等第一次调用才发现：重名时"哪个实现生效"取决于合并顺序，
     * 而顺序取决于 Bean 注册顺序——那是个没人愿意在 3 个月后 debug 的隐式契约。
     */
    private static void assertUnique(List<ToolSpec> merged) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (ToolSpec spec : merged) {
            seen.merge(spec.name(), 1, Integer::sum);
        }
        List<String> duplicated = seen.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!duplicated.isEmpty()) {
            throw new IllegalStateException("工具名重复，合并两份目录时发现了同一个名字：" + duplicated
                    + "。同名的工具只能有一处实现——否则哪一份生效取决于注册顺序。");
        }
    }

    @Override
    public List<ToolSpec> specs() {
        return specs;
    }

    @Override
    public Optional<ToolSpec> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    /** 目录里的名字，顺序与 {@code tools/list} 一致。给运维与测试用。 */
    public List<String> names() {
        return specs.stream().map(ToolSpec::name).toList();
    }
}
