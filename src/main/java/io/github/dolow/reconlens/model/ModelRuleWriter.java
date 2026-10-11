package io.github.dolow.reconlens.model;

import io.github.dolow.reconlens.dsl.DuplicateSpec;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.MissingSpec;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.ShardSpec;
import io.github.dolow.reconlens.dsl.SideSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"读原始表"的规则改写成"读模型表"的规则 —— 这是模型表能被引擎直接使用的关键：
 * 取数、比较、归因一行代码都不用改，改的只是这份配置。
 *
 * <p>改写后两侧都退化成最简单的那种写法（单表 + keyField=biz_key），因为模型表在构建时就已经是
 * 规则声明的粒度了：聚合维度已经汇总过，原始侧的 where 也已经生效过，所以这里不能再带
 * dimension / measures / where。比对字段、差异类型、容差、重试次数原样保留，结果才和原规则一致。
 */
final class ModelRuleWriter {

    private ModelRuleWriter() {
    }

    static Map<String, Object> write(RuleSpec spec, String modelRuleCode, String leftTable, String rightTable, int buckets) {
        Map<String, Object> rule = new LinkedHashMap<>();
        String name = spec.name() == null || spec.name().isBlank() ? spec.code() : spec.name();
        rule.put("code", modelRuleCode);
        rule.put("name", name + "（模型表）");
        rule.put("description", "由 recon_build_model 依据规则 " + spec.code() + " 生成，读预处理模型表；比对字段与差异类型与原规则一致。");
        rule.put("left", side(spec.left(), leftTable));
        rule.put("right", side(spec.right(), rightTable));
        rule.put("shard", shard(spec.shard(), buckets));
        rule.put("compare", compare(spec.compare()));
        rule.put("missing", missing(spec.missing()));
        rule.put("duplicate", duplicate(spec.duplicate()));
        rule.put("options", Map.of("maxAttempts", spec.maxAttempts()));
        return rule;
    }

    private static Map<String, Object> side(SideSpec source, String table) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("label", source.label());
        side.put("table", table);
        side.put("keyField", ModelDdl.KEY_COLUMN);
        // 键类型沿用它，比较语义才和原规则一模一样（模型表的列类型就是源列的类型）
        side.put("keyType", source.keyType().name());
        return side;
    }

    private static Map<String, Object> shard(ShardSpec source, int buckets) {
        Map<String, Object> shard = new LinkedHashMap<>();
        shard.put("count", buckets);
        shard.put("parallelism", source.effectiveParallelism());
        if (buckets > 1) {
            // 桶列是生成列 + 索引，等值定位即可；`< {shards}` 是桶号的天然上界（bucket 由 mod/ORA_HASH 算出来），
            // 顺带满足解析器"两个占位符都要有"的契约 —— 原规则的取模写法需要它，我们这条不需要，但写出来不为错。
            String where = ModelDdl.BUCKET_COLUMN + " = {index} AND " + ModelDdl.BUCKET_COLUMN + " < {shards}";
            shard.put("whereLeft", where);
            shard.put("whereRight", where);
        }
        return shard;
    }

    private static List<Map<String, Object>> compare(List<FieldRule> fields) {
        List<Map<String, Object>> compare = new ArrayList<>(fields.size());
        for (FieldRule field : fields) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field.field());
            item.put("type", field.type().name());
            item.put("diffType", field.diffType());
            item.put("severity", field.severity().name());
            if (field.ignoreCase()) {
                item.put("ignoreCase", true);
            }
            if (field.tolerance() != null) {
                item.put("tolerance", field.tolerance());
            }
            if (field.tolerancePercent() != null) {
                item.put("tolerancePercent", field.tolerancePercent());
            }
            compare.add(item);
        }
        return compare;
    }

    private static Map<String, Object> missing(MissingSpec source) {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("leftMissingDiffType", source.leftMissingDiffType());
        missing.put("rightMissingDiffType", source.rightMissingDiffType());
        missing.put("severity", source.severity().name());
        return missing;
    }

    private static Map<String, Object> duplicate(DuplicateSpec source) {
        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("diffType", source.diffType());
        duplicate.put("severity", source.severity().name());
        return duplicate;
    }
}
