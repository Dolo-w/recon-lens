package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobKind;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.TrialPhase;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.engine.ValueComparator;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.rule.SpecHasher;
import io.github.dolow.reconlens.store.GateHealthRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 闸门 2：影子试算。
 *
 * <p>它回答的问题不是"这条规则对不对"，而是"<b>我们有没有资格说它对不对</b>"：
 * 先造一份两侧同窗的物化样本 → 跑两次看结果是否可复现（T2）→ 与生产基线比"少报了什么"（T3/T4）
 * → 检查样本够不够（T5）→ 自己给自己注入两条已知差异，看能不能检出（T0）。
 *
 * <h2>四个顺序不能换</h2>
 * <ol>
 *   <li><b>闸门 1 在闸门 2 之前</b>：静态违规（编造列、容差放大）不必花一次试算的成本去发现；</li>
 *   <li><b>物化在 canary 之前</b>：注入要改数据，而改生产表是绝对不行的 —— 只能改副本；</li>
 *   <li><b>两次正式跑在 canary 之前</b>：注入会永久改掉样本，之后再跑 T2 比的就是被污染的数据；</li>
 *   <li><b>canary 在"取差异集"之后</b>：注入点必须从"本来就干净"的键里选，否则必然误报闸门故障。</li>
 * </ol>
 *
 * <h2>试算不污染生产</h2>
 * 试算也写 {@code recon_job} / {@code recon_diff}（否则没有分片记录与差异），但一律 {@code kind=TRIAL}：
 * {@code listJobs} 与全库差异汇总都按 {@code PROD} 过滤。样本表在 {@code finally} 里 {@code DROP}，
 * 进程被杀留下的残留在下次启动时按 {@code trial_} 前缀回收。
 */
public class ShadowTrialService {

    private static final Logger log = LoggerFactory.getLogger(ShadowTrialService.class);

    private final RuleRepository ruleRepository;
    private final ReconRepository reconRepository;
    private final RuleCatalog catalog;
    private final ReconEngine engine;
    private final SampleWindowProbe probe;
    private final SampleMaterializer materializer;
    private final CanaryInjector canaryInjector;
    private final GateHealthRepository gateHealth;
    private final RuleSafetyValidator safetyValidator;
    private final TrialVerdictResolver resolver;
    private final ReconProperties properties;
    private final ObjectMapper json;

    public ShadowTrialService(RuleRepository ruleRepository,
                              ReconRepository reconRepository,
                              RuleCatalog catalog,
                              ReconEngine engine,
                              SampleWindowProbe probe,
                              SampleMaterializer materializer,
                              CanaryInjector canaryInjector,
                              GateHealthRepository gateHealth,
                              RuleSafetyValidator safetyValidator,
                              TrialVerdictResolver resolver,
                              ReconProperties properties,
                              ObjectMapper json) {
        this.ruleRepository = ruleRepository;
        this.reconRepository = reconRepository;
        this.catalog = catalog;
        this.engine = engine;
        this.probe = probe;
        this.materializer = materializer;
        this.canaryInjector = canaryInjector;
        this.gateHealth = gateHealth;
        this.safetyValidator = safetyValidator;
        this.resolver = resolver;
        this.properties = properties;
        this.json = json;
    }

    /** 一次试算的产出：结论 + 证据体 + （任务级）摘要钥匙。 */
    public record TrialResult(String code, int version, TrialState state, TrialReport report, String digest) {
    }

