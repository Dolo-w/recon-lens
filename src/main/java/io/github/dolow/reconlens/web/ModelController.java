package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.model.ModelBuilder;
import io.github.dolow.reconlens.model.ModelException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预处理模型表的构建入口。构建是同步的（1000 万行的实测代价见 docs/benchmark.md §3.10），
 * 需要异步化时再引入后台任务，不要用 shell 去后台跑 curl 假装异步。
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelBuilder builder;
    private final JdbcTemplate jdbc;

    public ModelController(ModelBuilder builder, JdbcTemplate jdbc) {
        this.builder = builder;
        this.jdbc = jdbc;
    }

    @PostMapping("/{ruleCode}")
    public Map<String, Object> build(@PathVariable String ruleCode,
                                     @RequestParam(required = false) Integer shards,
                                     @RequestParam(required = false) String operator) {
        ModelBuilder.ModelBuild build = builder.build(ruleCode, shards, operator);
        return Map.of(
                "ruleCode", build.ruleCode(),
                "ruleVersion", build.ruleVersion(),
                "modelRuleCode", build.modelRuleCode(),
                "modelRuleVersion", build.modelRuleVersion(),
                "shards", build.shards(),
                "tables", build.tables().stream().map(ModelController::table).toList(),
                "hint", "现在可以用规则 " + build.modelRuleCode() + " 发起对账，结果与原规则一致；"
                        + "源数据变化后需要重建模型表才会更新。");
    }

    /** 已建好的模型表，按构建时间倒序（最多 100 条，包含历史构建记录）。 */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT rule_code, rule_version, model_rule_code, side,"
                + " model_table, dimension, aggregated, shards, row_count, build_ms, created_by, created_at"
                + " FROM recon_model ORDER BY id DESC LIMIT 100");
        List<Map<String, Object>> models = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleCode", row.get("rule_code"));
            item.put("ruleVersion", row.get("rule_version"));
            item.put("modelRuleCode", row.get("model_rule_code"));
            item.put("side", row.get("side"));
            item.put("table", row.get("model_table"));
            item.put("dimension", row.get("dimension"));
            item.put("aggregated", row.get("aggregated"));
            item.put("shards", row.get("shards"));
            item.put("rows", row.get("row_count"));
            item.put("buildMillis", row.get("build_ms"));
            item.put("createdBy", row.get("created_by"));
            item.put("createdAt", row.get("created_at"));
            return item;
        }).toList();
        return Map.of("count", models.size(), "models", models);
    }

    private static Map<String, Object> table(ModelBuilder.ModelBuild.Table table) {
        return Map.of(
                "side", table.side(),
                "table", table.table(),
                "rows", table.rows(),
                "buildMillis", table.buildMillis());
    }

    @ExceptionHandler(ModelException.class)
    public ResponseEntity<Map<String, Object>> onModelError(ModelException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "model_build_failed", "message", e.getMessage()));
    }

    /** 规则不存在 / 规则体不合法（catalog.resolve 抛的就是这个）→ 400，和发起任务时的口径一致。 */
    @ExceptionHandler({RuleException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> onBadRule(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "model_build_failed", "message", e.getMessage()));
    }
}
