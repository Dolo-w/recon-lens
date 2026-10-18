package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.store.RegisteredTableRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 动态表注册（第二层接入）：让数据结构不同的新用户<b>不改配置、不重启</b>就把表放进闸门 1。
 *
 * <p>两段式，preview → confirm：
 * <ul>
 *   <li><b>preview 只读元数据</b>（表存不存在、有哪些列、大约多少行），不改任何状态 ——
 *       给人和模型看的；</li>
 *   <li><b>confirm 才落库 + 更新内存白名单</b>，闸门 1 与起草 prompt 立即看见这张表。</li>
 * </ul>
 *
 * <p>调用方（MCP 工具）必须把 preview 的结果展示给人，人确认后才能调 confirm ——
 * 这是"模型可以提议、不能拍板"边界在本子系统里的又一处落地：注册改变的是安全边界本身，
 * 比放行一条规则更敏感，所以连"自动批准"通道都不给。
 *
 * <p>confirm 前内部重新 probe 一次：注册的那一刻表必须真的存在，而不是"预览时存在过"。
 */
public class TableRegistrar {

    /**
     * 表名白名单格式：{@code [schema.]标识符}。注册器的输入可能一路进 SQL（行数探测），
     * 这里从严 —— 拒绝引号、空白、分号与一切非标识符字符，宁可让用户换个写法。
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
     */
    public record Preview(String table, List<String> columns, Long rows, String error) {
    }

    /** 只读预览：表存在性、列清单、行数。不改任何状态。 */
    public Preview preview(String table) {
        String name = normalize(table);
        if (name == null) {
            return new Preview(table == null ? "" : table.trim(), List.of(), null,
                    "表名不合法：只允许 [schema.]标识符（字母/数字/下划线/$），不要引号或特殊字符");
        }
        Optional<SchemaCatalog.TableSchema> found = schema.probe(name);
        if (found.isEmpty()) {
            return new Preview(name, List.of(), null,
                    "数据源里查不到这张表的元数据（表不存在，或连接账号没有元数据权限）");
        }
        return new Preview(name, List.copyOf(found.get().columns()), countRows(name), null);
    }

    /** 确认注册：落库审计 + 本进程白名单立即生效。幂等，重复注册覆盖快照与登记人。 */
    public Preview confirm(String table, String operator) {
        Preview p = preview(table);
        if (p.error() != null) {
            return p;
        }
        String by = operator == null || operator.isBlank() ? "api" : operator.trim();
        registry.upsert(p.table(), String.join(",", p.columns()), by);
        schema.addDynamic(p.table());
        return p;
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