    /**
     * 对某一版规则跑影子试算，并把结论落回 {@code recon_rule.trial_*}。
     *
     * @throws RuleException 闸门 1 拒绝（违规列表在异常消息里）—— 它<b>不是</b>试算结论，
     *                       而是"根本没资格进入试算"，所以不落 {@code trial_verdict}
     */
    public TrialResult trial(String code, int version) {
        RuleRepository.RuleRow row = ruleRepository.findByVersion(code, version)
                .orElseThrow(() -> new RuleException("规则 " + code + " 没有版本 v" + version));
        RuleSpec candidate = catalog.parseSnapshot(row.specJson(), "规则库 " + code + " v" + version);
        RuleSpec baseline = baselineSpec(row).orElse(null);

        List<SafetyViolation> violations = safetyValidator.validate(candidate, baseline);
        if (!violations.isEmpty()) {
            throw new RuleException("闸门 1 拒绝 " + code + " v" + version + "，未进入试算：" + violations);
        }

        ReconProperties.Trial cfg = properties.getTrial();
        String gateVersion = GateVersion.of(cfg);

        // 闸门已被判定为不可信 → 不再浪费一次全量试算。这里不自动重跑 canary：
        // 自动恢复会让"闸门坏了"变成一次短暂抖动，而它本该是一次需要人介入的事件。
        Optional<GateHealthRepository.GateHealth> health = gateHealth.find(gateVersion);
        if (health.isPresent() && !health.get().trusted()) {
            TrialOutcome outcome = new TrialOutcome(false, CoverageReason.GATE_UNTRUSTED,
                    TrialOutcome.SampleStats.empty(), null, null, null, null, null, false, false);
            List<String> notes = List.of("闸门 " + gateVersion + " 处于不可信状态："
                    + health.get().detail() + "（修好后调用 retryGate 重新自检）");
            return persist(row, resolver.resolve(outcome),
                    report(row, gateVersion, null, outcome, List.of(), simpleChecks("N/A: 闸门不可信"), notes), null);
        }

        return execute(row, candidate, gateVersion, cfg);
    }

    /**
     * 修好闸门之后显式重跑自检：清掉这一版闸门的健康记录，再完整跑一次试算。
     *
     * <p>没有这个入口，{@code trusted=false} 就是一条单行道 —— 而闸门坏掉是运维上真实会发生的事。
     * 但也绝不能自动恢复，否则"闸门不可信"会退化成一次不起眼的抖动。
     */
    public TrialResult retryGate(String code, int version) {
        String gateVersion = GateVersion.of(properties.getTrial());
        gateHealth.clear(gateVersion);
        log.warn("收到闸门自检重跑请求（规则 {} v{}），已清除 gate-version={} 的健康记录", code, version, gateVersion);
        return trial(code, version);
    }

    /**
     * 清理过期的试算任务（{@code recon_job.kind='TRIAL'}）。不清的话每次试算两个 job 起步，
     * 表会被撑爆；而试算数据本来就只有"当时那一眼"的价值。
     *
     * @return 删除的任务数（差异与分片记录一并删除）
     */
    public int cleanupExpiredTrials() {
        int days = properties.getTrial().getRetentionDays();
        List<Long> expired = reconRepository.expiredTrialJobs(Instant.now().minusSeconds(days * 86400L));
        for (Long jobId : expired) {
            reconRepository.deleteJob(jobId);
        }
        if (!expired.isEmpty()) {
            log.info("清理了 {} 个超过 {} 天的试算任务", expired.size(), days);
        }
        return expired.size();
    }

    // ------------------------------------------------------------------

