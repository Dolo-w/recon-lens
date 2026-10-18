package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.dsl.RuleException;import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.store.RuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "审批后改内容"必须在缓存命中时也能抓到。
 *
 * <p>早先 {@code resolve} 命中 {@code latestCache} 就直接返回，于是这条检测只在进程刚启动、
 * 缓存还没热的那一瞬间有效 —— 实测：先 GET 一次规则把缓存焐热，再 {@code UPDATE spec_json}，
 * 之后 {@code POST /api/jobs} 仍然 SUCCESS，跑的还是被改过的内容。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rule_tamper_cache;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right"
})
class RuleTamperCacheTest {

    private static final String CODE = "TAMPER_CACHE_TEST";

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private RuleLifecycleService lifecycle;

    @Autowired
    private RuleTrialRunner trialRunner;

    @Autowired
    private RuleRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private int version;

    @BeforeEach
    void setUp() {
        Map<String, Object> body = new RuleParser().readYaml("""
                code: TAMPER_CACHE_TEST
                name: 篡改缓存测试
                left:
                  label: 账单
                  table: bill_left
                  keyType: STRING
                  dimension: [customer_id, order_no]
                right:
                  label: 结算
                  table: bill_right
                  keyType: STRING
                  dimension: [customer_id, order_no]
                shard:
                  count: 1
                  parallelism: 1
                compare:
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.01
                    diffType: AMOUNT_MISMATCH
                    severity: HIGH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                  severity: HIGH
                """, "单测");
        version = catalog.defineDraft(body, "单测", "alice", RuleOrigin.HUMAN, null, "篡改用例").version();
        trialRunner.run(CODE, version);
        lifecycle.confirmApproval(CODE, version, "APPROVED", "wang",
                lifecycle.issueConfirmation(CODE, version, "APPROVE", "wang").code(), "审批");
        // 把缓存焐热：之后命中 latestCache，走的正是"缓存命中"这条分支
        catalog.resolve(CODE);
    }

    @Test
    void 审批后改掉内容_缓存命中也要降级并拒绝() {
        jdbc.update("UPDATE recon_rule SET spec_json = REPLACE(spec_json, 'amount', 'amount_x') "
                + "WHERE code = ? AND version = ?", CODE, version);

        assertThatThrownBy(() -> catalog.resolve(CODE))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("内容在审批后被修改");

        assertThat(repository.findByVersion(CODE, version).orElseThrow().lifecycle())
                .isEqualTo(RuleLifecycle.REVIEWING);
    }
}
