package io.github.dolow.reconlens.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表白名单 + 列元数据，闸门 1 的"对象存在性"依据。
 *
 * <p><b>白名单为空 = 全部拒绝，不是全部允许</b>。安全开关的默认值必须是 fail-closed：
 * 忘了配白名单的后果应该是"Agent 什么也生成不出来"，而不是"Agent 可以读整库"。
 *
 * <p>白名单有两个来源：<b>配置</b>（{@code recon.agent.schema.tables}，静态）∪
 * <b>动态注册</b>（{@code RegisteredTableRepository}，对话内经人工确认写入）——
 * 后者让数据结构不同的新用户不改配置、不重启就能接入。动态半边按 TTL 轮询刷新
 * （Web 实例与 MCP 进程是两个 JVM，靠共享库对齐），本进程注册后走 {@link #addDynamic} 立即生效。
 *
 * <p>元数据从 {@link DatabaseMetaData} 读，按表缓存（{@link #refresh()} 清空）。
 * 缓存是必要的：闸门 1 每校验一条规则要查几张表，而元数据查询在 MySQL 上是一次 information_schema 扫描。
 */
public class SchemaCatalog {

    private static final Logger log = LoggerFactory.getLogger(SchemaCatalog.class);

    /** 动态注册表的刷新间隔。注册是低频动作，30 秒的跨进程可见性延迟完全可接受。 */
    private static final long DYNAMIC_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final DataSource dataSource;
    private final AgentProperties properties;
    private final io.github.dolow.reconlens.store.RegisteredTableRepository registry;
    private final Map<String, Optional<TableSchema>> cache = new ConcurrentHashMap<>();

    /** 动态白名单的进程内快照 + 装载时间。volatile：闸门校验可能来自多个线程。 */
    private volatile Set<String> dynamicCache = Set.of();
    private volatile long dynamicCacheAtNanos = Long.MIN_VALUE;

    public SchemaCatalog(DataSource dataSource, AgentProperties properties) {
        this(dataSource, properties, null);
    }

    public SchemaCatalog(DataSource dataSource, AgentProperties properties,
                         io.github.dolow.reconlens.store.RegisteredTableRepository registry) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.registry = registry;
        if (properties.getSchema().getTables().isEmpty()) {
            log.warn("recon.agent.schema.tables 为空：闸门 1 会拒绝所有规则（白名单是 fail-closed 的，"
                    + "要放行哪些表就显式列出来，或用 recon_register_table 动态注册）");
        }
    }

    /** 一张表的列集合（大小写不敏感比较，MySQL 与 H2 的元数据大小写习惯不同）。顺序 = DDL 列序。 */
    public record TableSchema(String name, Set<String> columns) {

        public TableSchema {
            // Set.copyOf 是哈希序、不保序 —— 列顺序承载 DDL 语义（注册快照、起草 prompt），
            // 这里必须用 LinkedHashSet 包装保序
            columns = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(columns));
        }

        public boolean hasColumn(String column) {
            if (column == null || column.isBlank()) {
                return false;
            }
            String target = column.trim();
            for (String candidate : columns) {
                if (candidate.equalsIgnoreCase(target)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 配置里的白名单原始值（已去空白）。 */
    public List<String> configuredTables() {
        List<String> tables = new ArrayList<>();
        for (String table : properties.getSchema().getTables()) {
            if (table != null && !table.isBlank()) {
                tables.add(table.trim());
            }
        }
        return List.copyOf(tables);
    }

    /**
     * 表是否在白名单内。忽略 schema 前缀与大小写：配置写 {@code billing.bill_left}、
     * 规则写 {@code bill_left}（或反过来）应当视为同一张表，否则运维会被一条看不见的配置坑到。
     */
    public boolean allows(String table) {
        String bare = bareName(table);
        if (bare.isEmpty()) {
            return false;
        }
        for (String allowed : configuredTables()) {
            if (bareName(allowed).equalsIgnoreCase(bare)) {
                return true;
            }
        }
        for (String allowed : dynamicTablesSnapshot()) {
            if (bareName(allowed).equalsIgnoreCase(bare)) {
                return true;
            }
        }
        return false;
    }

    /** 白名单全集（配置 ∪ 动态注册），起草 prompt 的 available-schema 段用它 —— 注册过的表必须进 LLM 视野。 */
    public List<String> allAllowedTables() {
        List<String> all = new ArrayList<>(configuredTables());
        for (String table : dynamicTablesSnapshot()) {
            boolean exists = false;
            for (String listed : all) {
                if (bareName(listed).equalsIgnoreCase(bareName(table))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                all.add(table);
            }
        }
        return List.copyOf(all);
    }

    /** 白名单内且能在数据源里查到 → 返回列元数据；不在白名单或查不到 → 空。 */
    public Optional<TableSchema> find(String table) {
        if (!allows(table)) {
            return Optional.empty();
        }
        String key = bareName(table).toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, k -> load(table));
    }

    /** <b>不看白名单</b>直接读元数据（注册预览用）：调用方必须自己校验表名合法性。 */
    public Optional<TableSchema> probe(String table) {
        String bare = bareName(table);
        if (bare.isEmpty()) {
            return Optional.empty();
        }
        return load(table);
    }

    /**
     * 探测键表达式能否在该表上编译：{@code SELECT <expr> FROM <table> WHERE 1=0}。
     *
     * <p>给闸门 1 补表达式维度的列校验：列表维度能静态核对列名，表达式维度（{@code CONCAT(...)} 之类）
     * 静态抽取标识符既脆弱又容易漏 —— 让数据库自己判定，权威且零误报。
     * {@code WHERE 1=0} 不扫任何数据，成本可忽略。成功返回 empty，失败返回数据库错误消息。
     */
    public Optional<String> probeKeyExpression(String table, String expression) {
        String expr = expression == null ? "" : expression.trim();
        if (expr.isEmpty()) {
            return Optional.empty();
        }
        if (expr.contains(";")) {
            return Optional.of("表达式里出现分号（多语句）");
        }
        String stripped = SqlText.stripCommentsAndLiterals(expr);
        Matcher write = RuleSafetyValidator.WRITE_KEYWORD.matcher(stripped);
        if (write.find()) {
            return Optional.of("表达式含写操作关键字 " + write.group().toUpperCase(Locale.ROOT));
        }
        if (java.util.regex.Pattern.compile("\\(\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(stripped).find()) {
            // 维度表达式应该是"列的表达式"，不是查询。标量子查询每行执行一次，
            // 而且把任意查询藏进了规则的取数路径 —— 闸门 1 不放行这种写法。
            return Optional.of("表达式里出现子查询：维度表达式只能是列的表达式");
        }
        String sql = "SELECT " + expr + " AS __probe FROM " + table + " WHERE 1 = 0";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.of(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** 表结构变更（加列/新建表）后调用；下一次 {@link #find} 重新读元数据，动态白名单也强制重载。 */
    public void refresh() {
        cache.clear();
        dynamicCacheAtNanos = Long.MIN_VALUE;
    }

    /** 本进程刚注册一张表后的快速路径：不等 TTL，立即进白名单并清它的列缓存。 */
    public void addDynamic(String table) {
        String bare = bareName(table);
        if (bare.isEmpty()) {
            return;
        }
        Set<String> updated = new LinkedHashSet<>(dynamicTablesSnapshot());
        updated.add(bare);
        dynamicCache = Set.copyOf(updated);
        dynamicCacheAtNanos = System.nanoTime();
        cache.remove(bare.toLowerCase(Locale.ROOT));
    }

    private Set<String> dynamicTablesSnapshot() {
        if (registry == null) {
            return Set.of();
        }
        Set<String> snapshot = dynamicCache;
        long loadedAt = dynamicCacheAtNanos;
        if (loadedAt == Long.MIN_VALUE || System.nanoTime() - loadedAt >= DYNAMIC_TTL_NANOS) {
            try {
                snapshot = Set.copyOf(registry.tableNames());
            } catch (RuntimeException e) {
                // 注册表读不出来（比如表还没建）不等于"全部拒绝动态表"，沿用上次快照，下次再试。
                log.warn("读取动态表注册失败，沿用上次快照: {}", e.getMessage());
            }
            dynamicCache = snapshot;
            dynamicCacheAtNanos = System.nanoTime();
        }
        return snapshot;
    }

    private Optional<TableSchema> load(String table) {
        String bare = bareName(table);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(bare);
        candidates.add(bare.toUpperCase(Locale.ROOT));
        candidates.add(bare.toLowerCase(Locale.ROOT));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = safeCatalog(connection);
            for (String candidate : candidates) {
                // ORDINAL_POSITION 排序：列清单的顺序承载 DDL 顺序语义（注册快照、起草 prompt 都用它），
                // 而 JDBC 规范之外的驱动实现（H2 实测）返回顺序不稳定，不能直接信结果集顺序
                java.util.TreeMap<Integer, String> columns = new java.util.TreeMap<>();
                try (ResultSet rs = meta.getColumns(catalog, null, candidate, null)) {
                    while (rs.next()) {
                        String found = rs.getString("TABLE_NAME");
                        if (found == null || !bareName(found).equalsIgnoreCase(bare)) {
                            continue;
                        }
                        int ordinal = rs.getInt("ORDINAL_POSITION");
                        columns.put(rs.wasNull() ? columns.size() + 1 : ordinal, rs.getString("COLUMN_NAME"));
                    }
                }
                if (!columns.isEmpty()) {
                    return Optional.of(new TableSchema(table, new LinkedHashSet<>(columns.values())));
                }
            }
        } catch (SQLException e) {
            // 读不到元数据不等于"表不存在"，但对闸门 1 来说结果一样：不能证明它存在，就不能放行。
            log.warn("读取表 {} 的元数据失败，闸门 1 将按\"表不可用\"处理: {}", table, e.getMessage());
            return Optional.empty();
        }
        log.warn("白名单里的表 {} 在数据源里查不到列元数据（表名拼错？schema 不对？）", table);
        return Optional.empty();
    }

    private static String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    /** {@code billing.bill_left} → {@code bill_left}；去掉反引号 / 双引号 / 方括号。 */
    private static String bareName(String table) {
        if (table == null) {
            return "";
        }
        String name = table.trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        name = name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        return name.trim();
    }
}