    private TrialResult execute(RuleRepository.RuleRow row, RuleSpec candidate,
                                String gateVersion, ReconProperties.Trial cfg) {
        List<String> notes = new ArrayList<>();
        int windowRows = cfg.getWindowRows();
        SampleMaterializer.Materialized tables = null;
        SampleWindowProbe.Window window = null;
        try {
            SampleBlockedException blocked = null;
            int attempts = 0;
            while (attempts < cfg.getWindowAttempts() && tables == null && blocked == null) {
                attempts++;
                try {
                    window = probe.probe(candidate.left(), candidate.right(), windowRows);
                    probe.requireSameCollation(window);
                    tables = materializer.materialize(candidate, window, cfg.getMaxSampleBytes());
                } catch (SampleBlockedException e) {
                    if (e.reason() == CoverageReason.SAMPLE_TOO_SMALL && attempts < cfg.getWindowAttempts()) {
                        windowRows = Math.max(1, windowRows / 2);
                        notes.add("样本超上限，第 " + attempts + " 次缩窗重试（window-rows=" + windowRows + "）");
                        continue;
                    }
                    blocked = e;
                }
            }
            if (blocked != null || tables == null) {
                CoverageReason reason = blocked == null ? CoverageReason.NO_DDL_PRIVILEGE : blocked.reason();
                notes.add("未物化：" + (blocked == null ? "取窗失败" : blocked.getMessage()));
                TrialOutcome outcome = new TrialOutcome(false, reason, TrialOutcome.SampleStats.empty(), null,
                        null, null, null, null, false, false);
                return persist(row, resolver.resolve(outcome),
                        report(row, gateVersion, window, outcome, List.of(), simpleChecks("N/A: 未物化"), notes), null);
            }

            // 物化成功 ≠ 样本完整：原 SQL 自带 LIMIT 时，CTAS 越忠实，样本越是"截断后的一角"。
            // 这件事必须在跑之前就知道，否则只能从"试算 PASS、生产全红"里事后发现。
            List<String> truncation = SqlTruncationDetector.describe(candidate);
            boolean upstreamTruncated = !truncation.isEmpty();

            RuleSpec trialRule = trialVariant(candidate, tables);
            RuleCatalog.Resolved resolved = new RuleCatalog.Resolved(trialRule, null, snapshotOf(trialRule, tables));
            String batch = "TRIAL-" + row.code() + "-v" + row.version();

            ReconEngine.RunTrace first = engine.runTraced(resolved, batch,
                    "trial " + row.code() + " v" + row.version(), JobKind.TRIAL, TrialPhase.TRIAL);
            materializer.heartbeat(tables);
            ReconEngine.RunTrace second = engine.runTraced(resolved, batch + "-2",
                    "trial-idempotency " + row.code() + " v" + row.version(), JobKind.TRIAL, TrialPhase.TRIAL);
            materializer.heartbeat(tables);

            boolean jobSucceeded = first.job().status() == JobStatus.SUCCESS;
            boolean idempotent = jobSucceeded && first.digestsByShard().equals(second.digestsByShard());

            Map<String, String> checks = new LinkedHashMap<>();
            checks.put("T1", jobSucceeded ? "PASS" : "FAIL: " + first.job().status()
                    + (first.job().errorMsg() == null ? "" : " (" + first.job().errorMsg() + ")"));
            checks.put("T2", idempotent ? "PASS" : "FAIL: 两次运行摘要不一致 first="
                    + first.digestsByShard() + " second=" + second.digestsByShard());

            List<DiffRecord> newDiffs = first.capturedDiffs();
            TrialOutcome.SampleStats stats = sampleStats(row, candidate, trialRule, tables, first);
            checks.put("T5-a", stats.leftRows() >= cfg.getMinSampleRows() && stats.rightRows() >= cfg.getMinSampleRows()
                    ? "PASS" : "FAIL: 每侧样本行数 " + stats.leftRows() + "/" + stats.rightRows());
            checks.put("T5-b", stats.commonKeys() >= cfg.getMinCommonKeys()
                    ? "PASS" : "FAIL: 双侧交集 " + stats.commonKeys());
            int required = resolver.requiredShards(
                    stats.baselineShardsWithData() > 0 ? stats.baselineShardsWithData() : stats.shardCount());
            checks.put("T5-c", candidate.shard().count() <= 1 ? "N/A: 单分片规则"
                    : (stats.shardsWithData() >= required ? "PASS: " + stats.shardsWithData() + "/" + required
                    : "FAIL: 有数据分片 " + stats.shardsWithData() + "/" + required));
            checks.put("T5-d", upstreamTruncated
                    ? "降级: 上游 SQL 自带截断（" + String.join("，", truncation)
                    + "）→ 物化到的是截断后的一角，覆盖只能算 SAMPLE_PARTIAL"
                    : "PASS");

            Baseline baselineDiffSet = baselineDiffs(row.code(), window, candidate);
            Boolean coverageLossExplained = baselineDiffSet.comparable()
                    ? coverageLossExplained(baselineDiffSet, newDiffs, candidate) : null;
            Boolean zeroDiffExplained = null;
            if (!baselineDiffSet.comparable()) {
                checks.put("T3", "N/A: " + baselineDiffSet.reason());
                checks.put("T4", "N/A: " + baselineDiffSet.reason());
            } else if (baselineDiffSet.diffs().isEmpty()) {
                checks.put("T3", "N/A: 窗口内没有基线差异");
                checks.put("T4", "N/A: 基线为 0，归零无从谈起");
            } else {
                checks.put("T3", Boolean.TRUE.equals(coverageLossExplained) ? "PASS"
                        : "FAIL: 有差异消失且无法机械归因");
                if (newDiffs.isEmpty()) {
                    zeroDiffExplained = coverageLossExplained;
                    checks.put("T4", Boolean.TRUE.equals(zeroDiffExplained) ? "PASS"
                            : "FAIL: 差异归零但无法机械解释");
                } else {
                    checks.put("T4", "N/A: 新差异不为 0（" + newDiffs.size() + " 条）");
                }
            }

            boolean growth = stats.baselineDiffCount() > 0
                    && stats.newDiffCount() > stats.baselineDiffCount() * cfg.getDiffGrowthRatio();
            checks.put("T6", stats.baselineDiffCount() == 0 ? "N/A: 无基线"
                    : (growth ? "重点区: 差异 " + stats.baselineDiffCount() + " → " + stats.newDiffCount()
                    : "PASS: " + stats.baselineDiffCount() + " → " + stats.newDiffCount()));

            // canary 必须最后做：它要改样本表，而且注入点要排除本次已经报出差异的键
            TrialOutcome.Canary canary = runCanary(tables, trialRule, newDiffs, gateVersion, batch);
            materializer.heartbeat(tables);
            checks.put("T0", canary.runnable()
                    ? (canary.passed() ? "PASS" : "FAIL: " + canary.detail())
                    : "N/A: " + canary.detail());

            TrialOutcome outcome = new TrialOutcome(true, null, stats, canary,
                    jobSucceeded, idempotent, coverageLossExplained, zeroDiffExplained, growth, upstreamTruncated);
            TrialState state = resolver.resolve(outcome);
            notes.add("注入位置与改前/改后见 canary.detail：注入的是样本表，不是生产表");
            if (upstreamTruncated) {
                notes.add("上游截断：" + truncation + "。样本是原查询截断后的一角，"
                        + "试算通过只说明这一角里没问题，不代表生产全量没问题");
            }
            return persist(row, state, report(row, gateVersion, window, outcome, truncation, checks, notes),
                    digestOf(first));
        } finally {
            materializer.drop(tables);
        }
    }

