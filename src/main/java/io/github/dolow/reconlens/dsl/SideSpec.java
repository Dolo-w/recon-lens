package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.engine.FieldType;

import java.util.List;
import java.util.Map;

/**
 * 一侧数据源。三种写法（从简到繁）：
 * <ul>
 *   <li>单表 + keyField：按某个业务键列对账</li>
 *   <li>自定义 SQL：sql 里把业务键别名为 keyField，其余列作为比对字段</li>
 *   <li>声明式维度：dimension（列列表或表达式）+ 可选 measures（聚合列）+ 可选 where（原始数据过滤）</li>
 * </ul>
 * 三种写法最终都会生成一条<b>按业务键升序取数</b>的 SELECT —— 归并式对账的正确性依赖这个前提。
 *
 * <p>声明式维度是为"使用方自己定义对账维度"准备的：复合键用 {@code dimension: [a, b]}，
 * 表达式维度用 {@code dimension: "CONCAT(a, '|', b)"}，聚合维度再加 {@code measures}。
 * 它们都收敛到同一个生成的键列 {@link #KEY_ALIAS}，因此引擎（归并/比较/落库）完全不需要知道维度长什么样。
 */
public record SideSpec(String label,
                       String table,
                       String sql,
                       String keyField,
                       FieldType keyType,
                       String orderBy,
                       String dimension,
                       List<String> dimensionColumns,
                       String dimensionSeparator,
                       Map<String, String> measures,
                       String where) {

    /** 声明式维度生成的业务键别名：引擎只认一个键列，复合键与表达式维度都收敛到它。 */
    public static final String KEY_ALIAS = "__biz_key";

    /** 默认的复合键分隔符（可读性优先；值里可能出现分隔符时请显式指定 dimensionSeparator）。 */
    public static final String DEFAULT_DIMENSION_SEPARATOR = "|";

    /** 老写法（表/SQL + keyField）的兼容构造器。 */
    public SideSpec(String label, String table, String sql, String keyField, FieldType keyType, String orderBy) {
        this(label, table, sql, keyField, keyType, orderBy, null, List.of(),
                DEFAULT_DIMENSION_SEPARATOR, Map.of(), null);
    }

    public boolean customSql() {
        return sql != null && !sql.isBlank();
    }

    /** 是否使用声明式维度（复合键 / 表达式 / 聚合）。 */
    public boolean derivedKey() {
        return dimension != null && !dimension.isBlank();
    }

    public boolean aggregated() {
        return !measures.isEmpty();
    }

    /** 参与取数的内层查询。 */
    public String sourceSelect() {
        return customSql() ? sql : "SELECT * FROM " + table;
    }

    /** 业务键表达式：声明式维度就是那段表达式，否则是业务键列本身。 */
    public String keyExpression() {
        return derivedKey() ? dimension : keyField;
    }

    public String describe() {
        String source = customSql() ? "自定义SQL" : table;
        return label + "(" + source + (derivedKey() ? ", 维度=" + dimension : "") + ")";
    }
}
