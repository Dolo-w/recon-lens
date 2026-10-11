package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.List;

/**
 * 差异落库接口。放在 engine 包里由持久层反向实现，这样引擎不依赖 JdbcTemplate，
 * 单元测试可以直接给一个内存实现。
 */
public interface DiffWriter {

    void insertDiffs(List<DiffRecord> diffs);
}