    /**
     * 试算变体：把两侧数据源换成样本表、清掉 {@code sql}/{@code where}（都已压进样本），
     * <b>保留 dimension / measures / keyField / compare / shard</b>。
     * 分片条件保持原样，交给 {@code ShardPlanner} 在运行时展开 —— 一旦在这里按分片物化，
     * 试算就退化成单分片，T4 又变成永绿。
     */
    private RuleSpec trialVariant(RuleSpec candidate, SampleMaterializer.Materialized tables) {
        return new RuleSpec(candidate.code(), candidate.name(), candidate.description(),
                sampleSide(candidate.left(), tables.tableLeft()),
                sampleSide(candidate.right(), tables.tableRight()),
                candidate.shard(), candidate.compare(), candidate.missing(), candidate.duplicate(),
                candidate.maxAttempts());
    }

    private static SideSpec sampleSide(SideSpec side, String table) {
        return new SideSpec(side.label(), table, null, side.keyField(), side.keyType(), side.orderBy(),
                side.dimension(), side.dimensionColumns(), side.dimensionSeparator(), side.measures(), null);
    }

    private TrialOutcome.Canary runCanary(SampleMaterializer.Materialized tables, RuleSpec trialRule,
                                          List<DiffRecord> newDiffs, String gateVersion, String batch) {
        Set<String> dirtyKeys = new LinkedHashSet<>();
        for (DiffRecord diff : newDiffs) {
            dirtyKeys.add(diff.bizKey());
        }
        CanaryInjector.Plan plan = canaryInjector.inject(tables, trialRule, dirtyKeys);
        if (!plan.runnable()) {
            // 干净键不够 ≠ 闸门坏了：这是"这条规则差异率异常高"的信号，不置 gate_trusted=false
            return new TrialOutcome.Canary(false, false, plan.reason());
        }
        ReconEngine.RunTrace canaryRun = engine.runTraced(
                new RuleCatalog.Resolved(trialRule, null, snapshotOf(trialRule, tables)),
                batch + "-canary", "canary " + trialRule.code(), JobKind.TRIAL, TrialPhase.CANARY);
        TrialOutcome.Canary canary = canaryInjector.verify(plan, trialRule, canaryRun.capturedDiffs());
        if (canary.passed()) {
            gateHealth.markTrusted(gateVersion, canaryRun.job().id());
        } else {
            // canary 失败 = 这一版闸门整体不可信，不只是这条规则。落库是为了重启后别把签字通道悄悄打开。
            gateHealth.markUntrusted(gateVersion, canaryRun.job().id(), canary.detail());
        }
        return canary;
    }

