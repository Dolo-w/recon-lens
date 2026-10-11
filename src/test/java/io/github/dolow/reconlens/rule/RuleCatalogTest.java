package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则库：classpath 的 YAML 只在库里没有该规则时导入一次（v1），之后规则的版本由库决定。
 * 三条要验证的关键行为：改规则<b>立刻生效</b>、旧版本<b>仍可回放</b>、任务跑起来之后
 * <b>钉住当时的规则</b>（重跑不会因为规则被改而换语义）。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class RuleCatalogTest {

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private ReconEngine engine;

    @Autowired
    private ReconRepository repository;

    @Test
    void classpathRulesAreImportedAsVersionOne() {
        RuleCatalog.Resolved bill = catalog.resolve("BILL_VS_SETTLE");

        assertThat(bill.version()).isEqualTo(1);
        assertThat(bill.snapshot()).contains("\"code\":\"BILL_VS_SETTLE\"");
        assertThat(bill.spec().left().table()).isEqualTo("bill_left");
        assertThat(catalog.codes()).contains("BILL_VS_SETTLE", "CUSTOMER_SUM_RECON");
    }

    @Test
    void missingRuleSaysWhatIsAvailable() {
        assertThatThrownBy(() -> catalog.resolve("NO_SUCH_RULE"))
                .hasMessageContaining("未找到规则 NO_SUCH_RULE")
                .hasMessageContaining("BILL_VS_SETTLE");
    }

    @Test
    void definingARuleTakesEffectImmediatelyAndKeepsHistory() {
        String code = "CATALOG_DIM_TEST";

        RuleCatalog.Resolved v1 = catalog.defineMap(ruleBody(code, "amount", "AMOUNT_MISMATCH"), "单测 v1", "tester");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(fields(catalog.resolve(code))).containsExactly("amount");

        RuleCatalog.Resolved v2 = catalog.defineMap(ruleBody(code, "status", "STATUS_MISMATCH"), "单测 v2", "tester");
        assertThat(v2.version()).isEqualTo(2);
        // 定义完立刻按新版本对账，不需要重启
        assertThat(fields(catalog.resolve(code))).containsExactly("status");
        // 旧版本没有被覆盖，还能按版本号取回来
        assertThat(fields(catalog.resolve(code, 1))).containsExactly("amount");
        assertThat(catalog.versions(code)).extracting(RuleRepository.RuleRow::version).containsExactly(2, 1);
        assertThat(catalog.versions(code)).extracting(RuleRepository.RuleRow::createdBy).containsExactly("tester", "tester");
        assertThat(catalog.versions(code)).extracting(RuleRepository.RuleRow::enabled).containsExactly(true, true);
    }

    @Test
    void inlineRuleRunsWithoutEnteringTheRuleLibrary() {
        RuleCatalog.Resolved inline = catalog.inline(ruleBody("INLINE_DIM_TEST", "amount", "AMOUNT_MISMATCH"), "单测内联");

        assertThat(inline.version()).isNull();
        assertThat(catalog.exists("INLINE_DIM_TEST")).isFalse();

        ReconJob job = engine.run(inline, "inline-it", "内联规则");
        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(job.leftRows()).isEqualTo(20);
        assertThat(job.rightRows()).isEqualTo(21);
        assertThat(repository.summaryByType(job.id()))
                .containsEntry("AMOUNT_MISMATCH", 2L)
                .doesNotContainKey("STATUS_MISMATCH");
        assertThat(repository.ruleVersion(job.id())).isNull();
    }

    @Test
    void jobsKeepTheRuleVersionTheyWereCreatedWith() {
        String code = "CATALOG_PIN_TEST";

        catalog.defineMap(ruleBody(code, "amount", "AMOUNT_MISMATCH"), "单测金额版", "tester");
        ReconJob first = engine.run(code, "pin-v1", "按金额对账");
        assertThat(first.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(repository.summaryByType(first.id())).containsEntry("AMOUNT_MISMATCH", 2L);
        assertThat(repository.ruleVersion(first.id())).isEqualTo(1);
        assertThat(repository.ruleSnapshot(first.id())).isPresent();

        // 改规则升到 v2：新任务按 v2，老任务重跑仍按 v1 的快照
        catalog.defineMap(ruleBody(code, "status", "STATUS_MISMATCH"), "单测状态版", "tester");
        ReconJob second = engine.run(code, "pin-v2", "按状态对账");
        assertThat(repository.summaryByType(second.id())).containsEntry("STATUS_MISMATCH", 1L);
        assertThat(repository.ruleVersion(second.id())).isEqualTo(2);

        ReconJob retried = engine.retry(first.id(), false);
        assertThat(retried.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(repository.summaryByType(first.id()))
                .containsEntry("AMOUNT_MISMATCH", 2L)
                .doesNotContainKey("STATUS_MISMATCH");
    }

    private List<String> fields(RuleCatalog.Resolved resolved) {
        return resolved.spec().compare().stream().map(FieldRule::field).toList();
    }

    /** 一条最小可用的规则体：单分片、一个比对字段，其余用演示数据。 */
    private Map<String, Object> ruleBody(String code, String field, String diffType) {
        Map<String, Object> left = Map.of("label", "账单", "table", "bill_left", "keyField", "order_no",
                "keyType", "STRING", "orderBy", "order_no");
        Map<String, Object> right = Map.of("label", "结算", "table", "bill_right", "keyField", "order_no",
                "keyType", "STRING", "orderBy", "order_no");
        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("field", field);
        compare.put("type", "amount".equals(field) ? "DECIMAL" : "STRING");
        compare.put("diffType", diffType);
        compare.put("severity", "HIGH");
        if ("amount".equals(field)) {
            compare.put("tolerance", "0.01");
        } else {
            compare.put("ignoreCase", true);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "规则库单测 " + code);
        body.put("left", left);
        body.put("right", right);
        body.put("shard", Map.of("count", 1, "parallelism", 1));
        body.put("compare", List.of(compare));
        body.put("missing", Map.of("leftMissingDiffType", "MISSING_LEFT",
                "rightMissingDiffType", "MISSING_RIGHT", "severity", "HIGH"));
        body.put("duplicate", Map.of("diffType", "DUPLICATE_KEY", "severity", "MEDIUM"));
        return body;
    }
}
