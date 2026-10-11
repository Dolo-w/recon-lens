package io.github.dolow.reconlens.domain;

import java.math.BigDecimal;

/**
 * 一条差异。field 为空表示整条记录级别的差异（单边缺失 / 重复键），
 * 非空表示某个字段值不一致。
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
        String explanation) {

    public static DiffRecord of(long jobId, int shardId, String diffType, Severity severity, String bizKey,
                                String field, String leftValue, String rightValue,
                                BigDecimal deltaValue, BigDecimal deltaRatio) {
        return new DiffRecord(null, jobId, shardId, diffType, severity, bizKey, field,
                leftValue, rightValue, deltaValue, deltaRatio, null);
    }

    public DiffRecord withExplanation(String text) {
        return new DiffRecord(id, jobId, shardId, diffType, severity, bizKey, field,
                leftValue, rightValue, deltaValue, deltaRatio, text);
    }
}