    /**
     * T5-b 的分母：两侧共同的业务键数（去重）。
     *
     * <p><b>为什么不用 {@code INTERSECT}</b>：它从 MySQL <b>8.0.31</b> 才有，而目标库是 8.0.29
     * （README 声明的支持版本）。写成 {@code INTERSECT} 在 H2 上一切正常、到了生产直接
     * {@code ERROR 1064}，然后被上层吞成 {@code INCONCLUSIVE} —— 这条最关键的"分片切错"判定
     * 会在目标库上从未真正跑过，而所有测试都是绿的。
     *
     * <p>等价式：{@code |A ∩ B|}（去重）= 左表去重后、能在右表找到同值的键数。
     * NULL 键不计入：业务键为 NULL 的行本来就不该参与对账（INTERSECT 会把两个 NULL 当成相同，
     * 反而给出虚高的共同键数，掩盖"两侧根本没重叠"这件事）。
     *
     * <p>两侧都先投影成单列派生表再比较，这样 {@code keyExpression()} 是普通列还是
     * {@code CONCAT(...)} 之类的表达式都不影响 SQL 形状 —— 不能给表达式加 {@code r.} 前缀。
     */
    static String commonKeysSql(RuleSpec rule, SampleMaterializer.Materialized tables) {
        String left = "SELECT " + rule.left().keyExpression() + " AS k FROM " + tables.tableLeft();
        String right = "SELECT " + rule.right().keyExpression() + " AS k FROM " + tables.tableRight();
        return "SELECT COUNT(*) FROM (SELECT DISTINCT l.k FROM (" + left + ") l"
                + " WHERE l.k IS NOT NULL"
                + " AND EXISTS (SELECT 1 FROM (" + right + ") r WHERE r.k = l.k)) x";
    }

    private TrialOutcome.SampleStats sampleStats(RuleRepository.RuleRow row, RuleSpec candidate, RuleSpec trialRule,
                                                 SampleMaterializer.Materialized tables, ReconEngine.RunTrace run) {
        int leftRows = countRows("SELECT COUNT(*) FROM " + tables.tableLeft());
        int rightRows = countRows("SELECT COUNT(*) FROM " + tables.tableRight());
        int commonKeys = countRows(commonKeysSql(trialRule, tables));
        int shardsWithData = reconRepository.shardsWithData(run.job().id());
        int shardCount = candidate.shard().count();

        Optional<Long> baselineJob = reconRepository.latestProdJobId(row.code());
        int baselineShards = baselineJob.map(reconRepository::shardsWithData).orElse(0);
        int baselineDiffCount = baselineJob.flatMap(reconRepository::findJob)
                .map(job -> (int) job.diffRows()).orElse(0);
        return new TrialOutcome.SampleStats(leftRows, rightRows, commonKeys, shardsWithData, shardCount,
                baselineShards, baselineDiffCount, run.capturedDiffs().size());
    }

    /** 基线差异集：最近一次同 code 的 PROD 任务，限定在<b>本次采样窗口内</b>。 */
    private Baseline baselineDiffs(String code, SampleWindowProbe.Window window, RuleSpec candidate) {
        if (window == null || window.lo() == null) {
            return Baseline.notComparable("采样窗口为空");
        }
        Optional<Long> jobId = reconRepository.latestProdJobId(code);
        if (jobId.isEmpty()) {
            return Baseline.notComparable("该规则没有历史生产任务");
        }
        List<DiffRecord> diffs = reconRepository.diffsInWindow(jobId.get(), window.lo(), window.hi(),
                candidate.left().keyType());
        Set<String> keys = windowKeys(candidate, window);
        return new Baseline(diffs, keys, keys != null, keys == null ? "读取样本键集合失败" : null);
    }

