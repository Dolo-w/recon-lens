package io.github.dolow.reconlens.engine;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一行数据。列名统一小写，避免不同数据库/驱动对大小写与别名的处理差异。
 */
public final class Row {

    private final Map<String, Object> values;

    public Row(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((k, v) -> normalized.put(normalize(k), v));
        this.values = Collections.unmodifiableMap(normalized);
    }

    public static Row of(Map<String, Object> values) {
        return new Row(values);
    }

    public Map<String, Object> values() {
        return values;
    }

    public Object get(String column) {
        return values.get(normalize(column));
    }

    public boolean has(String column) {
        return values.containsKey(normalize(column));
    }

    /** 文本视图：null 保持 null，BigDecimal 用 toPlainString 避免科学计数法污染差异值。 */
    public String str(String column) {
        return render(get(column));
    }

    public static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return values.toString();
    }

    private static String normalize(String column) {
        return Objects.requireNonNull(column, "column").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
