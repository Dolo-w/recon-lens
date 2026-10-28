package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.store.RuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 闸门 3：草稿不可见、审批绑内容哈希、跳过试算只能用于非语义变更。
 *
 * <p>这些用例的共同点：<b>它们拦的都是"流程上合法"的操作</b> ——
 * 一次正常的 approve、一次正常的"跳过试算"、一次正常的 resolve。
 * 危险不来自非法调用，而来自"每一步都合规，但合起来绕过了闸门"。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class RuleLifecycleServiceTest {

    private static final TrialState PASS_FULL = new TrialState.Trialed(TrialVerdict.PASS, Coverage.FULL, List.of());
    private static final TrialState FAIL_FULL = new TrialState.Trialed(TrialVerdict.FAIL, Coverage.FULL, List.of());

    @Autowired
    private RuleLifecycleService service;

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private RuleRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final RuleParser parser = new RuleParser();

    private Map<String, Object> body(String code, String name, String tolerance) {
        String yaml = """
                code: %s
                name: %s
                left: {label: L, table: bill_left, keyField: order_no, keyType: STRING, orderBy: order_no}
                right: {label: R, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no}
                shard: {count: 1, parallelism: 1}
                compare:
                  - {field: amount, type: DECIMAL, tolerance: %s, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(code, name, tolerance);
        return parser.readYaml(yaml, "单测");
    }

    private int draft(String code, String name, String tolerance, Integer baseVersion) {
        return catalog.defineDraft(body(code, name, tolerance), "单测", "alice",
                RuleOrigin.AGENT, baseVersion, "单测草稿").version();
    }

    /**
     * 模拟闸门 2 把结论落进 {@code recon_rule.trial_*}。
     *
     * <p>注意这里用的是 {@link RuleRepository#saveTrial} 这个<b>唯一写口</b>：
     * 审批侧没有别的路径能造出"PASS + FULL"，这正是 M2 收掉的口子。
     */
    private void seedTrial(String code, int version, TrialState state) {
        repository.saveTrial(code, version, state, "sha256:test", "{}",
                SpecHasher.sha256(repository.findByVersion(code, version).orElseThrow().specJson()));
    }

    @Test
    void 草稿对业务不可见_批准后才生效() {
        String code = "LIFECYCLE_FLOW_TEST";
        int version = draft(code, "第一版", "0.01", null);

        // 消息要说"存在但没生效"，而不是"未找到"：人刚看到草稿落库，回头执行却被告知
        // 规则不存在，会去查一个根本不存在的错（RuleCatalog#notRunnable）。
        assertThatThrownBy(() -> catalog.resolve(code))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("存在（v" + version + "，状态 DRAFT）")
                .hasMessageContaining("没有可以运行的生效版本");

        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);
        seedTrial(code, version, PASS_FULL);
        service.approve(code, version, "bob", token, null);

        assertThat(catalog.resolve(code).version()).isEqualTo(version);
        assertThat(repository.findByVersion(code, version).orElseThrow().reviewedBy()).isEqualTo("bob");
        assertThat(repository.findByVersion(code, version).orElseThrow().reviewedSpecSha()).isNotBlank();
    }

    @Test
    void 有草稿时整个规则不能从列表里消失() {
        String code = "LIFECYCLE_LIST_TRAP_TEST";
        catalog.defineMap(body(code, "生效版", "0.01"), "单测", "alice");
        draft(code, "草稿版", "0.01", 1);

        // listLatest 的内层子查询如果漏掉 lifecycle，会先选出 DRAFT 的 v2、外层再按 ACTIVE 一滤 → 整个 code 消失
        assertThat(catalog.codes()).contains(code);
        assertThat(catalog.allResolved())
                .anySatisfy(resolved -> {
                    assertThat(resolved.spec().code()).isEqualTo(code);
                    assertThat(resolved.version()).isEqualTo(1);
                });
    }

    @Test
    void 审批后被改过的内容会被降级() {
        String code = "LIFECYCLE_TAMPER_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);
        seedTrial(code, version, PASS_FULL);
        service.approve(code, version, "bob", token, null);

        String tampered = repository.findByVersion(code, version).orElseThrow().specJson()
                .replace("0.01", "10");
        jdbc.update("UPDATE recon_rule SET spec_json = ? WHERE code = ? AND version = ?",
                tampered, code, version);

        assertThatThrownBy(() -> catalog.resolve(code))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("reviewed_spec_sha");
        assertThat(repository.findByVersion(code, version).orElseThrow().lifecycle())
                .isEqualTo(RuleLifecycle.REVIEWING);
    }

    @Test
    void 审批token绑定内容_内容变了token就失效() {
        String code = "LIFECYCLE_TOKEN_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);
        seedTrial(code, version, PASS_FULL);

        String tampered = repository.findByVersion(code, version).orElseThrow().specJson()
                .replace("0.01", "5");
        jdbc.update("UPDATE recon_rule SET spec_json = ? WHERE code = ? AND version = ?",
                tampered, code, version);

        assertThatThrownBy(() -> service.approve(code, version, "bob", token, null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("审批 token 无效");
    }

    /**
     * 这一步比"token 绑定内容"更隐蔽：<b>每一步都是合法调用</b>。
     *
     * <pre>
     *   trial() → PASS × FULL
     *   UPDATE spec_json = '...'        ← DBA 热修 / 回放导入
     *   submit → REVIEWING
     *   issueToken                      ← 绑定的是【改后】的内容，所以 token 校验必然通过
     *   approve                         ← findTrial 读到的是改之前的 PASS
     * </pre>
     *
     * token 钉的是"审批那一刻"，trial_spec_sha 钉的是"试算那一刻"，两个时刻缺一不可。
     */
    @Test
    void 试算之后改过内容_旧结论不能拿来批准() {
        String code = "LIFECYCLE_TRIAL_STALE_TEST";
        int version = draft(code, "第一版", "0.01", null);
        seedTrial(code, version, PASS_FULL);          // 试算跑的是 0.01 那一版

        String tampered = repository.findByVersion(code, version).orElseThrow().specJson()
                .replace("0.01", "10");                // 试算之后把容差放大
        jdbc.update("UPDATE recon_rule SET spec_json = ? WHERE code = ? AND version = ?",
                tampered, code, version);

        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);   // token 绑的是改后内容 → 这一步合法

        assertThatThrownBy(() -> service.approve(code, version, "bob", token, "我接受"))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("试算结论不是在当前内容上跑出来的");
        assertThat(repository.findByVersion(code, version).orElseThrow().lifecycle())
                .as("拒绝之后版本必须还停在 REVIEWING，不能半生效")
                .isEqualTo(RuleLifecycle.REVIEWING);
    }

    /** 跳过试算同样要绑定内容：它也是"结论"，也会被后面的改动甩在身后。 */
    @Test
    void 跳过试算之后改过内容_也不能直接批准() {
        String code = "LIFECYCLE_SKIP_STALE_TEST";
        catalog.defineMap(body(code, "旧名字", "0.01"), "单测", "alice");
        int version = draft(code, "新名字", "0.01", 1);

        service.submit(code, version, "alice");
        service.skipTrial(code, version, "bob", "只改了展示名");

        String tampered = repository.findByVersion(code, version).orElseThrow().specJson()
                .replace("新名字", "又改了一个名字");
        jdbc.update("UPDATE recon_rule SET spec_json = ? WHERE code = ? AND version = ?",
                tampered, code, version);

        String token = service.issueToken(code, version);
        assertThatThrownBy(() -> service.approve(code, version, "bob", token, null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("试算结论不是在当前内容上跑出来的");
    }

    @Test
    void 只改名字可以跳过试算() {        String code = "LIFECYCLE_SKIP_OK_TEST";
        catalog.defineMap(body(code, "旧名字", "0.01"), "单测", "alice");
        int version = draft(code, "新名字", "0.01", 1);

        service.submit(code, version, "alice");
        service.skipTrial(code, version, "bob", "只改了展示名");

        // 跳过是一个独立动作，审核记录上留痕：看得出"这一版没验过、是决定不验"
        assertThat(repository.findTrial(code, version).orElseThrow().verdict()).isEqualTo("SKIPPED");
        assertThat(repository.findTrial(code, version).orElseThrow().skipReason()).isEqualTo("只改了展示名");

        String token = service.issueToken(code, version);
        service.approve(code, version, "bob", token, null);   // Skipped → NO_SIGNOFF_REQUIRED，不需要签字
        assertThat(catalog.resolve(code).version()).isEqualTo(version);
    }

    @Test
    void 改了容差就不能跳过试算() {
        String code = "LIFECYCLE_SKIP_DENIED_TEST";
        catalog.defineMap(body(code, "同名", "0.01"), "单测", "alice");
        int version = draft(code, "同名", "10", 1);

        service.submit(code, version, "alice");

        // 资格由服务端字段级 diff 判定 —— "我只是想跳过"这句话本身没有任何效力
        assertThatThrownBy(() -> service.skipTrial(code, version, "bob", "只是想跳过"))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("SKIPPED_NOT_ALLOWED");
        assertThat(repository.findTrial(code, version).orElseThrow().verdict()).isNull();
    }

    @Test
    void 跳过试算必须写理由() {
        String code = "LIFECYCLE_SKIP_REASON_TEST";
        catalog.defineMap(body(code, "旧名字", "0.01"), "单测", "alice");
        int version = draft(code, "新名字", "0.01", 1);
        service.submit(code, version, "alice");

        // 没有理由的"跳过"在审核页上和"还没跑"长得一模一样
        assertThatThrownBy(() -> service.skipTrial(code, version, "bob", "  "))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("必须写理由");
    }

    @Test
    void 试算失败硬拦_谁也不能批准() {
        String code = "LIFECYCLE_FAIL_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);
        seedTrial(code, version, FAIL_FULL);

        assertThatThrownBy(() -> service.approve(code, version, "bob", token, "我接受"))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("硬拦");
    }

    @Test
    void 没跑试算不能批准_签字也不行() {
        String code = "LIFECYCLE_NOT_RUN_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);

        assertThatThrownBy(() -> service.approve(code, version, "bob", token, "我接受"))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("还没跑试算");
    }

    @Test
    void 样本不完整必须签字_签了才能放行() {
        String code = "LIFECYCLE_SIGN_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");
        String token = service.issueToken(code, version);
        seedTrial(code, version, new TrialState.Trialed(TrialVerdict.PASS, Coverage.SAMPLE_PARTIAL,
                List.of(CoverageReason.SHARD_COVERAGE)));

        assertThatThrownBy(() -> service.approve(code, version, "bob", token, null))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("需要签字");

        service.approve(code, version, "bob", token, "接受样本只覆盖单分片");
        assertThat(catalog.resolve(code).version()).isEqualTo(version);
    }

    @Test
    void 驳回后不可见_且必须写理由() {
        String code = "LIFECYCLE_REJECT_TEST";
        int version = draft(code, "第一版", "0.01", null);
        service.submit(code, version, "alice");

        assertThatThrownBy(() -> service.reject(code, version, "bob", "  "))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("驳回必须写理由");

        service.reject(code, version, "bob", "口径不对");
        assertThat(repository.findByVersion(code, version).orElseThrow().lifecycle())
                .isEqualTo(RuleLifecycle.REJECTED);
        assertThatThrownBy(() -> catalog.resolve(code)).isInstanceOf(RuleException.class);
    }

    @Test
    void 审批必须带操作人身份() {
        String code = "LIFECYCLE_OPERATOR_TEST";
        int version = draft(code, "第一版", "0.01", null);

        assertThatThrownBy(() -> service.submit(code, version, " "))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("X-Operator");
    }
}
