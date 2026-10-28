package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具目录的只读出口：<b>控制台、会话、宿主到底能用哪些工具，以这一个接口为准</b>。
 *
 * <p>为什么要暴露：ADR-0001 D1 的承诺是"三档入口共用一份工具目录"。没有这个接口之前，
 * 「共用」只能靠读代码证明；有了它，人、脚本、别人写的客户端都能问一次"你现在有几个工具、参数长什么样"，
 * 装了新版本前后各调一次就知道能力变没变。
 *
 * <p>为什么不需要 {@code X-Operator}：这是接口元数据，和 OpenAPI 文档同级，不含任何业务数据。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry registry;

    public ToolController(ToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolSpec spec : registry.specs()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", spec.name());
            one.put("description", spec.description());
            one.put("inputSchema", spec.inputSchema());
            one.put("write", spec.write());
            if (spec.suggestion() != null) {
                one.put("suggestion", spec.suggestion());
            }
            items.add(one);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("items", items);
        return body;
    }
}
