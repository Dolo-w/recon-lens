package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleRegistry;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 规则接口：让调用方（和演示页面）知道当前实例能跑哪些对账。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleRegistry rules;

    public RuleController(RuleRegistry rules) {
        this.rules = rules;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return rules.all().stream().map(RuleController::describe).toList();
    }

    private static Map<String, Object> describe(RuleSpec rule) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", rule.code());
        view.put("name", rule.name());
        view.put("description", rule.description());
        view.put("left", rule.left().describe());
        view.put("right", rule.right().describe());
        view.put("keyField", rule.left().keyField());
        view.put("shardCount", rule.shard().count());
        view.put("parallelism", rule.shard().effectiveParallelism());
        view.put("maxAttempts", rule.maxAttempts());
        view.put("compare", rule.compare().stream().map(RuleController::fieldView).toList());
        view.put("missing", Map.of(
                "leftMissingDiffType", rule.missing().leftMissingDiffType(),
                "rightMissingDiffType", rule.missing().rightMissingDiffType(),
                "severity", rule.missing().severity()));
        view.put("duplicate", Map.of(
                "diffType", rule.duplicate().diffType(),
                "severity", rule.duplicate().severity()));
        return view;
    }

    private static Map<String, Object> fieldView(FieldRule field) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("field", field.field());
        view.put("type", field.type());
        view.put("tolerance", field.tolerance());
        view.put("tolerancePercent", field.tolerancePercent());
        view.put("ignoreCase", field.ignoreCase());
        view.put("diffType", field.diffType());
        view.put("severity", field.severity());
        return view;
    }
}