    /**
     * 窗口内出现过的业务键（两侧并集）。
     *
     * <p>为什么需要它：基线差异里有些键压根没进样本（窗口按 {@code LIMIT} 截断），
     * 把它们算进"消失的差异"，T3 会把<b>采样裁剪</b>误判成<b>覆盖损失</b>。
     *
     * @return {@code null} 表示查询失败 —— 此时<b>不</b>放宽判定，宁可判 FAIL 让人看，
     *         也不要让一个失败的辅助查询把"无法归因"变成"看起来都归因了"
     */
    private Set<String> windowKeys(RuleSpec candidate, SampleWindowProbe.Window window) {
        String sql = "SELECT k FROM (" + keysOf(candidate.left(), window)
                + " UNION " + keysOf(candidate.right(), window) + ") u";
        try {
            return new LinkedHashSet<>(reconRepository.queryKeys(sql));
        } catch (RuntimeException e) {
            log.warn("读取窗口键集合失败，T3 按严格口径判定（无法归因即 FAIL）: {}", e.toString());
            return null;
        }
    }

    private String keysOf(SideSpec side, SampleWindowProbe.Window window) {
        String key = side.keyExpression();
        StringBuilder sql = new StringBuilder("SELECT ").append(key).append(" AS k FROM (")
                .append(side.sourceSelect()).append(") src WHERE (")
                .append(key).append(" BETWEEN ").append(SampleMaterializer.literal(side, window.lo()))
                .append(" AND ").append(SampleMaterializer.literal(side, window.hi())).append(')');
        if (side.where() != null && !side.where().isBlank()) {
            sql.append(" AND (").append(side.where()).append(')');
        }
        return sql.toString();
    }

    /**
     * T3：消失的差异是否逐条可机械解释。只认三种来源，<b>不认自述</b>：
     * <ol>
     *   <li>该字段在新规则里不再参与 compare；</li>
     *   <li>用<b>新容差</b>重算该条差异，算出来确实在容差内；</li>
     *   <li>该键在新样本里被判为 matched（新差异集里完全没有它，且它确实进过样本）。</li>
     * </ol>
     */
    private boolean coverageLossExplained(Baseline baseline, List<DiffRecord> newDiffs, RuleSpec candidate) {
        Set<String> newKeys = new LinkedHashSet<>();
        Set<String> newBizKeys = new LinkedHashSet<>();
        for (DiffRecord diff : newDiffs) {
            newKeys.add(keyOf(diff));
            newBizKeys.add(diff.bizKey());
        }
        Set<String> sampleKeys = baseline.sampleKeys() == null ? Set.of() : baseline.sampleKeys();
        boolean keysKnown = baseline.sampleKeys() != null;

        for (DiffRecord old : baseline.diffs()) {
            if (newKeys.contains(keyOf(old))) {
                continue;                       // 没消失
            }
            String field = old.field() == null ? "" : old.field();
            if (!field.isBlank() && candidate.field(field).isEmpty()) {
                continue;                       // ① 字段不再参与比对
            }
            if (!field.isBlank() && withinNewTolerance(old, candidate.field(field).orElse(null))) {
                continue;                       // ② 新容差能覆盖这条差额
            }
            if (keysKnown && !newBizKeys.contains(old.bizKey()) && sampleKeys.contains(old.bizKey())) {
                continue;                       // ③ 该键在新规则下判为 matched（且确实验到了）
            }
            return false;
        }
        return true;
    }

    private boolean withinNewTolerance(DiffRecord old, FieldRule newRule) {
        if (newRule == null || old.leftValue() == null || old.rightValue() == null) {
            return false;
        }
        return !ValueComparator.compare(newRule, old.leftValue(), old.rightValue()).different();
    }

    private static String keyOf(DiffRecord diff) {
        return diff.diffType() + '\u0001' + diff.bizKey() + '\u0001' + (diff.field() == null ? "" : diff.field());
    }

    /**
     * @param sampleKeys 窗口内出现过的键；{@code null} = 查询失败（按严格口径判）
     */
    private record Baseline(List<DiffRecord> diffs, Set<String> sampleKeys, boolean comparable, String reason) {

        static Baseline notComparable(String reason) {
            return new Baseline(List.of(), Set.of(), false, reason);
        }
    }

    private int countRows(String sql) {
        Integer value = reconRepository.countRows(sql);
        return value == null ? 0 : value;
    }

    private String digestOf(ReconEngine.RunTrace run) {
        Map<Integer, String> digests = new TreeMap<>(run.digestsByShard());
        StringBuilder text = new StringBuilder();
        digests.forEach((shard, sha) -> text.append(shard).append('=').append(sha).append(';'));
        return text.isEmpty() ? null : SpecHasher.sha256(text.toString());
    }

