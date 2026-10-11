package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片计划 + 取数 SQL 生成。
 * <p>分片的意义：把一个大对账拆成 N 个可以并行、可以单独重试、单独续跑的小任务；
 * 每个分片内部的取数仍然靠 ORDER BY + 游标保持内存可控。
 */
public final class ShardPlanner {

    private ShardPlanner() {
    }

    public static List<ShardPlan> plan(RuleSpec rule) {
        int count = rule.shard().count();
        List<ShardPlan> plans = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            plans.add(new ShardPlan(index,
                    render(rule.shard().whereLeft(), count, index),
                    render(rule.shard().whereRight(), count, index)));
        }
        return plans;
    }

    static String render(String where, int shards, int index) {
        if (where == null || where.isBlank()) {
            return null;
        }
        return where.replace("{shards}", String.valueOf(shards)).replace("{index}", String.valueOf(index));
    }

    /** 生成取数 SQL：外层套一层是为了让自定义 SQL 与单表两种写法共用同一段分片条件。 */
    public static String selectSql(SideSpec side, String where) {
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
        return sql.append(" ORDER BY ").append(SideSpec.KEY_ALIAS).toString();
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
