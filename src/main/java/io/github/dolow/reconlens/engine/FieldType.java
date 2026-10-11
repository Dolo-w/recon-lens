package io.github.dolow.reconlens.engine;

/** 支持的字段类型。规则 DSL 里用字符串书写，解析时归一到这里。 */
public enum FieldType {
    STRING,
    LONG,
    DECIMAL,
    BOOLEAN;

    public static FieldType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return STRING;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的字段类型: " + raw + "（可选 STRING/LONG/DECIMAL/BOOLEAN）");
        }
    }
}
