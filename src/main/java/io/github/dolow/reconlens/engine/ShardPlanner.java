package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.dsl.ShardSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片计划 + 取数 SQL 生成。
 * <p>分片的意义：把一个大对账拆成 N 个可以并行、可以单独重试、单独续跑的小任务；
 * 每个分片内部的取数仍然靠 ORDER BY + 游标保持内存可控。
 *
 * <p>两种分片形态：
 * <ul>
 *   <li><b>模板分片</b>（默认）：规则里写 {@code whereLeft/whereRight} 模板，引擎只做占位符替换 ——
 *       灵活，但 {@code MOD(CRC32(...))} 这类表达式谓词走不了索引，必然全表扫；</li>
 *   <li><b>范围分片</b>（{@code shard.rangeKey}）：引擎探两侧该列的 MIN/MAX 均分后生成
 *       {@code col >= lo AND col < hi}（末段 {@code <= hi}）—— 纯"列与常量比较"，能走主键/索引。
 *       两侧用合并后的同一组边界，同源约束由构造保证。</li>
 * </ul>
 */
public final class ShardPlanner {

    private ShardPlanner() {
    }

    /** 模板分片。rangeKey 规则必须走 {@link #plan(RuleSpec, long[][])}。 */
    public static List<ShardPlan> plan(RuleSpec rule) {
        return plan(rule, null);
    }

    /**
     * @param rangeBounds 范围分片的边界：{@code bounds[i] = {lo, hi}}，两侧共用同一组；
     *                    模板分片传 {@code null}
     */
    public static List<ShardPlan> plan(RuleSpec rule, long[][] rangeBounds) {
        ShardSpec shard = rule.shard();
        int count = shard.count();
        List<ShardPlan> plans = new ArrayList<>(count);
        if (shard.rangeKey() != null && !shard.rangeKey().isBlank()) {
            if (rangeBounds == null || rangeBounds.length != count) {
                throw new IllegalStateException("shard.rangeKey 分片需要先探测两侧列边界（ShardPlanner.plan(rule, bounds)）");
            }
            for (int index = 0; index < count; index++) {
                String where = rangeWhere(shard.rangeKey(), rangeBounds[index][0], rangeBounds[index][1], index == count - 1);
                plans.add(new ShardPlan(index, where, where));
            }
            return plans;
        }
        for (int index = 0; index < count; index++) {
            plans.add(new ShardPlan(index,
                    render(shard.whereLeft(), count, index),
                    render(shard.whereRight(), count, index)));
        }
        return plans;
    }

    /**
     * 范围谓词。半开区间 {@code [lo, hi)} 保证相邻分片不重叠 —— 否则边界值会被两个分片各扫一次，
     * 在 MergeMatcher 眼里就是凭空的 DUPLICATE_KEY。
     */
    static String rangeWhere(String column, long lo, long hi, boolean last) {
        return column + " >= " + lo + " AND " + column + (last ? " <= " + hi : " < " + hi);
    }

    static String render(String where, int shards, int index) {
        if (where == null || where.isBlank()) {
            return null;
        }
        return where.replace("{shards}", String.valueOf(shards)).replace("{index}", String.valueOf(index));
    }

    /** 生成取数 SQL：外层套一层是为了让自定义 SQL 与单表两种写法共用同一段分片条件。 */
    public static String selectSql(SideSpec side, String where) {
        return selectSql(side, where, SideSpec.KEY_ALIAS);
    }

    /**
     * @param keyOrder 排序表达式。派生维度的键列在 MySQL 的 ci 排序下与 Java 比较器不同序，
     *                 由 {@link KeyOrder} 给出 utf16_bin 版本；H2 上就是裸列名。
     */
    public static String selectSql(SideSpec side, String where, String keyOrder) {
        String filter = combine(side, where);
        if (!side.derivedKey()) {
            StringBuilder sql = new StringBuilder(128);
            sql.append("SELECT * FROM (").append(side.sourceSelect()).append(") src");
            if (filter != null) {
                sql.append(" WHERE ").append(filter);
            }
            sql.append(" ORDER BY ").append(side.orderBy());
            return sql.toString();
        }
        // 声明式维度：分片条件作用在原始数据上，再投影出统一的业务键列；
        // 聚合维度还要按维度表达式 GROUP BY（此时只产出维度 + measures，因为分组后原始列已经没有意义）。
        String inner = side.sourceSelect();
        if (filter != null) {
            inner = "SELECT * FROM (" + inner + ") src WHERE " + filter;
        }
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT ").append(side.keyExpression()).append(" AS ").append(SideSpec.KEY_ALIAS);
        if (side.aggregated()) {
            side.measures().forEach((alias, expression) -> sql.append(", ").append(expression).append(" AS ").append(alias));
            sql.append(" FROM (").append(inner).append(") src GROUP BY ").append(side.keyExpression());
        } else {
            sql.append(", src.* FROM (").append(inner).append(") src");
        }
        return sql.append(" ORDER BY ").append(keyOrder).toString();
    }

    /**
     * 规则里两侧各自可以写一个静态过滤条件（left.where / right.where），它与分片的 where 是「与」的关系：
     * 例如「只对未作废的单据对账」写在 side.where，「按业务键取模切片」写在 shard.whereLeft/Right。
     */
    private static String combine(SideSpec side, String shardWhere) {
        String sideWhere = side.where();
        boolean hasShard = shardWhere != null && !shardWhere.isBlank();
        boolean hasSide = sideWhere != null && !sideWhere.isBlank();
        if (hasShard && hasSide) {
            return "(" + shardWhere + ") AND (" + sideWhere + ")";
        }
        if (hasShard) {
            return shardWhere;
        }
        return hasSide ? sideWhere : null;
    }
}
