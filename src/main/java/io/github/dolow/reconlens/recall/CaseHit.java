package io.github.dolow.reconlens.recall;

/** 一条命中的案例记忆。{@code score} 已经算过时间衰减，直接用它排序。 */
public record CaseHit(CaseMemory memory, double score) {
}
