package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.Severity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 差异集合摘要。这里的每一条断言都在保护 T2：
 * <b>同一份数据跑两次，摘要必须一样；内容真变了，摘要必须不一样</b>。
 */
class DiffSetDigestTest {

    private static DiffRecord diff(long jobId, int shardId, String diffType, String bizKey, String field) {
        return DiffRecord.of(jobId, shardId, diffType, Severity.HIGH, bizKey, field,
                "10.00", "10.01", new BigDecimal("0.01"), null);
    }

    private static DiffRecord amount(String bizKey) {
        return diff(1L, 0, "AMOUNT_MISMATCH", bizKey, "amount");
    }

    private static DiffSetDigest digestOf(FieldType keyType, List<DiffRecord> diffs, int maxKeys) {
        DiffSetDigest.Accumulator accumulator = DiffSetDigest.accumulator(keyType, maxKeys);
        diffs.forEach(accumulator::accept);
        return accumulator.build().orElseThrow();
    }

    private static String sha(FieldType keyType, List<DiffRecord> diffs) {
        return digestOf(keyType, diffs, 1000).sha256();
    }

    @Test
    void digestIgnoresProducedOrder() {
        List<DiffRecord> produced = List.of(amount("B2"), amount("A1"),
                diff(1L, 0, "MISSING_LEFT", "C3", null));
        List<DiffRecord> reordered = List.of(produced.get(2), produced.get(0), produced.get(1));

        // 并行分片本来就无法保证产出顺序，摘要若能随顺序变，T2 会天天假失败
        assertThat(sha(FieldType.STRING, reordered)).isEqualTo(sha(FieldType.STRING, produced));
    }

    @Test
    void digestIgnoresJobAndShardIds() {
        List<DiffRecord> firstRun = List.of(diff(1L, 0, "AMOUNT_MISMATCH", "A1", "amount"));
        List<DiffRecord> secondRun = List.of(diff(9L, 3, "AMOUNT_MISMATCH", "A1", "amount"));

        // 两次运行必然是不同 jobId、分片归属也可能不同；摘要比的是内容，不是"哪次跑出来的"
        assertThat(sha(FieldType.STRING, secondRun)).isEqualTo(sha(FieldType.STRING, firstRun));
    }

    @Test
    void anyOfTypeKeyOrFieldChangeFlipsTheDigest() {
        String base = sha(FieldType.STRING, List.of(amount("A1")));

        assertThat(sha(FieldType.STRING, List.of(diff(1L, 0, "STATUS_MISMATCH", "A1", "amount"))))
                .as("差异类型变了").isNotEqualTo(base);
        assertThat(sha(FieldType.STRING, List.of(amount("A2"))))
                .as("业务键变了").isNotEqualTo(base);
        assertThat(sha(FieldType.STRING, List.of(diff(1L, 0, "AMOUNT_MISMATCH", "A1", "status"))))
                .as("字段变了").isNotEqualTo(base);
    }

    @Test
    void anyOfTheComparedValuesChangeFlipsTheDigest() {
        String base = sha(FieldType.STRING, List.of(amount("A1")));
        DiffRecord sameKeyDifferentValue = DiffRecord.of(1L, 0, "AMOUNT_MISMATCH", Severity.HIGH,
                "A1", "amount", "10.00", "10.05", new BigDecimal("0.05"), null);

        // 只比键集合时，"键一样、值全算错"在摘要上完全看不出来
        assertThat(sha(FieldType.STRING, List.of(sameKeyDifferentValue)))
                .as("左右取值变了").isNotEqualTo(base);
    }

    @Test
    void deltaScaleIsNormalizedSoTwoRunsOfTheSameDataAgree() {
        DiffRecord scaled = DiffRecord.of(1L, 0, "AMOUNT_MISMATCH", Severity.HIGH,
                "A1", "amount", "10.00", "10.01", new BigDecimal("0.0100"), null);

        // 1.0 与 1.00 是同一个数；不归一化会让同一份数据两次跑的摘要无端不同
        assertThat(sha(FieldType.STRING, List.of(scaled)))
                .isEqualTo(sha(FieldType.STRING, List.of(amount("A1"))));
    }

    @Test
    void duplicateLinesAreNotSwallowed() {
        DiffSetDigest once = digestOf(FieldType.STRING, List.of(amount("A1")), 1000);
        DiffSetDigest twice = digestOf(FieldType.STRING, List.of(amount("A1"), amount("A1")), 1000);

        // 用 Set 去重会让"多了一条一模一样的差异"彻底隐形
        assertThat(twice.count()).isEqualTo(2);
        assertThat(twice.sha256()).isNotEqualTo(once.sha256());
    }

    @Test
    void numericKeysAreNormalizedToTheSameKey() {
        // 与 ValueComparator.compareKeys 一致：LONG 下 100 与 100.00 是同一个键
        String integer = sha(FieldType.LONG, List.of(amount("100")));
        String scaled = sha(FieldType.LONG, List.of(amount("100.00")));

        assertThat(scaled).isEqualTo(integer);
    }

