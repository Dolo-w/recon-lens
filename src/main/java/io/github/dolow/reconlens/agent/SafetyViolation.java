package io.github.dolow.reconlens.agent;

/**
 * 闸门 1 的一条违规。结构化是为了<b>直接喂回 LLM 做自我修正</b>：
 * 只有一句中文报错时，模型只能猜；带着 {@code location} 与 {@code suggestion}，它才知道改哪一行。
 *
 * @param code       违规类型（稳定，可作为断言与统计口径）
 * @param location   规则体里的位置，如 {@code left.table} / {@code compare[2].tolerancePercent} / {@code shard.whereRight}
 * @param message    哪里不对
 * @param suggestion 该怎么改
 */
public record SafetyViolation(ViolationCode code, String location, String message, String suggestion) {

    /** 违规类型。<b>名字就是对外口径</b>，落库与回喂 LLM 都靠它，不要改拼写。 */
    public enum ViolationCode {

        /** 表不在白名单，或白名单里有但数据源里查不到。 */
        UNKNOWN_TABLE,

        /** 引用了该侧取数列里不存在的列。 */
        UNKNOWN_COLUMN,

        /** SQL 不是只读（非 SELECT/WITH 开头，或含 DML/DDL 关键字）。 */
        SQL_NOT_READ_ONLY,

        /** 多语句：除了结尾的一个分号，中间还出现分号。 */
        MULTI_STATEMENT,

        /** 两侧分片口径确定不一致：同一个业务键会被切到不同分片。 */
        SHARD_SPLIT_KEY,

        /**
         * 分片数超过上限。
         *
         * <p>分片是<b>重跑单位</b>，不是"越大越快"：{@code shard.count: 100000} 会开出一千个任务行、
         * 一千个线程池任务，而差异表里每条差异还要带 shard 号。这不是性能问题，是把重跑粒度做废了。
         */
        SHARD_COUNT,

        /** 使用了目标库不存在的函数（如 MySQL 没有 HASH()）。试算阶段才报错的，闸门 1 就该拦下。 */
        NON_PORTABLE_FUNCTION,

        /** 容差被放大超过基线倍数。 */
        TOLERANCE_INFLATED,

        /**
         * 容差超过<b>绝对上限</b>（与基线无关）。
         *
         * <p>为什么单独一条：{@link #TOLERANCE_INFLATED} 是"相对基线"的判定，而 Agent 起草的
         * <b>首版规则没有基线</b> —— 只靠倍数校验，{@code tolerancePercent: 90} 可以直接通过。
         * "多大的差异可以不算差异"这件事，必须有一个不依赖历史的硬上限。
         */
        TOLERANCE_ABSOLUTE_LIMIT,

        /** 严重级别被降级。 */
        SEVERITY_DOWNGRADED,

        /** 比对字段集合被缩小（且没有人工豁免）。 */
        FIELD_REMOVED,

        /**
         * 人为注入的违规：只由 {@code recon.agent.safety.force-draft-violation=true} 产生。
         *
         * <p>为什么需要它：闸门 1 拒绝之后的链路（回炉 re-plan → 只做归因 → PARTIAL）在真模型下
         * <b>打不出来</b> —— 模型被拒绝一次后几乎总能自我纠正，实测三条触发路都被它修好了。
         * 没有这条注入，"被拒之后会怎样"就只能靠单测断言，不能在生产形态的服务上验一遍。
         */
        TEST_FORCED
    }

    @Override
    public String toString() {
        return code + " @" + location + ": " + message + " → " + suggestion;
    }
}
