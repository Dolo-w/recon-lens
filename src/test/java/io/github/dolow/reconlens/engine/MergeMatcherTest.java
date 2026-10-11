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

        @Override
        public void accept(DiffRecord diff) {
            diffs.add(diff);
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
    void duplicateKeyIsReportedOnceAndStillCompared() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(
                List.of(row("A1", "10", "PAID"), row("A1", "99", "PAID")),
                List.of(row("A1", "10", "PAID"), row("A1", "95", "PAID")),
                sink);

        Map<String, Long> byType = countByType(sink.diffs);
        // 同键多条的差异只报一条（否则差异表会被重复数据淹没），但组内仍然逐条比对
        assertThat(byType).containsEntry("DUPLICATE_KEY", 1L);
        assertThat(byType).containsEntry("AMOUNT_MISMATCH", 1L);
        assertThat(stats.matchedRows()).isEqualTo(2);
    }

    @Test
    void emptyBothSidesIsClean() {
        CollectingSink sink = new CollectingSink();
        MatchStats stats = match(List.of(), List.of(), sink);

        assertThat(sink.diffs).isEmpty();
        assertThat(stats.matchedRows()).isZero();
    }
}
