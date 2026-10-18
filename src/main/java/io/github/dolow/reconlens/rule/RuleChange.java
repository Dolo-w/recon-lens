package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 两版规则之间的<b>字段级差异</b>，以及"这次改动算不算触及安全语义"。
 *
 * <p>为什么"属于哪一类"必须由代码判定（第四轮 9.1-③）：如果把"我只是改个名字"这句话交给提草稿的人自述，
 * 那这个开关立刻退化成"给自己写个理由就能绕过闸门 2"。所以这里把 {@link RuleSpec} 摊平成一张
 * "路径 → 值"的表，只认机械比较的结果：
 * <ul>
 *   <li>{@code name} / {@code description} 之外的任何变化都算<b>语义变更</b>（含 {@code options.maxAttempts}，
 *       它影响重试，宁可多要一次签字）；</li>
 *   <li>{@code name} / {@code description} 变化 → {@code changedPaths} 为空、{@code semantic=false}。</li>
 * </ul>
 *
 * <p>这张表同时是"规则 diff"给审核人看的数据源：审核人不该看到两坨 YAML 自己找不同。
 */
public record RuleChange(boolean semantic, Set<String> changedPaths) {

    public RuleChange {
        changedPaths = changedPaths == null ? Set.of() : Set.copyOf(changedPaths);
    }

    /** 基线为 null（新规则）时，任何内容都算"第一次定义"，不参与"放大/缩小"判定。 */
    public static RuleChange between(RuleSpec baseline, RuleSpec candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (baseline == null) {
            return new RuleChange(!candidate.compare().isEmpty(), Set.of("(新规则)"));
        }
        Map<String, String> left = flatten(baseline);
        Map<String, String> right = flatten(candidate);
        Set<String> changed = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        for (String key : keys) {
            if (!Objects.equals(left.get(key), right.get(key))) {
                changed.add(key);
            }
        }
        return new RuleChange(!changed.isEmpty(), changed);
    }

    /** 这次改动只碰了展示字段（名字 / 描述），可以申请跳过试算。 */
    public boolean skippable() {
        return !semantic;
    }

    private static Map<String, String> flatten(RuleSpec spec) {
        Map<String, String> flat = new TreeMap<>();
        side(flat, "left", spec.left());
        side(flat, "right", spec.right());

        flat.put("shard.count", String.valueOf(spec.shard().count()));
        flat.put("shard.parallelism", String.valueOf(spec.shard().parallelism()));
        flat.put("shard.whereLeft", nullable(spec.shard().whereLeft()));
        flat.put("shard.whereRight", nullable(spec.shard().whereRight()));
        flat.put("shard.rangeKey", nullable(spec.shard().rangeKey()));

        for (FieldRule rule : spec.compare()) {
            String path = "compare." + rule.field().toUpperCase(Locale.ROOT);
            flat.put(path + ".type", String.valueOf(rule.type()));
            flat.put(path + ".ignoreCase", String.valueOf(rule.ignoreCase()));
            flat.put(path + ".tolerance", decimal(rule.tolerance()));
            flat.put(path + ".tolerancePercent", decimal(rule.tolerancePercent()));
            flat.put(path + ".diffType", nullable(rule.diffType()));
            flat.put(path + ".severity", String.valueOf(rule.severity()));
        }

        flat.put("missing.leftMissingDiffType", nullable(spec.missing().leftMissingDiffType()));
        flat.put("missing.rightMissingDiffType", nullable(spec.missing().rightMissingDiffType()));
        flat.put("missing.severity", String.valueOf(spec.missing().severity()));
        flat.put("duplicate.diffType", nullable(spec.duplicate().diffType()));
        flat.put("duplicate.severity", String.valueOf(spec.duplicate().severity()));

        // 故意不写 name / description：它们不是安全语义，写进 changedPaths 会让"改个文案"需要签字。
        flat.put("options.maxAttempts", String.valueOf(spec.maxAttempts()));
        return flat;
    }

    private static void side(Map<String, String> flat, String prefix, SideSpec side) {
        flat.put(prefix + ".table", nullable(side.table()));
        flat.put(prefix + ".sql", nullable(side.sql()));
        flat.put(prefix + ".keyField", nullable(side.keyField()));
        flat.put(prefix + ".keyType", String.valueOf(side.keyType()));
        flat.put(prefix + ".where", nullable(side.where()));
        flat.put(prefix + ".dimension", nullable(side.dimension()));
        flat.put(prefix + ".dimensionColumns", String.join(",", side.dimensionColumns()));
        // measures 是 Map，按 key 排序后再比：顺序不是语义
        flat.put(prefix + ".measures", new TreeMap<>(side.measures()).toString());
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "(null)" : value.trim();
    }

    /** 0.10 与 0.1 是同一个容差；用字符串比会凭空报"改过"。 */
    private static String decimal(BigDecimal value) {
        return value == null ? "(null)" : value.stripTrailingZeros().toPlainString();
    }
}
