package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.store.TableInspector;
import io.github.dolow.reconlens.tool.TableAllowList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 看一张表的结构（列、类型、可空、主键、行数）。
 *
 * <p>和 MCP 的 {@code recon_describe_table}、会话里的「看字段」是同一份实现，
 * 只是出口不同 —— 这个项目的约束是"同一能力只有一个实现"，否则三个入口迟早讲三种话。
 *
 * <p><b>不在白名单里的表返回 404</b>，且响应体不区分"不存在"与"不允许看"。
 * 区分了就等于给了一个探测表名的接口：攻击者能靠 403/404 的差别把库里的表名枚举出来。
 */
@RestController
@RequestMapping("/api/tables")
public class TableController {

    private final ObjectProvider<TableAllowList> allowList;
    private final TableInspector inspector;

    public TableController(ObjectProvider<TableAllowList> allowList, TableInspector inspector) {
        this.allowList = allowList;
        this.inspector = inspector;
    }

    /**
     * 采样段。{@code estimated=true} 是给调用方看的：这些比例是窗口上的估算，
     * 不是全表统计 —— 不写清楚，数字迟早被当成结论引用。
     */
    private static Map<String, Object> profileBody(TableInspector.Profile profile) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("table", profile.table());
        out.put("window", profile.window());
        out.put("estimated", true);
        List<Map<String, Object>> stats = new ArrayList<>();
        for (TableInspector.ColumnStat stat : profile.stats()) {
            LinkedHashMap<String, Object> one = new LinkedHashMap<>();
            one.put("name", stat.name());
            one.put("nullRate", stat.nullRate());
            one.put("distinct", stat.distinct());
            one.put("distinctCapped", stat.distinctCapped());
            one.put("min", stat.min());
            one.put("max", stat.max());
            one.put("samples", stat.samples());
            one.put("masked", stat.masked());
            stats.add(one);
        }
        out.put("columns", stats);
        return out;
    }

    /** 起草建议段：维度候选、比对字段候选、理由与一份最小骨架。 */
    private static Map<String, Object> adviceBody(TableInspector.Advice advice) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("table", advice.table());
        out.put("against", advice.against());
        out.put("dimensions", suggestions(advice.dimensions()));
        out.put("compares", suggestions(advice.compares()));
        out.put("notes", advice.notes());
        out.put("snippet", advice.snippet());
        return out;
    }

    private static List<Map<String, Object>> suggestions(List<TableInspector.Suggestion> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableInspector.Suggestion item : items) {
            LinkedHashMap<String, Object> one = new LinkedHashMap<>();
            one.put("column", item.column());
            one.put("why", item.why());
            out.add(one);
        }
        return out;
    }

    @GetMapping("/{table}")
    public ResponseEntity<Map<String, Object>> describe(@PathVariable String table,
                                                        @RequestParam(required = false) Integer maxColumns,
                                                        @RequestParam(required = false, defaultValue = "false") boolean sample,
                                                        @RequestParam(required = false) Integer window,
                                                        @RequestParam(required = false, defaultValue = "false") boolean advice,
                                                        @RequestParam(required = false) String against) {
        TableAllowList list = allowList.getIfAvailable();
        if (list == null) {
            // 没开 Agent 模式就没有白名单。此时"拒绝"是唯一安全答案：不能证明可见，就不给看。
            return ResponseEntity.status(404).body(Map.of("error", "表 " + table + " 不可见（未开启 Agent 模式，没有白名单）"));
        }
        if (!list.allows(table)) {
            return ResponseEntity.status(404).body(Map.of("error", "表 " + table + " 不可见"));
        }
        TableInspector.Detail detail;
        try {
            detail = inspector.describe(table, maxColumns == null ? TableInspector.DEFAULT_MAX_COLUMNS : maxColumns);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("table", detail.table());
        body.put("rowCount", detail.rowCount());
        body.put("rowSource", detail.rowSource());
        body.put("truncated", detail.truncated());
        body.put("primaryKey", detail.primaryKey());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TableInspector.Column column : detail.columns()) {
            LinkedHashMap<String, Object> one = new LinkedHashMap<>();
            one.put("name", column.name());
            one.put("type", column.type());
            one.put("nullable", column.nullable());
            one.put("size", column.size());
            one.put("scale", column.scale());
            one.put("primaryKey", column.primaryKey());
            columns.add(one);
        }
        body.put("columns", columns);
        if (sample) {
            try {
                TableInspector.Profile profile = inspector.profile(
                        table, window == null ? TableInspector.DEFAULT_WINDOW : window, 3);
                body.put("profile", profileBody(profile));
            } catch (IllegalArgumentException e) {
                body.put("profileError", e.getMessage());
            }
        }
        if (advice) {
            try {
                body.put("advice", adviceBody(inspector.advice(table, against)));
            } catch (IllegalArgumentException e) {
                body.put("adviceError", e.getMessage());
            }
        }
        return ResponseEntity.ok(body);
    }
}
