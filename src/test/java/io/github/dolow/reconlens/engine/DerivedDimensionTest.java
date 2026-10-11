package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 声明式对账维度：使用方只声明「一笔业务靠什么区分」（dimension）与聚合口径（measures），
 * 引擎一侧完全不知道维度长什么样 —— 生成 SQL 时统一收敛到 {@code __biz_key} 键列。
 * 这里验证它和手写 keyField 等价、聚合维度真的先分组再比对、表达式维度原样下发。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class DerivedDimensionTest {

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private ReconEngine engine;

    @Autowired
    private ReconRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dimensionOnAUniqueColumnBehavesLikeTheClassicKeyFieldRule() {
        ReconJob classic = engine.run("BILL_VS_SETTLE", "dim-classic", "手写业务键");
        ReconJob derived = run(inline("DIM_ORDER_EQUIV", List.of("order_no"), Map.of(), List.of("amount", "status")));

        assertThat(derived.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(derived.leftRows()).isEqualTo(classic.leftRows()).isEqualTo(20);
        assertThat(derived.rightRows()).isEqualTo(classic.rightRows()).isEqualTo(21);
        assertThat(derived.matchedRows()).isEqualTo(classic.matchedRows());
        assertThat(derived.diffRows()).isEqualTo(classic.diffRows());
        assertThat(repository.summaryByType(derived.id())).isEqualTo(repository.summaryByType(classic.id()));
        assertThat(repository.summaryBySeverity(derived.id())).isEqualTo(repository.summaryBySeverity(classic.id()));
    }

    @Test
    void aggregatedDimensionGroupsRowsBeforeComparing() {
        ReconJob job = run(inline("DIM_CUSTOMER_SUM", List.of("customer_id"), Map.of("amount", "SUM(amount)"),
                List.of("amount")));

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        // 分组发生在取数阶段：扫描行数 = 客户数，而不是订单数
        assertThat(job.leftRows()).isEqualTo(distinct("bill_left", "customer_id"));
        assertThat(job.rightRows()).isEqualTo(distinct("bill_right", "customer_id"));
        assertThat(job.leftRows()).isLessThan(20);
        assertThat(repository.summaryByType(job.id())).containsKey("AMOUNT_MISMATCH");
        assertThat(repository.listDiffs(job.id(), null, 0, 200))
                .allSatisfy(diff -> assertThat(diff.bizKey()).startsWith("C"));
    }

    @Test
    void expressionDimensionIsPassedThroughVerbatim() {
        ReconJob job = run(inline("DIM_EXPR_TEST", "CONCAT(customer_id, '#', status)", Map.of(),
                List.of("amount", "status")));

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        // 没配 measures 时不分组：表达式维度只是换了个业务键，行数与原表一致
        assertThat(job.leftRows()).isEqualTo(rows("bill_left"));
        assertThat(job.rightRows()).isEqualTo(rows("bill_right"));
    }

    @Test
    void perSideStaticFilterAppliesBeforeMatching() {
        Map<String, Object> body = inlineBody("DIM_FILTER_TEST", List.of("order_no"), Map.of(), List.of("amount"));
        body.put("right", side("结算", "bill_right", List.of("order_no"), Map.of(), "status = 'PAID'"));

        ReconJob job = run(catalog.inline(body, "维度单测 DIM_FILTER_TEST"));

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        // 两侧各自可以写一个静态过滤条件：右侧只对 PAID 的结算单对账，其余行在取数阶段就被丢掉
        assertThat(job.leftRows()).isEqualTo(rows("bill_left"));
        assertThat(job.rightRows()).isEqualTo(countWhere("bill_right", "status = 'PAID'"));
        assertThat(job.rightRows()).isLessThan(rows("bill_right"));
    }

    private ReconJob run(RuleCatalog.Resolved resolved) {
        return engine.run(resolved, resolved.spec().code(), "维度单测");
    }

    private long distinct(String table, String expression) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT " + expression + ") FROM " + table, Long.class);
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countWhere(String table, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class);
    }

    /** 只声明维度，其余（比对字段、缺失、重复）与演示规则保持一致。 */
    private RuleCatalog.Resolved inline(String code, Object dimension, Map<String, String> measures, List<String> fields) {
        return catalog.inline(inlineBody(code, dimension, measures, fields), "维度单测 " + code);
    }

    private Map<String, Object> inlineBody(String code, Object dimension, Map<String, String> measures, List<String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "维度单测 " + code);
        body.put("left", side("账单", "bill_left", dimension, measures));
        body.put("right", side("结算", "bill_right", dimension, measures));
        body.put("shard", Map.of("count", 1, "parallelism", 1));
        body.put("compare", fields.stream().map(this::field).toList());
        body.put("missing", Map.of("leftMissingDiffType", "MISSING_LEFT",
                "rightMissingDiffType", "MISSING_RIGHT", "severity", "HIGH"));
        body.put("duplicate", Map.of("diffType", "DUPLICATE_KEY", "severity", "MEDIUM"));
        return body;
    }

    private Map<String, Object> side(String label, String table, Object dimension, Map<String, String> measures) {
        return side(label, table, dimension, measures, null);
    }

    private Map<String, Object> side(String label, String table, Object dimension, Map<String, String> measures,
                                    String where) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("label", label);
        side.put("table", table);
        side.put("dimension", dimension);
        if (!measures.isEmpty()) {
            side.put("measures", measures);
        }
        if (where != null) {
            side.put("where", where);
        }
        return side;
    }

    private Map<String, Object> field(String name) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("field", name);
        if ("amount".equals(name)) {
            rule.put("type", "DECIMAL");
            rule.put("tolerance", "0.01");
            rule.put("diffType", "AMOUNT_MISMATCH");
            rule.put("severity", "HIGH");
        } else {
            rule.put("type", "STRING");
            rule.put("ignoreCase", true);
            rule.put("diffType", "STATUS_MISMATCH");
            rule.put("severity", "MEDIUM");
        }
        return rule;
    }
}
