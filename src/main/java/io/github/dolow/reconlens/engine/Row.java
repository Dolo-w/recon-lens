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

    /**
     * 整行的紧凑 JSON 视图（差异原始凭证用）。值格式与 {@link #render} 同一口径
     * （BigDecimal 去6零避免科学计数法）。超过 {@code maxChars} 整体截断并带标记 ——
     * 凭证是给人复盘的，不是无损备份：宽行截断比把 DB 顶爆更重要。
     */
    public String toJson(int maxChars) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            sb.append(v == null ? "null" : quote(v instanceof BigDecimal bd
                    ? bd.stripTrailingZeros().toPlainString() : String.valueOf(v)));
        }
        sb.append('}');
        if (sb.length() > maxChars) {
            // 标记占位按真实长度算，保证总量精确 ≤ maxChars
            return sb.substring(0, maxChars - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
        }
        return sb.toString();
    }

    static final String TRUNCATION_MARKER = "…(已截断)";

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String normalize(String column) {
        return Objects.requireNonNull(column, "column").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
