package io.github.dolow.reconlens.domain;

import java.math.BigDecimal;

/**
 * 一条差异。field 为空表示整条记录级别的差异（单边缺失 / 重复键），
 * 非空表示某个字段值不一致。
 *
 * <p>{@code eventAt} 是这条差异的<b>业务发生时刻</b>（原样字符串，来自规则里配置的
 * {@code eventTime} 列，可能为空——没配或该侧行里没有值）。为什么存字符串而不是
 * Instant：来源列的类型由使用方的表决定（DATETIME/DATE/文本），引擎不做时区翻译，
 * 原样落库、计价时尽力解析，解析不了就回退任务完成时刻——宁可回退也不猜。
 */
public record DiffRecord(
        Long id,
        long jobId,
        int shardId,
        String diffType,
        Severity severity,
        String bizKey,
        String field,
        String leftValue,
        String rightValue,
        BigDecimal deltaValue,
        BigDecimal deltaRatio,
        String explanation,
        String eventAt) {

    public static DiffRecord of(long jobId, int shardId, String diffType, Severity severity, String bizKey,
                                String field, String leftValue, String rightValue,
                                BigDecimal deltaValue, BigDecimal deltaRatio) {
        return of(jobId, shardId, diffType, severity, bizKey, field, leftValue, rightValue,
                deltaValue, deltaRatio, null);
    }

    /** 带<b>业务发生时刻</b>的构造：事件级对账（价格按业务发生时刻匹配）用。 */
    public static DiffRecord of(long jobId, int shardId, String diffType, Severity severity, String bizKey,
                                String field, String leftValue, String rightValue,
                                BigDecimal deltaValue, BigDecimal deltaRatio, String eventAt) {
        return new DiffRecord(null, jobId, shardId, diffType, severity, bizKey, field,
                leftValue, rightValue, deltaValue, deltaRatio, null, eventAt);
    }

    /** 不带事件时间的兼容构造器（事件级对账之前的老调用点/测试用）。 */
    public DiffRecord(Long id, long jobId, int shardId, String diffType, Severity severity, String bizKey,
                      String field, String leftValue, String rightValue,
                      BigDecimal deltaValue, BigDecimal deltaRatio, String explanation) {
        this(id, jobId, shardId, diffType, severity, bizKey, field,
                leftValue, rightValue, deltaValue, deltaRatio, explanation, null);
    }

    public DiffRecord withExplanation(String text) {
        return new DiffRecord(id, jobId, shardId, diffType, severity, bizKey, field,
                leftValue, rightValue, deltaValue, deltaRatio, text, eventAt);
    }
}
