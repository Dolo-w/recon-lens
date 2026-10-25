package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.store.DatasetFingerprints;
import io.github.dolow.reconlens.store.RegisteredTableRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 动态表注册：预览 → 人工确认 → 落库 + 白名单立即生效。
 *
 * <p><b>注册会放宽安全边界</b>，所以每个失败路径都要求"一个字节的变更都不留"：
 * preview 只读；confirm 先 preview 再写库，任一步失败都不留半截状态。
 *
 * <p>指纹（{@code schema_hash} / {@code row_count}）在 confirm 时落：注册那一刻的结构与行数，
 * 是之后判断"这份数据换过没有"的基准（ADR-0002 D3）。重注册时把<b>列的新增/消失</b>直接说出来 ——
 * 少了的那一列如果被规则引用，跑起来就是 {@code UNKNOWN_COLUMN}，提前一句话能省半小时排障。
 */
public class TableRegistrar {

    /**
     * 表名只允许 {@code [schema.]标识符}：这个名字会进 DDL 与审计表，
     * 放过引号/分号等于把注入面开到数据库。
     */
    private static final Pattern TABLE_NAME = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    private final SchemaCatalog schema;
    private final RegisteredTableRepository registry;
    private final JdbcTemplate jdbc;

    public TableRegistrar(SchemaCatalog schema, RegisteredTableRepository registry, JdbcTemplate jdbc) {
        this.schema = schema;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /**
     * 一次注册预览/确认的结果。{@code error} 非空表示没有发生任何变更（preview 与 confirm 同此语义：
     * <b>要么全成，要么什么都不动</b>，不留"注册了但列快照是空的"这种中间态）。
     *
     * <p>{@code added}/{@code removed}/{@code rowsBefore} 是"与上一次注册相比"的差别：
     * 首次注册时 {@code rowsBefore == null} 且两个列表都空。
     */
    public record Preview(String table, List<String> columns, Long rows, String error,
                          List<String> added, List<String> removed, Long rowsBefore) {

        public boolean firstTime() {
            return rowsBefore == null && added.isEmpty() && removed.isEmpty();
        }

        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }

        /** 一行说清"这次注册改了什么"，没改就说没改。 */
        public String changeText() {
            if (firstTime()) {
                return "首次注册";
            }
            List<String> parts = new ArrayList<>();
            if (!added.isEmpty()) {
                parts.add("新增 " + added.size() + " 列（" + String.join(", ", added) + "）");
            }
            if (!removed.isEmpty()) {
                parts.add("消失 " + removed.size() + " 列（" + String.join(", ", removed) + "）");
            }
            if (parts.isEmpty()) {
                return "列与上次注册一致";
            }
            String text = "与上次注册相比：" + String.join("；", parts);
            if (!removed.isEmpty()) {
                text += "。消失的列如果被规则引用，跑起来会报未知列";
            }
            return text;
        }
    }

    /** 只读预览：表存在性、列清单、行数、与上次注册的差别。不改任何状态。 */
    public Preview preview(String table) {
        String name = normalize(table);
        if (name == null) {
            return new Preview(table == null ? "" : table.trim(), List.of(), null,
                    "表名不合法：只允许 [schema.]标识符（字母/数字/下划线/$），不要引号或特殊字符",
                    List.of(), List.of(), null);
        }
        Optional<SchemaCatalog.TableSchema> found = schema.probe(name);
        if (found.isEmpty()) {
            return new Preview(name, List.of(), null,
                    "数据源里查不到这张表的元数据（表不存在，或连接账号没有元数据权限）",
                    List.of(), List.of(), null);
        }
        List<String> columns = List.copyOf(found.get().columns());
        Optional<RegisteredTableRepository.Registration> before = registry.find(name);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Long rowsBefore = null;
        if (before.isPresent()) {
            rowsBefore = before.get().rowCount();
            Set<String> previous = lower(before.get().columns());
            Set<String> current = lower(columns);
            for (String c : current) {
                if (!previous.contains(c)) {
                    added.add(c);
                }
            }
            for (String c : previous) {
                if (!current.contains(c)) {
                    removed.add(c);
                }
            }
        }
        return new Preview(name, columns, countRows(name), null, List.copyOf(added), List.copyOf(removed), rowsBefore);
    }

    /** 确认注册：落库审计 + 本进程白名单立即生效。幂等，重复注册覆盖快照与登记人。 */
    public Preview confirm(String table, String operator) {
        Preview p = preview(table);
        if (p.error() != null) {
            return p;
        }
        String by = operator == null || operator.isBlank() ? "api" : operator.trim();
        registry.upsert(p.table(), String.join(",", p.columns()), DatasetFingerprints.hash(p.columns()), p.rows(), by);
        schema.addDynamic(p.table());
        return p;
    }

    private static Set<String> lower(List<String> columns) {
        Set<String> set = new LinkedHashSet<>();
        for (String c : columns) {
            if (c != null && !c.isBlank()) {
                set.add(c.trim().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private static String normalize(String table) {
        if (table == null) {
            return null;
        }
        String name = table.trim();
        return TABLE_NAME.matcher(name).matches() ? name : null;
    }

    /** 行数只是预览里的参考信息，拿不到（权限/方言）不让它挡注册。 */
    private Long countRows(String table) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
