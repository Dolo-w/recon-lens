package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 攒批写差异：差异量大时逐条 insert 会成为瓶颈，这里按 batchSize 攒批，
 * 同时把"单分片内存占用"限制在 batchSize 条以内。
 *
 * <p>凭证去重是<b>分片级</b>的：同一行左侧数据产出 3 个字段差异，凭证只存第一份
 * （唯一索引 (job, shard, side, biz_key) 兜底，这里先省掉重复流量）。set 与差异
 * 同量级，不会突破"单分片内存与数据总量无关"的承诺。
 */
public final class BatchDiffSink implements DiffSink {

    private final DiffWriter writer;
    private final int batchSize;
    private final List<DiffRecord> buffer;
    private final List<DiffEvidence> evidenceBuffer;
    /** 分片生命周期内的凭证去重：side|bizKey。 */
    private final Set<String> seenEvidence = new HashSet<>();

    private long count;

    public BatchDiffSink(DiffWriter writer, int batchSize) {
        this.writer = writer;
        this.batchSize = Math.max(1, batchSize);
        this.buffer = new ArrayList<>(Math.min(this.batchSize, 1024));
        this.evidenceBuffer = new ArrayList<>(Math.min(this.batchSize, 1024));
    }

    @Override
    public void accept(DiffRecord diff, List<DiffEvidence> evidence) {
        buffer.add(diff);
        if (evidence != null) {
            for (DiffEvidence e : evidence) {
                if (seenEvidence.add(e.side() + "|" + e.bizKey())) {
                    evidenceBuffer.add(e);
                }
            }
        }
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
        List<DiffEvidence> evidence = evidenceBuffer.isEmpty() ? List.of() : List.copyOf(evidenceBuffer);
        buffer.clear();
        evidenceBuffer.clear();
        writer.insertDiffs(batch);
        if (!evidence.isEmpty()) {
            writer.insertEvidence(evidence);
        }
    }

    @Override
    public long count() {
        return count;
    }
}
