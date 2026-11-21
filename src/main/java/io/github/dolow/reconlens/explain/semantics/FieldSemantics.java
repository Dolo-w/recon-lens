package io.github.dolow.reconlens.explain.semantics;

import java.util.Map;

/**
 * 一个字段的语义标注（K3 字段语义字典的最小单元）。
 *
 * <p><b>为什么不是一列自由文本</b>：SchemaCatalog 只知道"这列叫 amount、类型 DECIMAL"，
 * 模型写规则时只能猜"它是含税还是净额、单位是元还是分"。语义标注要能变成
 * <b>硬判定</b>（两侧口径冲突 WARN），就必须把可判定的维度拆成独立字段——
 * {@code caliber}（含税/不含税）与 {@code unit}（元/分）是两个能机械比对的维度；
 * {@code semantic} 是给人看的一句解释；{@code enum} 给状态列提供取值含义。
 *
 * @param table      表名（小写归一）
 * @param column     列名（小写归一）
 * @param semantic   一句人话解释（给模型/审核人）
 * @param caliber    口径（如 含税/不含税/单边），两侧 caliber 不同 = 口径冲突
 * @param unit       单位（如 元/分/百万 token），两侧 unit 不同 = 口径冲突
 * @param enumMeanings 枚举取值 → 含义（状态列用）
 */
public record FieldSemantics(String table, String column, String semantic,
                             String caliber, String unit, Map<String, String> enumMeanings) {

    public FieldSemantics {
        table = table == null ? "" : table.trim().toLowerCase();
        column = column == null ? "" : column.trim().toLowerCase();
        enumMeanings = enumMeanings == null ? Map.of() : Map.copyOf(enumMeanings);
    }

    /** 口径是否可机械比对（两侧都有 caliber 或 unit 才比，只有一边知道口径不构成冲突证据）。 */
    public boolean comparable() {
        return (caliber != null && !caliber.isBlank()) || (unit != null && !unit.isBlank());
    }
}
