package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.GateHealthRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import io.github.dolow.reconlens.store.TrialSampleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 2 的端到端验收：真的建样本表、真的跑两次引擎、真的改数据做 canary、真的落库。
 *
 * <p>这些断言刻意不 mock 引擎 —— 一个只在 mock 上通过的闸门，等于没有闸门。
 * 数据来自 {@code data.sql} 的 20 行演示账单，阈值全部调小到测试规模。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:trial_gate;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right",
        "recon.trial.window-rows=500",
        "recon.trial.min-sample-rows=5",
        "recon.trial.min-common-keys=5",
        "recon.trial.min-shards-with-data=2"
})
class ShadowTrialServiceTest {

    @Autowired
    private ShadowTrialService trials;

    @Autowired
    private RuleCatalog catalog;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private GateHealthRepository gateHealth;

    @Autowired
    private ReconRepository reconRepository;

    @Autowired
    private ReconProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SampleMaterializer materializer;

    @Autowired
    private SampleWindowProbe probe;

    @Autowired
    private TrialSampleRepository leases;

    private int originalMinSampleRows;

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = LOWER(?)",
                Integer.class, table);
        return count != null && count > 0;
    }

    private int draft(String code) {
        Map<String, Object> root = new RuleParser()
                .readYaml(TestRules.YAML.replace("code: T_BILL", "code: " + code), "test.yml");
        RuleCatalog.Resolved resolved = catalog.defineDraft(root, "test.yml", "tester",
                RuleOrigin.HUMAN, null, "端到端测试草稿");
        return resolved.version();
    }

    private String gateVersion() {
        return GateVersion.of(properties.getTrial());
    }

    /** 两侧都用 {@code sql:} 且自带 {@code LIMIT}：物化能成功，但物化到的是"截断后的一角"。 */
    private int draftWithLimit(String code, int rows) {
        String yaml = """
                code: %s
                name: 上游截断
                left:
                  label: 账单
                  sql: SELECT * FROM bill_left ORDER BY order_no LIMIT %d
                  keyField: order_no
                  keyType: STRING
                  orderBy: order_no
                right:
                  label: 结算
                  sql: SELECT * FROM bill_right ORDER BY order_no LIMIT %d
                  keyField: order_no
                  keyType: STRING
                  orderBy: order_no
                shard:
                  count: 4
                  parallelism: 2
                  whereLeft: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
                  whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
                compare:
                  - {field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH}
                missing: {leftMissingDiffType: MISSING_LEFT, rightMissingDiffType: MISSING_RIGHT, severity: HIGH}
                duplicate: {diffType: DUPLICATE_KEY, severity: MEDIUM}
                """.formatted(code, rows, rows);
        Map<String, Object> root = new RuleParser().readYaml(yaml, "test.yml");
        return catalog.defineDraft(root, "test.yml", "tester", RuleOrigin.HUMAN, null,
                "上游截断测试草稿").version();
    }

    @AfterEach
    void restoreConfig() {
        if (originalMinSampleRows > 0) {
            properties.getTrial().setMinSampleRows(originalMinSampleRows);
            originalMinSampleRows = 0;
        }
        // 每个用例都可能把闸门标成不可信；下一轮从干净状态开始，免得用例之间互相影响
        gateHealth.clear(gateVersion());
    }

    @Test
    void passesOnAHealthySampleAndProvesTheGate() {
        int version = draft("TRIAL_OK");
        ShadowTrialService.TrialResult result = trials.trial("TRIAL_OK", version);

        TrialState.Trialed state = (TrialState.Trialed) result.state();
        assertThat(state.verdict()).isEqualTo(TrialVerdict.PASS);
        assertThat(state.coverage()).isEqualTo(Coverage.FULL);
        assertThat(state.approval()).isEqualTo(ApprovalMode.NO_SIGNOFF_REQUIRED);

        // 证据体：T0/T1/T2/T5 都要有结论，而且 canary 必须真的"检出了"
        assertThat(result.report().checks()).containsKeys("T0", "T1", "T2", "T5-a", "T5-b", "T5-c");
        assertThat(result.report().checks().get("T0")).isEqualTo("PASS");
        assertThat(result.report().checks().get("T2")).isEqualTo("PASS");
        assertThat(result.report().canary().passed()).isTrue();
        assertThat(result.report().canary().detail()).contains("检出");

        // 落库：六列是一个整体，读回来必须还原出同一个状态
        RuleRepository.TrialRecord stored = ruleRepository.findTrial("TRIAL_OK", version).orElseThrow();
        assertThat(stored.verdict()).isEqualTo("PASS");
        assertThat(stored.coverage()).isEqualTo("FULL");
        assertThat(stored.digest()).isNotBlank();
        assertThat(stored.report()).contains("\"checks\"");
        assertThat(stored.state()).isInstanceOf(TrialState.Trialed.class);

        // 闸门被这一版自检证明了可信 → 下次试算不必再自检
        assertThat(gateHealth.isTrusted(gateVersion())).isTrue();
    }

    @Test
    void sampleTablesAreAlwaysDropped() {
        int version = draft("TRIAL_CLEANUP");
        trials.trial("TRIAL_CLEANUP", version);

        Integer leftovers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) LIKE 'trial!_%' ESCAPE '!'",
                Integer.class);
        assertThat(leftovers).as("样本表必须在 finally 里删掉，进程活着时不该留残留").isZero();
    }

    @Test
    void trialJobsStayOutOfProductionViews() {
        int version = draft("TRIAL_ISOLATION");
        trials.trial("TRIAL_ISOLATION", version);

        List<Map<String, Object>> trialJobs = jdbc.queryForList(
                "SELECT id FROM recon_job WHERE rule_code = ? AND kind = 'TRIAL'", "TRIAL_ISOLATION");
        assertThat(trialJobs).as("两次 T2 + canary 至少 3 个 TRIAL 任务").hasSizeGreaterThanOrEqualTo(3);

        assertThat(reconRepository.listJobs(1000))
                .as("任务列表只列生产任务：试算混进来会让人误以为它在上生产跑过")
                .noneMatch(job -> job.ruleCode().equals("TRIAL_ISOLATION"));
    }

    @Test
    void smallSampleDegradesToPartialAndCannotBeAutoApproved() {
        int version = draft("TRIAL_SMALL");
        originalMinSampleRows = properties.getTrial().getMinSampleRows();
        properties.getTrial().setMinSampleRows(10000);

        ShadowTrialService.TrialResult result = trials.trial("TRIAL_SMALL", version);

        TrialState.Trialed state = (TrialState.Trialed) result.state();
        assertThat(state.coverage()).isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.reasons()).contains(CoverageReason.SAMPLE_TOO_SMALL);
        assertThat(state.approval()).isEqualTo(ApprovalMode.BLOCK_SIGNABLE);
        assertThat(result.report().checks().get("T5-a")).startsWith("FAIL");
    }

    @Test
    void untrustedGateShortCircuitsWithoutSpendingAQuery() {
        int version = draft("TRIAL_UNTRUSTED");
        gateHealth.markUntrusted(gateVersion(), null, "测试：canary 期望 2 条实际 0 条");

        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM recon_job", Integer.class);
        ShadowTrialService.TrialResult result = trials.trial("TRIAL_UNTRUSTED", version);
        int jobsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM recon_job", Integer.class);

        TrialState.Trialed state = (TrialState.Trialed) result.state();
        assertThat(state.verdict()).isEqualTo(TrialVerdict.INCONCLUSIVE);
        assertThat(state.coverage()).isEqualTo(Coverage.NONE);
        assertThat(state.reasons()).containsExactly(CoverageReason.GATE_UNTRUSTED);
        assertThat(state.approval()).as("闸门坏了，谁也不许签字放行").isEqualTo(ApprovalMode.BLOCK_HARD);
        assertThat(jobsAfter).as("不可信期间不该再跑试算，那是纯浪费").isEqualTo(jobsBefore);
    }

    @Test
    void sqlWithLimitDegradesCoverageAndSaysSoInTheReport() {
        // A23 的实体：上游 SQL 自带 LIMIT。物化会成功、T1–T7 也可能全过，
        // 但"验到的只是原查询截断后的一角"必须落在 coverage 上 —— 否则试算 PASS、生产全红，
        // 而报告里一个字都不提这件事。
        int version = draftWithLimit("TRIAL_LIMIT", 10);
        ShadowTrialService.TrialResult result = trials.trial("TRIAL_LIMIT", version);

        TrialState.Trialed state = (TrialState.Trialed) result.state();
        assertThat(state.coverage()).as("截断样本不许算 FULL").isEqualTo(Coverage.SAMPLE_PARTIAL);
        assertThat(state.reasons()).contains(CoverageReason.UPSTREAM_LIMIT);
        assertThat(state.verdict()).as("这一角里确实没验出问题 —— 但它只是一角").isEqualTo(TrialVerdict.PASS);
        assertThat(state.approval()).as("可批，但必须有人签字").isEqualTo(ApprovalMode.NEED_SIGN);

        // 报告要能回答"哪一侧被截断了"，而不是只留一个枚举
        assertThat(result.report().upstreamTruncation()).containsExactly("left: LIMIT", "right: LIMIT");
        assertThat(result.report().checks().get("T5-d")).startsWith("降级");
    }

    @Test
    void upstreamLimitAndSmallSampleBothLandInTheReasonColumn() {
        // A28② 的实体：sql 自带 LIMIT，样本当然也不够 —— 两个理由必须同时出现在逗号列里。
        // 单值列会丢掉审核人最需要的那半句话："截断"是他改不了的，"样本不够"是他能调的。
        int version = draftWithLimit("TRIAL_LIMIT_SMALL", 10);
        originalMinSampleRows = properties.getTrial().getMinSampleRows();
        properties.getTrial().setMinSampleRows(10000);   // 左右各 10 行，必然不达标

        ShadowTrialService.TrialResult result = trials.trial("TRIAL_LIMIT_SMALL", version);

        RuleRepository.TrialRecord stored =
                ruleRepository.findTrial("TRIAL_LIMIT_SMALL", version).orElseThrow();
        assertThat(stored.verdict()).isEqualTo("INCONCLUSIVE");
        assertThat(stored.coverage()).isEqualTo("SAMPLE_PARTIAL");
        assertThat(stored.coverageReason()).contains("UPSTREAM_LIMIT").contains("SAMPLE_TOO_SMALL");
        // 列序来自 CoverageReason.severity：UPSTREAM_LIMIT(30) 在 SAMPLE_TOO_SMALL(10) 之前
        assertThat(stored.coverageReason().indexOf("UPSTREAM_LIMIT"))
                .isLessThan(stored.coverageReason().indexOf("SAMPLE_TOO_SMALL"));
        assertThat(result.report().upstreamTruncation()).isNotEmpty();
    }

    @Test
    void retryGateClearsTheHealthRecordSoTheNextTrialRunsAgain() {
        int version = draft("TRIAL_RETRY");
        gateHealth.markUntrusted(gateVersion(), null, "测试：制造一次失败");

        ShadowTrialService.TrialResult retried = trials.retryGate("TRIAL_RETRY", version);

        assertThat(retried.state()).isInstanceOf(TrialState.Trialed.class);
        assertThat(gateHealth.isTrusted(gateVersion())).isTrue();
    }

    // ---------------- 样本表租约（D2） ----------------

    @Test
    void materializeRegistersTheLeaseBeforeTheTablesExist() {
        int version = draft("TRIAL_MATERIALIZE");
        RuleRepository.RuleRow row = ruleRepository.findByVersion("TRIAL_MATERIALIZE", version).orElseThrow();
        RuleSpec rule = catalog.parseSnapshot(row.specJson(), "test");
        SampleWindowProbe.Window window = probe.probe(rule.left(), rule.right(), 100);

        SampleMaterializer.Materialized tables = materializer.materialize(rule, window, 64L * 1024 * 1024);
        try {
            assertThat(leases.isRegistered(tables.tableLeft())).as("建表之前就必须有登记").isTrue();
            assertThat(leases.isRegistered(tables.tableRight())).isTrue();
            assertThat(tableExists(tables.tableLeft())).isTrue();
        } finally {
            materializer.drop(tables);
        }
        assertThat(leases.isRegistered(tables.tableLeft()))
                .as("drop 之后租约必须一起注销，否则清理会一直看到一对已经不存在的表").isFalse();
    }

    @Test
    void trialLeavesNoLeaseRowBehind() {
        int version = draft("TRIAL_LEASE");
        trials.trial("TRIAL_LEASE", version);

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM recon_trial_sample", Integer.class);
        assertThat(rows).as("试算的正常路径也要注销：finally 里的 drop 负责这件事").isZero();
    }

    @Test
    void cleanupKeepsALiveLeaseAndItsTables() {
        // 反向验证的核心：另一个实例正在跑（心跳新鲜），我一个字节都不许动。
        // 这条如果转红，说明"租约"退化成了"扫到就删" —— 那 D2 等于没修。
        String runId = "live" + System.nanoTime();
        String left = "trial_" + runId + "_l";
        String right = "trial_" + runId + "_r";
        jdbc.execute("CREATE TABLE " + left + " (k VARCHAR(32))");
        jdbc.execute("CREATE TABLE " + right + " (k VARCHAR(32))");
        leases.register(runId, left, right, Instant.now());
        try {
            int dropped = materializer.cleanupResidual(Instant.now(), Duration.ofMinutes(30));

            assertThat(dropped).as("心跳还新鲜 → 这是别人的工作区").isZero();
            assertThat(tableExists(left)).isTrue();
            assertThat(tableExists(right)).isTrue();
            assertThat(leases.isRegistered(left)).isTrue();
        } finally {
            materializer.drop(new SampleMaterializer.Materialized(runId, left, right));
        }
    }

    @Test
    void cleanupDropsALeaseWhoseHeartbeatStopped() {
        String runId = "dead" + System.nanoTime();
        String left = "trial_" + runId + "_l";
        String right = "trial_" + runId + "_r";
        jdbc.execute("CREATE TABLE " + left + " (k VARCHAR(32))");
        jdbc.execute("CREATE TABLE " + right + " (k VARCHAR(32))");
        leases.register(runId, left, right, Instant.now().minus(Duration.ofHours(2)));

        int dropped = materializer.cleanupResidual(Instant.now(), Duration.ofMinutes(30));

        assertThat(dropped).isGreaterThanOrEqualTo(2);
        assertThat(tableExists(left)).isFalse();
        assertThat(tableExists(right)).isFalse();
        assertThat(leases.isRegistered(left)).as("表删了，登记也要删").isFalse();
    }

    @Test
    void cleanupDropsAnOrphanTableThatNoLeaseCovers() {
        // 机制上线之前留下的表：没有登记行，也不可能有活人在用（新代码一定是先登记再建表）
        String orphan = "trial_orphan" + System.nanoTime() + "_l";
        jdbc.execute("CREATE TABLE " + orphan + " (k VARCHAR(32))");

        int dropped = materializer.cleanupResidual(Instant.now(), Duration.ofMinutes(30));

        assertThat(dropped).isGreaterThanOrEqualTo(1);
        assertThat(tableExists(orphan)).isFalse();
    }
}
