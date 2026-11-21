package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 归并式对账的核心行为：等你、等我、重复键、字段比对。 */
class MergeMatcherTest {

    private final RuleSpec rule = TestRules.bill();

    /** 收集差异的测试出口 */
    private static final class CollectingSink implements DiffSink {
        private final List<DiffRecord> diffs = new ArrayList<>();
        private final java.util.List<io.github.dolow.reconlens.domain.DiffEvidence> evidences = new ArrayList<>();

        @Override
        public void accept(DiffRecord diff, java.util.List<io.github.dolow.reconlens.domain.DiffEvidence> evidence) {
            diffs.add(diff);
            evidences.addAll(evidence);
        }

        @Override
        public void flush() {
        }

        @Override
        public long count() {
            return diffs.size();
        }
    }

    private static Row row(String orderNo, String amount, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_no", orderNo);
        values.put("amount", new BigDecimal(amount));
        values.put("status", status);
        return new Row(values);
    }

    private MatchStats match(List<Row> left, List<Row> right, CollectingSink sink) {
        try (RowCursor leftCursor = new ListRowCursor(left);
             RowCursor rightCursor = new ListRowCursor(right)) {
            return new MergeMatcher(rule, 1L, 0).match(leftCursor, rightCursor, sink);
        }
    }

    private static Map<String, Long> countByType(List<DiffRecord> diffs) {
        return diffs.stream().collect(Collectors.groupingBy(DiffRecord::diffType, Collectors.counting()));
    }

    @Test
    void equalKeysWithSameValuesProduceNoDiff() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(List.of(row("A1", "10.00", "PAID")), List.of(row("A1", "10.00", "paid")), sink);

