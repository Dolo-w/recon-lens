package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.engine.FieldType;

/**
 * 一侧数据源。两种写法：
 * <ul>
 *   <li>单表：table + keyField</li>
 *   <li>自定义 SQL：sql（必须把业务键别名为 keyField，其余字段作为比对字段）</li>
 * </ul>
 * 两种写法都必须按业务键升序取数 —— 归并式对账的正确性依赖这个前提。
 */
public record SideSpec(String label,
                       String table,
                       String sql,
                       String keyField,
                       FieldType keyType,
                       String orderBy) {

    public boolean customSql() {
        return sql != null && !sql.isBlank();
    }

    /** 参与取数的内层查询。 */
    public String sourceSelect() {
        return customSql() ? sql : "SELECT * FROM " + table;
    }

    public String describe() {
        return label + (customSql() ? "(自定义SQL)" : "(" + table + ")");
    }
}
