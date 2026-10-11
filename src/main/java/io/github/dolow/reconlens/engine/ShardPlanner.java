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
        StringBuilder sql = new StringBuilder(128);
        sql.append("SELECT * FROM (").append(side.sourceSelect()).append(") src");
        if (where != null && !where.isBlank()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY ").append(side.orderBy());
        return sql.toString();
    }
}