        assertThat(sink.diffs).isEmpty();
        assertThat(stats.matchedRows()).isEqualTo(1);
        assertThat(stats.scannedLeft()).isEqualTo(1);
        assertThat(stats.scannedRight()).isEqualTo(1);
    }

    @Test
    void amountBeyondToleranceIsReportedWithFieldAndDelta() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "3000", "PAID")), List.of(row("A1", "2999", "PAID")), sink);

        assertThat(sink.diffs).hasSize(1);
        DiffRecord diff = sink.diffs.get(0);
        assertThat(diff.diffType()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(diff.field()).isEqualTo("amount");
        assertThat(diff.bizKey()).isEqualTo("A1");
        assertThat(diff.leftValue()).isEqualTo("3000");
        assertThat(diff.rightValue()).isEqualTo("2999");
        assertThat(diff.deltaValue()).isEqualByComparingTo("1");
        assertThat(diff.severity()).isEqualTo(io.github.dolow.reconlens.domain.Severity.HIGH);
    }

    @Test
    void fieldDiffCarriesBothSidesEvidenceAndDedupesAcrossFields() {
        CollectingSink sink = new CollectingSink();
        // 两个字段都不同 → 2 条差异；同一条左行的凭证只存一份（side|bizKey 去重）
        match(List.of(row("A1", "3000", "PAID")), List.of(row("A1", "2999", "REFUNDED")), sink);

        assertThat(sink.diffs).hasSize(2);
        // 匹配器层每条字段差异都带两侧凭证；同键去重在 BatchDiffSink 做（见它的专属测试）
        var left = sink.evidences.stream().filter(e -> e.side().equals("LEFT")).toList();
        var right = sink.evidences.stream().filter(e -> e.side().equals("RIGHT")).toList();
        assertThat(left).hasSize(2);
        assertThat(right).hasSize(2);
        assertThat(left.get(0).bizKey()).isEqualTo("A1");
        assertThat(left.get(0).source()).contains("bill_left");
        // 凭证是整行快照：不参与比对的列也在里面
        assertThat(left.get(0).rowJson()).contains("\"order_no\":\"A1\"").contains("\"amount\":\"3000\"");
        assertThat(right.get(0).rowJson()).contains("\"status\":\"REFUNDED\"");
    }

    @Test
    void missingDiffCarriesOnlyThePresentSideEvidence() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10", "PAID")), List.of(), sink);
        assertThat(sink.diffs).hasSize(1);
        assertThat(sink.evidences).hasSize(1);
        assertThat(sink.evidences.get(0).side()).isEqualTo("LEFT");

        CollectingSink sink2 = new CollectingSink();
        match(List.of(), List.of(row("B1", "10", "PAID")), sink2);
        assertThat(sink2.evidences).hasSize(1);
        assertThat(sink2.evidences.get(0).side()).isEqualTo("RIGHT");
        assertThat(sink2.evidences.get(0).bizKey()).isEqualTo("B1");
    }

    @Test
    void duplicateDiffCarriesFirstRowOfEachSideAsEvidence() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10", "PAID"), row("A1", "11", "PAID")),
              List.of(row("A1", "10", "PAID")), sink);
        assertThat(sink.diffs.get(0).diffType()).isEqualTo("DUPLICATE_KEY");
        var sides = sink.evidences.stream().map(e -> e.side()).toList();
        assertThat(sides).containsExactly("LEFT", "RIGHT");
    }

    @Test
    void equalKeysProduceNoEvidence() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10.00", "PAID")), List.of(row("A1", "10.00", "paid")), sink);
        assertThat(sink.diffs).isEmpty();
        assertThat(sink.evidences).isEmpty();
    }

    @Test
    void statusComparisonFollowsIgnoreCase() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10", "UNPAID")), List.of(row("A1", "10", "PAID")), sink);

        assertThat(sink.diffs).hasSize(1);
        assertThat(sink.diffs.get(0).diffType()).isEqualTo("STATUS_MISMATCH");
        assertThat(sink.diffs.get(0).field()).isEqualTo("status");
    }

    @Test
    void keyOnlyOnLeftIsRightMissing() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(
                List.of(row("A1", "10", "PAID"), row("A2", "20", "PAID")),
                List.of(row("A2", "20", "PAID")),
                sink);

        assertThat(countByType(sink.diffs)).containsExactly(Map.entry("MISSING_RIGHT", 1L));
        assertThat(sink.diffs.get(0).bizKey()).isEqualTo("A1");
        assertThat(stats.matchedRows()).isEqualTo(1);
    }

    @Test
    void keyOnlyOnRightIsLeftMissing() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10", "PAID")), List.of(row("A1", "10", "PAID"), row("A9", "90", "PAID")), sink);

        assertThat(countByType(sink.diffs)).containsExactly(Map.entry("MISSING_LEFT", 1L));
        assertThat(sink.diffs.get(0).bizKey()).isEqualTo("A9");
    }

    @Test
    void bothSidesTailRemainderIsReported() {
        CollectingSink sink = new CollectingSink();
        match(List.of(row("A1", "10", "PAID"), row("A5", "50", "PAID")),
                List.of(row("A1", "10", "PAID"), row("A3", "30", "PAID")), sink);

        Map<String, Long> byType = countByType(sink.diffs);
        assertThat(byType).containsEntry("MISSING_RIGHT", 1L).containsEntry("MISSING_LEFT", 1L);
        assertThat(sink.diffs).extracting(DiffRecord::bizKey).containsExactlyInAnyOrder("A5", "A3");
    }

    @Test
    void duplicateKeyIsReportedOnceAndNotPositionallyPaired() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(
                List.of(row("A1", "10", "PAID"), row("A1", "99", "PAID")),
                List.of(row("A1", "10", "PAID"), row("A1", "95", "PAID")),
                sink);

        Map<String, Long> byType = countByType(sink.diffs);
        // 同键多条的差异只报一条（否则差异表会被重复数据淹没）。
        // 不再做组内按位配对：组内行序依赖数据库返回顺序（排序键相同时不是确定性的），
        // 按位凑对会让同一份数据两次跑出不同的字段差异集 —— 幂等被打破。
        // 重复键本身就是数据问题，先修数据再谈字段比对。
        assertThat(byType).containsEntry("DUPLICATE_KEY", 1L);
        assertThat(byType).doesNotContainKey("AMOUNT_MISMATCH");
        assertThat(stats.matchedRows()).isZero();
    }

    @Test
    void duplicateKeyGroupOrderDoesNotChangeTheResult() {
        // 顺序无关性验收：游标按键有序是归并的契约，键序打乱不是合法输入；
        // 能乱的是<b>同一键组内部</b>的行序（SQL 对相同排序键的返回顺序本就不确定）。
        // 组内顺序打乱后，差异集必须完全一致 —— 这正是"不按位配对"要保证的。
        CollectingSink first = new CollectingSink();
        match(List.of(row("A1", "10", "PAID"), row("A1", "99", "PAID"), row("B2", "20", "PAID")),
                List.of(row("A1", "10", "PAID"), row("A1", "95", "PAID"), row("B2", "20", "PAID")), first);
        CollectingSink second = new CollectingSink();
        match(List.of(row("A1", "99", "PAID"), row("A1", "10", "PAID"), row("B2", "20", "PAID")),
                List.of(row("A1", "95", "PAID"), row("A1", "10", "PAID"), row("B2", "20", "PAID")), second);

        assertThat(second.diffs.size()).isEqualTo(first.diffs.size());
        assertThat(countByType(second.diffs)).isEqualTo(countByType(first.diffs));
        assertThat(second.diffs).extracting(DiffRecord::bizKey)
                .containsExactlyInAnyOrderElementsOf(first.diffs.stream().map(DiffRecord::bizKey).toList());
    }

    @Test
    void emptyBothSidesIsClean() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(List.of(), List.of(), sink);

        assertThat(sink.diffs).isEmpty();
        assertThat(stats.matchedRows()).isZero();
    }

    @Test
    void sameSampleProducesTheSameDigestTwice() {
        List<Row> left = List.of(row("A1", "10", "PAID"), row("A2", "20", "PAID"), row("A5", "50", "PAID"));
        List<Row> right = List.of(row("A1", "10.00", "PAID"), row("A2", "21", "PAID"), row("A3", "30", "PAID"));

        DiffSetDigest first = digestOf(left, right);
        DiffSetDigest second = digestOf(left, right);

        // T2 的最小形态：同一份样本跑两次，摘要必须一致（这里连 jobId 都是同一个，
        // 真正验收时两次运行 jobId 不同 —— 摘要不含 jobId/shardId，所以不受影响）
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(second.byType()).isEqualTo(first.byType());
        assertThat(first.byType())
                .containsEntry("AMOUNT_MISMATCH", 1L)
                .containsEntry("MISSING_RIGHT", 1L)
                .containsEntry("MISSING_LEFT", 1L);
    }

    private DiffSetDigest digestOf(List<Row> left, List<Row> right) {
        DigestingDiffSink sink = new DigestingDiffSink(new CollectingSink(),
                DiffSetDigest.accumulator(rule.left().keyType(), DiffSetDigest.DEFAULT_MAX_KEYS));
        try (RowCursor leftCursor = new ListRowCursor(left);
             RowCursor rightCursor = new ListRowCursor(right)) {
            new MergeMatcher(rule, 1L, 0).match(leftCursor, rightCursor, sink);
        }
        return sink.digest().orElseThrow();
    }
}
