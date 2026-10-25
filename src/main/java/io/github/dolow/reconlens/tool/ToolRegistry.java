package io.github.dolow.reconlens.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 三个入口（REST 控制台、内建会话、MCP 宿主）共用的<b>唯一</b>工具目录。
 *
 * <p>三档入口的分歧不在"能做什么"，只在"谁在编排"：
 * <ul>
 *   <li>控制台：人点按钮，参数是表单填的；</li>
 *   <li>内建会话：确定性路由把一个动作翻成一次调用；</li>
 *   <li>MCP 宿主：外部大模型自己组织调用。</li>
 * </ul>
 * 三条路若各自持有一份工具名单，就会出现「控制台能批、宿主不能批」这类只在某条路径上成立的规则。
 * 所以名字、描述、Schema、实现都只有这一份，闸门写在实现里，谁调都一样。
 *
 * <p>{@link #invoke} 不吞异常：调用方需要分得清"工具返回了一段解释"与"工具炸了"。
 * MCP 侧自己把异常翻成 {@code isError=true} 的文本，会话侧翻成一句人话。
 */
public interface ToolRegistry {

    List<ToolSpec> specs();

    Optional<ToolSpec> find(String name);

    default List<String> names() {
        return specs().stream().map(ToolSpec::name).toList();
    }

    default ToolOutcome invoke(String name, Map<String, Object> args) {
        ToolSpec spec = find(name).orElseThrow(
                () -> new IllegalArgumentException("没有这个工具：" + name + "。可用：" + names()));
        return spec.invoke(args);
    }
}
