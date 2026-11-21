package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 凭证去重是分片级承诺：同一行左侧产出 3 个字段差异，凭证只落一份。 */
class BatchDiffSinkTest {

    /** 内存 DiffWriter：记录调用，不落库。 */
    private static final class MemoryWriter implements DiffWriter {
        final List<DiffRecord> diffs = new ArrayList<>();
        final List<DiffEvidence> evidence = new ArrayList<>();

        @Override
        public void insertDiffs(List<DiffRecord> diffs) {
            this.diffs.addAll(diffs);
        }

        @Override
        public void insertEvidence(List<DiffEvidence> evidence) {
            this.evidence.addAll(evidence);
        }
    }

    private static DiffRecord diff(String bizKey, String field) {
        return DiffRecord.of(1L, 0, "AMOUNT_MISMATCH", io.github.dolow.reconlens.domain.Severity.HIGH,
                bizKey, field, "1", "2", BigDecimal.ONE, null);
    }

    private static DiffEvidence evidence(String side, String bizKey) {
        return new DiffEvidence(1L, 0, side, bizKey, "bill_left", "{\"order_no\":\"" + bizKey + "\"}");
    }

    @Test
    void sameSideSameKeyAcrossMultipleDiffsIsWrittenOnce() {
        MemoryWriter writer = new MemoryWriter();
        BatchDiffSink sink = new BatchDiffSink(writer, 2);
        // 3 条差异共用同一左行凭证；右侧 2 条（等价重复）
        sink.accept(diff("A1", "amount"), List.of(evidence("LEFT", "A1"), evidence("RIGHT", "A1")));
        sink.accept(diff("A1", "status"), List.of(evidence("LEFT", "A1"), evidence("RIGHT", "A1")));
        sink.accept(diff("A1", "memo"), List.of(evidence("LEFT", "A1")));
        sink.flush();

        assertThat(writer.diffs).hasSize(3);
        assertThat(writer.evidence).extracting(DiffEvidence::side)
                .containsExactly("LEFT", "RIGHT");
        assertThat(sink.count()).isEqualTo(3);
    }

    @Test
    void evidenceFlushesTogetherWithItsDiffBatch() {
        MemoryWriter writer = new MemoryWriter();
        BatchDiffSink sink = new BatchDiffSink(writer, 1);
        sink.accept(diff("A1", "amount"), List.of(evidence("LEFT", "A1")));
        // batchSize=1：第一次 accept 就触发了 flush，凭证必须跟差异同一批出去
        assertThat(writer.diffs).hasSize(1);
        assertThat(writer.evidence).hasSize(1);
        sink.flush();
        assertThat(writer.diffs).hasSize(1);
    }

    @Test
    void diffWithoutEvidenceStillGetsWritten() {
        MemoryWriter writer = new MemoryWriter();
        BatchDiffSink sink = new BatchDiffSink(writer, 4);
        sink.accept(diff("A1", "amount"), List.of());
        sink.flush();
        assertThat(writer.diffs).hasSize(1);
        assertThat(writer.evidence).isEmpty();
    }

    @Test
    void rowJsonEscapesAndTruncates() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("memo", "带\"引号\"和\n换行\t制表");
        values.put("amount", new BigDecimal("1234.500"));
        Row row = new Row(values);
        String json = row.toJson(4000);
        assertThat(json).isEqualTo("{\"memo\":\"带\\\"引号\\\"和\\n换行\\t制表\",\"amount\":\"1234.5\"}");

        // 超长截断带标记，绝不静默丢半截没说法
        Map<String, Object> wide = new LinkedHashMap<>();
        wide.put("big", "x".repeat(5000));
        String truncated = new Row(wide).toJson(1000);
        assertThat(truncated).hasSize(1000).endsWith("…(已截断)");
    }
}
