package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 攒批写差异：差异量大时逐条 insert 会成为瓶颈，这里按 batchSize 攒批，
 * 同时把"单分片内存占用"限制在 batchSize 条以内。
 */
public final class BatchDiffSink implements DiffSink {

    private final DiffWriter writer;
    private final int batchSize;
    private final List<DiffRecord> buffer;

    private long count;

    public BatchDiffSink(DiffWriter writer, int batchSize) {
        this.writer = writer;
        this.batchSize = Math.max(1, batchSize);
        this.buffer = new ArrayList<>(Math.min(this.batchSize, 1024));
    }

    @Override
    public void accept(DiffRecord diff) {
        buffer.add(diff);
        count++;
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<DiffRecord> batch = List.copyOf(buffer);
        buffer.clear();
        writer.insertDiffs(batch);
    }

    @Override
    public long count() {
        return count;
    }
}