    private TrialReport report(RuleRepository.RuleRow row, String gateVersion, SampleWindowProbe.Window window,
                               TrialOutcome outcome, List<String> truncation,
                               Map<String, String> checks, List<String> notes) {
        TrialReport.Window windowView = window == null ? null : new TrialReport.Window(
                window.lo(), window.hi(),
                window.left() == null ? 0 : window.left().rows(),
                window.right() == null ? 0 : window.right().rows(), null, null);
        String base = row.baseVersion() == null ? null : "v" + row.baseVersion();
        return new TrialReport(row.code(), row.version(), gateVersion, base, windowView,
                outcome.sample(), truncation, outcome.canary(), checks, notes, Instant.now());
    }

    private Map<String, String> simpleChecks(String reason) {
        Map<String, String> checks = new LinkedHashMap<>();
        for (String id : List.of("T0", "T1", "T2", "T3", "T4", "T5-a", "T5-b", "T5-c", "T5-d", "T6")) {
            checks.put(id, reason);
        }
        return checks;
    }

    private Optional<RuleSpec> baselineSpec(RuleRepository.RuleRow row) {
        if (row.baseVersion() != null) {
            return ruleRepository.findByVersion(row.code(), row.baseVersion())
                    .map(base -> catalog.parseSnapshot(base.specJson(),
                            "基线 " + row.code() + " v" + row.baseVersion()));
        }
        return ruleRepository.listVersions(row.code()).stream()
                .filter(candidate -> candidate.version() < row.version() && candidate.runtimeVisible())
                .findFirst()
                .map(base -> catalog.parseSnapshot(base.specJson(),
                        "基线 " + row.code() + " v" + base.version()));
    }

    /**
     * 试算快照：存<b>试算变体</b> + 样本表描述，保证这次试算可回放。
     * 之所以不存原规则 JSON：那是生产表名，事后照着"重跑"会跑生产 —— 与试算的语义正好相反。
     */
    private String snapshotOf(RuleSpec trialRule, SampleMaterializer.Materialized tables) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("code", trialRule.code());
            root.put("name", trialRule.name());
            root.put("description", trialRule.description());
            root.put("left", trialRule.left());
            root.put("right", trialRule.right());
            root.put("shard", trialRule.shard());
            root.put("compare", trialRule.compare());
            root.put("missing", trialRule.missing());
            root.put("duplicate", trialRule.duplicate());
            root.put("maxAttempts", trialRule.maxAttempts());
            root.put("trialSample", Map.of("left", tables.tableLeft(), "right", tables.tableRight()));
            return json.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("试算快照序列化失败，回退为只带 code: {}", e.toString());
            return "{\"code\":\"" + trialRule.code() + "\"}";
        }
    }

    private TrialResult persist(RuleRepository.RuleRow row, TrialState state, TrialReport report, String digest) {
        String reportJson;
        try {
            reportJson = json.writeValueAsString(report.toMap());
        } catch (Exception e) {
            reportJson = "{\"error\":\"报告序列化失败: " + e.getMessage() + "\"}";
        }
        // 绑定的是 row.specJson()（这次试算真正跑的那份快照），不是"落库这一刻库里是什么"。
        // 两者之间如果有人改了内容，这里必须留下旧哈希，让 approve 侧发现"验的和批的不是一份"。
        ruleRepository.saveTrial(row.code(), row.version(), state, digest, reportJson,
                SpecHasher.sha256(row.specJson()));
        if (state instanceof TrialState.Trialed trialed) {
            log.info("试算完成 {} v{}: {}，摘要 {}，理由 {}", row.code(), row.version(),
                    TrialVerdictResolver.describe(trialed), digest == null ? "-" : digest.substring(0, 8),
                    trialed.reasons());
        } else {
            log.info("试算完成 {} v{}: {}", row.code(), row.version(), state.getClass().getSimpleName());
        }
        return new TrialResult(row.code(), row.version(), state, report, digest);
    }

    /** 便捷断言：这条结论是否允许直接批准（签字通道之外的唯一放行方式）。 */
    public static boolean autoApprovable(TrialState state) {
        return state.approval() == ApprovalMode.NO_SIGNOFF_REQUIRED;
    }
}
