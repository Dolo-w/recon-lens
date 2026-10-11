package io.github.dolow.reconlens.domain;

/** 任务状态机：PENDING → RUNNING → SUCCESS / PARTIAL_FAILED / FAILED */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_FAILED,
    FAILED;

    public boolean finished() {
        return this == SUCCESS || this == PARTIAL_FAILED || this == FAILED;
    }
}