    @Test
    void stringKeysKeepTheirCase() {
        // 反例：归并是逐字符比的，"abc" 与 "ABC" 是两个不同的业务键。
        // 摘要若在这里转小写，就会把两条真实差异并成一条 —— 它是"变少"，比"变多"更难发现
        assertThat(sha(FieldType.STRING, List.of(amount("ABC"))))
                .isNotEqualTo(sha(FieldType.STRING, List.of(amount("abc"))));
    }

    @Test
    void separatorInContentCannotForgeAnotherLine() {
        // 不用长度前缀时 ("ab","c") 与 ("a","bc") 会拼成同一个串，摘要出现碰撞
        String left = sha(FieldType.STRING, List.of(diff(1L, 0, "T", "ab", "c")));
        String right = sha(FieldType.STRING, List.of(diff(1L, 0, "T", "a", "bc")));

        assertThat(right).isNotEqualTo(left);
    }

    @Test
    void countsGroupByTypeAndField() {
        DiffSetDigest digest = digestOf(FieldType.STRING, List.of(
                amount("A1"),
                amount("A2"),
                // 引擎实际写的是空串（MergeMatcher 的 emit* 传 ""），注释里说的"为空"两种都要认
                diff(1L, 0, "MISSING_LEFT", "A3", ""),
                diff(1L, 0, "MISSING_RIGHT", "A4", null)), 1000);

        assertThat(digest.count()).isEqualTo(4);
        assertThat(digest.byType()).containsExactly(
                Map.entry("AMOUNT_MISMATCH", 2L),
                Map.entry("MISSING_LEFT", 1L),
                Map.entry("MISSING_RIGHT", 1L));
        // 整条记录级差异单独归一组，既不能混进任何字段名，也不能落进一个空名字的分组
        assertThat(digest.byField()).containsExactly(
                Map.entry("(记录级)", 2L),
                Map.entry("amount", 2L));
    }

    @Test
    void recordLevelDiffHashesTheSameWhetherFieldIsNullOrBlank() {
        // 同一个语义不该因为""和null的区别算出两个摘要 —— 否则换个入口跑就是 T2 假失败
        assertThat(sha(FieldType.STRING, List.of(diff(1L, 0, "MISSING_LEFT", "A1", ""))))
                .isEqualTo(sha(FieldType.STRING, List.of(diff(1L, 0, "MISSING_LEFT", "A1", null))));
    }

    @Test
    void emptyDiffSetStillHasADigest() {
        DiffSetDigest digest = digestOf(FieldType.STRING, List.of(), 1000);

        // "这次一条差异都没有"本身就是一种需要被比较的基线，不能返回 null
        assertThat(digest.count()).isZero();
        assertThat(digest.sha256()).hasSize(64);
        assertThat(digest.byType()).isEmpty();
    }

    @Test
    void exceedingTheCapYieldsNoDigestInsteadOfAPartialOne() {
        DiffSetDigest.Accumulator accumulator = DiffSetDigest.accumulator(FieldType.STRING, 2);
        accumulator.accept(amount("A1"));
        accumulator.accept(amount("A2"));
        accumulator.accept(amount("A3"));

        assertThat(accumulator.overflowed()).isTrue();
        assertThat(accumulator.count()).as("超限后条数仍然如实累计").isEqualTo(3);
        // 关键：不是"前两条的摘要"，而是没有摘要。半份摘要看着完全正常，却会随数据顺序抖动
        assertThat(accumulator.build()).isEmpty();
    }

    @Test
    void zeroCapIsRejectedInsteadOfSilentlyDisablingTheDigest() {
        assertThatThrownBy(() -> DiffSetDigest.accumulator(FieldType.STRING, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxKeys");
    }

    @Test
    void sinkKeepsWritingDiffsAfterTheDigestGivesUp() {
        RecordingSink delegate = new RecordingSink();
        DigestingDiffSink sink = new DigestingDiffSink(delegate, DiffSetDigest.accumulator(FieldType.STRING, 1));

        sink.accept(amount("A1"));
        sink.accept(amount("A2"));
        sink.flush();

        // 摘要放弃 ≠ 差异少写：落库这条路上，摘要只是一个旁观者
        assertThat(delegate.accepted).extracting(DiffRecord::bizKey).containsExactly("A1", "A2");
        assertThat(delegate.flushes).isEqualTo(1);
        assertThat(sink.count()).isEqualTo(2);
        assertThat(sink.digest()).isEmpty();
    }

    /** 只记录"收到了几条"的最小出口，用来验证装饰器不会吞掉差异。 */
    private static final class RecordingSink implements DiffSink {
        private final List<DiffRecord> accepted = new ArrayList<>();
        private int flushes;

        @Override
        public void accept(DiffRecord diff) {
            accepted.add(diff);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public long count() {
            return accepted.size();
        }
    }
}
