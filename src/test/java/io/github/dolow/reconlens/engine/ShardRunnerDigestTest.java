package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 把摘要接到分片执行路径上之后的两个行为：<b>正常时摘要跟着结果走</b>、
 * <b>超上限时摘要放弃但差异照写</b>。
 * <p>接线错了在 accumulator 的单测里看不出来 —— 只有在这一层才发现"配置改了、行为没变"。
 */
class ShardRunnerDigestTest {

    private final RuleSpec rule = TestRules.bill();

    @Test
    void digestCountMatchesWhatWasWritten() {
        ShardRun run = execute(100);

        assertThat(run.result.digest()).isNotNull();
        assertThat(run.result.digest().count()).isEqualTo(run.written.size());
        assertThat(run.result.digest().byType())
                .containsEntry("AMOUNT_MISMATCH", 1L)
                .containsEntry("MISSING_RIGHT", 1L);
    }

    @Test
    void overflowStillWritesEveryDiffButLeavesNoDigest() {
        ShardRun run = execute(1);

        assertThat(run.written).as("摘要放弃不等于差异少写").hasSize(2);
        assertThat(run.result.digest()).as("超过上限就不给摘要，不给'半份'").isNull();
        assertThat(run.result.stats().diffRows()).isEqualTo(2);
    }

    /**
     * 两行左侧、一行右侧：A1 金额差 0.5、A5 右侧缺失。
     *
     * <p>摘要只在<b>试算路径</b>上产出（生产跑批算完就扔，见 {@code ShardRunner} 的注释），
     * 所以这里必须传 {@link DiffCapture} 才会触发摘要。
     */
    private ShardRun execute(int maxKeys) {
        ReconProperties properties = new ReconProperties();
        properties.getEngine().setDiffDigestMaxKeys(maxKeys);

        ReconRepository repository = mock(ReconRepository.class);
        Row leftA1 = row("ORD20260001", "10.00", "PAID");
        Row leftA5 = row("ORD20260005", "50.00", "PAID");
        Row rightA1 = row("ORD20260001", "10.50", "PAID");

        ShardRunner runner = new ShardRunner(
                new StubCursors(rule, List.of(leftA1, leftA5), List.of(rightA1)), repository, properties);
        ShardResult result = runner.run(rule, 1L, new ShardPlan(0, null, null), new DiffCapture());

        ArgumentCaptor<List<DiffRecord>> batches = ArgumentCaptor.forClass(List.class);
        verify(repository, atLeastOnce()).insertDiffs(batches.capture());
        List<DiffRecord> written = batches.getAllValues().stream().flatMap(List::stream).toList();
        return new ShardRun(result, written);
    }

    private record ShardRun(ShardResult result, List<DiffRecord> written) {
    }

    private static Row row(String orderNo, String amount, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("order_no", orderNo);
        values.put("amount", new BigDecimal(amount));
        values.put("status", status);
        return new Row(values);
    }

    /** 用内存游标替换 JDBC：分片执行逻辑之外的部分都不该参与这个测试。 */
    private static final class StubCursors extends ReconCursors {

        private final RuleSpec rule;
        private final List<Row> left;
        private final List<Row> right;

        private StubCursors(RuleSpec rule, List<Row> left, List<Row> right) {
            super(null, new ReconProperties(), new KeyOrder(null));
            this.rule = rule;
            this.left = left;
            this.right = right;
        }

        @Override
        public RowCursor open(SideSpec side, String where) {
            return new ListRowCursor(side == rule.left() ? left : right);
        }
    }
}
