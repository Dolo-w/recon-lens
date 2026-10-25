package io.github.dolow.reconlens.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 读一张表的**结构**：列名、类型、可空、长度，主键，行数。
 *
 * <p>三条设计约束，都是踩过坑之后加的：
 *
 * <ul>
 *   <li><b>只读元数据，不读数据行</b>。{@code SELECT * FROM t WHERE 1=0} 拿到
 *       {@link ResultSetMetaData}，数据库不会真扫表。样例值属于另一层（有脱敏与开关），
 *       不该混在"看结构"里。</li>
 *   <li><b>行数优先用快照</b>。注册表里存着登记那一刻的 {@code row_count}，零成本；
 *       只有快照缺失才 {@code COUNT(*)}，并且带超时——千万行的表上不该因为"看一眼结构"
 *       就把业务库拖住。</li>
 *   <li><b>行数拿不到就说拿不到</b>。超时不是错误，是事实：宁可回"没取到"，也不回一个旧数字
 *       假装是现在。</li>
 * </ul>
 *
 * <p>表名拼接前必须过 {@link #SAFE_TABLE}：元数据接口拿不到参数化表名，这里是唯一防线。
 */
@Component
public class TableInspector {

    private static final Logger log = LoggerFactory.getLogger(TableInspector.class);

    /** 标识符白名单：字母/数字/下划线/$，可选 schema 前缀。不够宽松，但拼 SQL 只允许这个。 */
    static final Pattern SAFE_TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    /** 一次描述最多列多少列。宽表（几十列）要能一屏看完，多出来的要明确说"截断了"。 */
    public static final int DEFAULT_MAX_COLUMNS = 80;

    /** COUNT(*) 的超时秒数：超了就回"没取到"，不硬等。 */
    private static final int COUNT_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate jdbc;
    private final ObjectProvider<RegisteredTableRepository> registry;

    /**
     * 是否允许返回样例原文。默认 false：给人看结构不需要原文，
     * 但"顺手截图发群里"是常态 —— 默认原文等于默认泄露。
     *
     * <p>用字段注入而不是构造参数：本类在测试里是 new 出来的（不走 Spring），
     * 构造参数一改就要动所有调用点；字段注入让"测试里永远是默认值"这件事自然成立。
     */
    @org.springframework.beans.factory.annotation.Value("${recon.schema.sample-raw:false}")
    private boolean rawSamples = false;

    public TableInspector(JdbcTemplate jdbc, ObjectProvider<RegisteredTableRepository> registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /** 一列的结构。{@code size} 是显示宽度，{@code scale} 只对小数有意义。 */
    public record Column(String name, String type, boolean nullable, Integer size, Integer scale,
                         boolean primaryKey) {
    }

    /**
     * 一张表的结构。
     *
     * @param rowCount 快照或现算的行数；拿不到时为 {@code null}
     * @param rowSource {@code REGISTERED}（注册快照）/ {@code LIVE}（现算）/ {@code UNKNOWN}（拿不到）
     * @param truncated 列太多，只返回了前 {@code maxColumns} 列
     */
    public record Detail(String table, Long rowCount, String rowSource, List<Column> columns,
                         List<String> primaryKey, boolean truncated) {

        public boolean known() {
            return columns != null && !columns.isEmpty();
        }
    }

    /** 表名能不能拼进 SQL。调用方通常在更早已按白名单拦过，这里是第二道。 */
    public static boolean safeName(String table) {
        return table != null && SAFE_TABLE.matcher(table.trim()).matches();
    }

    /**
     * 读结构。
     *
     * @throws IllegalArgumentException 表名不合法，或表不存在/读不到元数据
     */
    public Detail describe(String table, int maxColumns) {
        String name = table == null ? "" : table.trim();
        if (!safeName(name)) {
            throw new IllegalArgumentException("表名只能由字母、数字、下划线组成（可带 schema 前缀），收到：" + name);
        }
        int limit = maxColumns <= 0 ? DEFAULT_MAX_COLUMNS : maxColumns;

        Set<String> keys = primaryKeys(name);
        List<Column> all = new ArrayList<>();
        try {
            readColumns(name, keys, all);
        } catch (RuntimeException e) {
            // 表不存在 / 没权限 / 库不对：都从这里冒出来。统一翻成人话，
            // 别把 Spring 的 BadSqlGrammarException 直接甩给用户（他看不懂，也不知道该改什么）。
            throw new IllegalArgumentException("表 " + name + " 读不到元数据（表名拼错？库不对？没权限？）："
                    + rootMessage(e), e);
        }

        if (all.isEmpty()) {
            throw new IllegalArgumentException("表 " + name + " 里没有读到任何列（表名拼错？库不对？）");
        }
        List<Column> shown = all.size() > limit ? all.subList(0, limit) : all;
        Optional<RowCount> rows = rowCount(name);
        return new Detail(name, rows.map(RowCount::count).orElse(null), rows.map(RowCount::source).orElse("UNKNOWN"),
                List.copyOf(shown), List.copyOf(keys), all.size() > limit);
    }

    /** 取列元数据。{@code WHERE 1=0}：数据库只回结构，不扫行。 */
    private void readColumns(String name, Set<String> keys, List<Column> all) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                // WHERE 1=0：数据库只回元数据，不扫行
                statement.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
                try (var rs = statement.executeQuery("SELECT * FROM " + name + " WHERE 1=0")) {
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        String label = meta.getColumnLabel(c);
                        Integer size = meta.getPrecision(c) > 0 ? meta.getPrecision(c) : null;
                        Integer scale = meta.getScale(c) > 0 ? meta.getScale(c) : null;
                        boolean numeric = isNumeric(meta.getColumnType(c));
                        all.add(new Column(label, typeName(meta, c),
                                meta.isNullable(c) != ResultSetMetaData.columnNoNulls,
                                numeric ? size : size, numeric ? scale : null,
                                keys.contains(label.toLowerCase(Locale.ROOT))));
                    }
                }
            }
            return null;
        });
    }

    /** 把 Spring 的嵌套异常压成一句有用的原因（最深的那句通常才是"表不存在"）。 */
    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message.trim();
    }

    // ------------------------------------------------------------------ L2：有界采样的质量画像

    /** 采样窗口上限。再大就不是"看一眼"，是让业务库替我们做全表统计。 */
    public static final int MAX_WINDOW = 2_000;
    public static final int DEFAULT_WINDOW = 500;
    private static final int MAX_SAMPLES = 10;
    private static final int DISTINCT_CAP = 500;

    /**
     * 一列的采样统计。
     *
     * @param nullRate      空值率（0~1）；窗口为 0 时是 {@code null}，不是 0 —— 没数据不等于没空值
     * @param distinct      窗口内的不同值个数；到 {@link #DISTINCT_CAP} 就没再数了
     * @param distinctCapped 是否被上限截断
     * @param samples       样例值（已脱敏，除非显式开原文）
     */
    public record ColumnStat(String name, int window, Double nullRate, Integer distinct, boolean distinctCapped,
                             String min, String max, List<String> samples, boolean masked) {
    }

    /** 采样画像。{@code window} 是真正取到的行数 —— 所有比例都是在这个窗口上算的估算值。 */
    public record Profile(String table, int window, List<ColumnStat> stats) {
    }

    /**
     * 在可控窗口上算列的分布：空值率、不同值个数、数值/字符的极值、脱敏样例。
     *
     * <p>为什么在 Java 里算而不是用 SQL 聚合：{@code COUNT(DISTINCT)} 在 MySQL 与 H2 上对
     * 字符集/空值的口径不完全一致，而这里要的是"给人一个大概"，不是"给报表一个精确值"。
     * 读一个窗口回来自己数，结果可复现、也不给业务库压力。
     *
     * @param sampleSize 每列取几个样例，0 表示不取
     * @param raw        是否返回原文（默认 false；由 {@code recon.schema.sample-raw} 决定）
     */
    public Profile profile(String table, int window, int sampleSize) {
        return profile(table, window, sampleSize, rawSamples);
    }

    /** 显式指定是否给原文：测试用，生产走配置。 */
    public Profile profile(String table, int window, int sampleSize, boolean raw) {
        String name = table == null ? "" : table.trim();
        if (!safeName(name)) {
            throw new IllegalArgumentException("表名只能由字母、数字、下划线组成（可带 schema 前缀），收到：" + name);
        }
        int rows = Math.max(1, Math.min(window <= 0 ? DEFAULT_WINDOW : window, MAX_WINDOW));
        int samples = Math.max(0, Math.min(sampleSize, MAX_SAMPLES));

        List<ColumnStat> stats = new java.util.ArrayList<>();
        try {
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
                    try (var rs = statement.executeQuery("SELECT * FROM " + name + " LIMIT " + rows)) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        int columnCount = meta.getColumnCount();
                        int[] nulls = new int[columnCount];
                        java.util.List<java.util.Set<String>> distinct = new java.util.ArrayList<>();
                        java.util.List<java.util.List<String>> kept = new java.util.ArrayList<>();
                        String[] mins = new String[columnCount];
                        String[] maxs = new String[columnCount];
                        boolean numeric = false;
                        for (int c = 0; c < columnCount; c++) {
                            distinct.add(new java.util.LinkedHashSet<>());
                            kept.add(new java.util.ArrayList<>());
                        }
                        int seen = 0;
                        while (rs.next() && seen < rows) {
                            seen++;
                            for (int c = 1; c <= columnCount; c++) {
                                String value = rs.getString(c);
                                if (value == null) {
                                    nulls[c - 1]++;
                                    continue;
                                }
                                java.util.Set<String> bucket = distinct.get(c - 1);
                                if (bucket.size() < DISTINCT_CAP) {
                                    bucket.add(value);
                                }
                                if (kept.get(c - 1).size() < samples) {
                                    kept.get(c - 1).add(value);
                                }
                                if (mins[c - 1] == null || compare(value, mins[c - 1]) < 0) {
                                    mins[c - 1] = value;
                                }
                                if (maxs[c - 1] == null || compare(value, maxs[c - 1]) > 0) {
                                    maxs[c - 1] = value;
                                }
                            }
                        }
                        for (int c = 1; c <= columnCount; c++) {
                            String column = meta.getColumnLabel(c);
                            java.util.Set<String> bucket = distinct.get(c - 1);
                            java.util.List<String> rawSamples = kept.get(c - 1);
                            java.util.List<String> shown = new java.util.ArrayList<>();
                            boolean changed = false;
                            for (String value : rawSamples) {
                                String maskedValue = raw ? value : Masking.mask(column, value);
                                if (!java.util.Objects.equals(value, maskedValue)) {
                                    changed = true;
                                }
                                shown.add(maskedValue);
                            }
                            // masked 的含义是"这一列的样例真被打码了"，不是"我们跑过打码函数"。
                            // 后者会让 order_no 这种普通列也带着"（已脱敏）"，反而没人信这个标记。
                            final boolean masked = !raw && changed;
                            stats.add(new ColumnStat(column, seen,
                                    seen == 0 ? null : round((double) nulls[c - 1] / seen),
                                    bucket.size(), bucket.size() >= DISTINCT_CAP,
                                    mins[c - 1] == null ? null : (raw ? mins[c - 1] : Masking.mask(column, mins[c - 1])),
                                    maxs[c - 1] == null ? null : (raw ? maxs[c - 1] : Masking.mask(column, maxs[c - 1])),
                                    List.copyOf(shown), masked));
                        }
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("表 " + name + " 采样失败（表名拼错？没权限？）："
                    + rootMessage(e), e);
        }
        int actual = stats.isEmpty() ? 0 : stats.get(0).window();
        return new Profile(name, actual, List.copyOf(stats));
    }

    /** 一条建议：用哪一列、为什么。理由必须写出来，否则等于让人猜。 */
    public record Suggestion(String column, String why) {
    }

    /**
     * 起草建议。
     *
     * <p>定位是"给个起点"，不是"替你定规则"：它只从**列名与类型**推断（一次数据行都不读），
     * 并且每条建议都带理由，方便人否决。真正该不该这么配，要看采样出来的空值率与不同值。
     */
    public record Advice(String table, String against, List<Suggestion> dimensions,
                         List<Suggestion> compares, List<String> notes, String snippet) {
    }

    /** 技术列：出现在每张表里，对账没意义。留在这里比在每个调用点各自过滤可靠。 */
    private static final Set<String> TECHNICAL = Set.of(
            "id", "created_at", "updated_at", "deleted_at", "create_time", "update_time",
            "etag", "version", "row_version", "__biz_key");

    private static final Pattern KEY_NAME = Pattern.compile(
            ".*(_no|_id|_code|_key|_type|_status|_city|_region|_channel|_account|_phone)$|^(no|code|key|type|status)$");

    /**
     * 起草建议：维度候选 + 比对字段候选 + 一份可以粘贴的最小规则骨架。
     *
     * @param against 另一侧的表名（可选）。给了就会求同名数值列的交集——两侧字段名不一致是常态，
     *                交集为空时要说出来，而不是给一份跑不通的骨架
     */
    public Advice advice(String table, String against) {
        Detail detail = describe(table, DEFAULT_MAX_COLUMNS);
        List<Suggestion> dimensions = new ArrayList<>();
        List<Suggestion> compares = new ArrayList<>();
        for (Column column : detail.columns()) {
            String name = column.name().toLowerCase(Locale.ROOT);
            if (TECHNICAL.contains(name)) {
                continue;
            }
            if (KEY_NAME.matcher(name).matches()) {
                // 分类列与键列都叫"像业务键"，但用法不同：status/type 通常做汇总维度，
                // 逐笔对账的维度要选"一笔业务唯一的那个"。说清楚，别让人照着抄。
                boolean category = name.endsWith("_status") || name.endsWith("_type")
                        || name.equals("status") || name.equals("type");
                dimensions.add(new Suggestion(column.name(), category
                        ? "名字像分类列，适合做汇总/分组维度；逐笔对账的维度要选一笔业务唯一的那个（如订单号）"
                        : "名字像业务键，适合做对账维度（同维度在两侧必须唯一）"));
            } else if (looksNumeric(column.type())) {
                compares.add(new Suggestion(column.name(), "数值列，适合做比对字段（注意容差与进位）"));
            }
        }

        List<String> notes = new ArrayList<>();
        Set<String> otherColumns = new LinkedHashSet<>();
        if (against != null && !against.isBlank()) {
            try {
                Detail other = describe(against, DEFAULT_MAX_COLUMNS);
                for (Column column : other.columns()) {
                    otherColumns.add(column.name().toLowerCase(Locale.ROOT));
                }
                List<Suggestion> shared = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (Suggestion candidate : compares) {
                    if (otherColumns.contains(candidate.column().toLowerCase(Locale.ROOT))) {
                        shared.add(candidate);
                    } else {
                        missing.add(candidate.column());
                    }
                }
                if (!shared.isEmpty()) {
                    compares = shared;
                    notes.add("两侧同名的数值列：" + shared.stream().map(Suggestion::column).distinct().toList()
                            + "，比对字段从这些里选最直接");
                } else {
                    notes.add("两侧没有同名数值列，比对字段需要手工映射（规则里可以写 side.field）");
                }
                if (!missing.isEmpty()) {
                    notes.add("只有本侧有的数值列：" + missing + "，对方没有对应字段就别拿来比");
                }
            } catch (IllegalArgumentException e) {
                notes.add("读另一侧表 " + against + " 失败（" + e.getMessage() + "），交集未计算");
            }
        }

        if (dimensions.isEmpty()) {
            notes.add("没找到像业务键的列；维度可能要用组合（例如 客户 + 订单号），或显式声明 dimension");
        }
        if (compares.isEmpty()) {
            notes.add("没找到数值列，只能比对状态/文本是否相等（type: STRING）");
        }
        notes.add("建议先看样例（sample=true）确认空值率与不同值，再定维度组合——维度上有空值会直接出假缺失");
        notes.add("骨架里分片先留 1；量大了再加 whereLeft/whereRight，两侧必须同一口径切，否则出假缺失");

        String dimensionValue = dimensions.isEmpty() ? "[id]" : dimensions.stream()
                .map(Suggestion::column).distinct().limit(2).toList().toString();
        String compareColumn = compares.isEmpty() ? "" : compares.get(0).column();
        String snippet = renderSnippet(table, against, dimensionValue, compareColumn);
        return new Advice(table, against, List.copyOf(dimensions), List.copyOf(compares), List.copyOf(notes), snippet);
    }

    private static boolean looksNumeric(String type) {
        String head = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return head.startsWith("DECIMAL") || head.startsWith("NUMERIC") || head.startsWith("NUMBER")
                || head.startsWith("INT") || head.startsWith("BIGINT") || head.startsWith("SMALLINT")
                || head.startsWith("TINYINT") || head.startsWith("DOUBLE") || head.startsWith("FLOAT")
                || head.startsWith("REAL");
    }

    /**
     * 最小可跑骨架。刻意只写必填项：可选的东西写多了，人会以为那些是必须的。
     *
     * <p>{@code shard.count: 1} 是刻意的——分片要求两侧同一口径切分，自动生成一个"看起来能跑"的
     * 取模条件比不生成更危险。
     */
    private static String renderSnippet(String table, String against, String dimension, String compareColumn) {
        String right = (against == null || against.isBlank()) ? "<右侧表>" : against;
        String field = compareColumn.isEmpty() ? "<比对字段>" : compareColumn;
        return String.join("\n",
                "code: NEW_RECON",
                "name: 新对账规则",
                "",
                "left:",
                "  label: 左",
                "  table: " + table,
                "  keyType: STRING",
                "  dimension: " + dimension,
                "",
                "right:",
                "  label: 右",
                "  table: " + right,
                "  keyType: STRING",
                "  dimension: " + dimension,
                "",
                "shard:",
                "  count: 1",
                "  parallelism: 1",
                "",
                "compare:",
                "  - field: " + field,
                "    type: DECIMAL",
                "    tolerancePercent: 0.0001",
                "    diffType: AMOUNT_MISMATCH",
                "    severity: HIGH",
                "",
                "missing:",
                "  leftMissingDiffType: MISSING_LEFT",
                "  rightMissingDiffType: MISSING_RIGHT",
                "  severity: HIGH",
                "",
                "duplicate:",
                "  diffType: DUPLICATE_KEY",
                "  severity: MEDIUM");
    }

    /** 数字按数值比，其它按字符串比。{@code "9"} 与 {@code "10"} 的坑就出在这里。 */
    private static int compare(String left, String right) {
        try {
            return new java.math.BigDecimal(left).compareTo(new java.math.BigDecimal(right));
        } catch (NumberFormatException ignored) {
            return left.compareTo(right);
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 行数 + 来源。来源比数字本身重要：快照数字可能过时，现算数字可能没取到。 */
    private record RowCount(Long count, String source) {
    }

    private Optional<RowCount> rowCount(String table) {
        RegisteredTableRepository repo = registry.getIfAvailable();
        if (repo != null) {
            try {
                Optional<RegisteredTableRepository.Registration> registered = repo.find(table);
                if (registered.isPresent() && registered.get().rowCount() != null) {
                    return Optional.of(new RowCount(registered.get().rowCount(), "REGISTERED"));
                }
            } catch (RuntimeException e) {
                log.debug("读注册快照失败，退回现算行数: {}", e.getMessage());
            }
        }
        try {
            Long n = jdbc.execute((ConnectionCallback<Long>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(COUNT_TIMEOUT_SECONDS);
                    try (var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        return rs.next() ? rs.getLong(1) : null;
                    }
                }
            });
            return n == null ? Optional.empty() : Optional.of(new RowCount(n, "LIVE"));
        } catch (RuntimeException e) {
            // 超时/无权限/表太大：不是错误，是"这次没取到"
            log.warn("统计表 {} 的行数失败（按未知处理）: {}", table, e.getMessage());
            return Optional.empty();
        }
    }

    /** 主键列（小写）。拿不到就返回空集合——调用方据此说"主键未识别"，而不是猜。 */
    private Set<String> primaryKeys(String table) {
        Set<String> keys = new LinkedHashSet<>();
        String bare = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                for (String candidate : List.of(bare, bare.toUpperCase(Locale.ROOT))) {
                    try (var rs = connection.getMetaData().getPrimaryKeys(null, null, candidate)) {
                        while (rs.next()) {
                            keys.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    if (!keys.isEmpty()) {
                        break;
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("读主键失败（按未识别处理）: {}", e.getMessage());
        }
        return keys;
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    /**
     * 带长度的类型名，如 {@code VARCHAR(64)} / {@code DECIMAL(18,2)}。
     *
     * <p>顺手把"标准名"折成人话：H2 报 {@code CHARACTER VARYING(32)}，MySQL 报 {@code varchar(32)}，
     * 两边同一列显示成两个样。这不是数据库差异，是名字差异，读者不该为此多学一套术语。
     */
    private static String typeName(ResultSetMetaData meta, int c) throws java.sql.SQLException {
        String base = meta.getColumnTypeName(c)
                .replace("CHARACTER VARYING", "VARCHAR")
                .replace("BINARY VARYING", "VARBINARY")
                .replace("DOUBLE PRECISION", "DOUBLE");
        int size = meta.getPrecision(c);
        int scale = meta.getScale(c);
        if (size <= 0) {
            return base;
        }
        if (isNumeric(meta.getColumnType(c)) && scale > 0) {
            return base + "(" + size + "," + scale + ")";
        }
        return switch (meta.getColumnType(c)) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR -> base + "(" + size + ")";
            default -> base;
        };
    }
}
