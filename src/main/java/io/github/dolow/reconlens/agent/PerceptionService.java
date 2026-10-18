package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 感知阶段：定时巡检"最近几次生产任务有没有变得不正常"，产出<b>报告</b>。
 *
 * <h2>只出报告，不推送、不自动动手（评审 R18）</h2>
 * 巡检最容易写坏的地方不是指标算错，而是"发现异常就自动重跑 / 自动改规则"。
 * 那会把一个观察者变成参与者：一旦它的判断偏了，它会用自己的错误动作去验证自己的错误判断。
 * 所以这里<b>只读不写业务数据</b>，唯一的写入是给自己留一条轨迹（{@code recon_agent_run}），
 * 让"巡检到底看到了什么"事后可复盘。
 *
 * <h2>四个信号（对应 §4.1）</h2>
 * <ul>
 *   <li>差异率环比：最新一次 vs 上一次，涨超倍数 → {@code DIFF_RATE_JUMP}；</li>
 *   <li>新出现的 {@code diff_type}：窗口内从未见过 → {@code NEW_DIFF_TYPE}（新类型往往意味着新病因）；</li>
 *   <li>{@code HIGH} 占比：高严重度占比超阈值 → {@code HIGH_SHARE}；</li>
 *   <li>{@code PARTIAL_FAILED}：分片失败但任务被标成功 → {@code PARTIAL_FAILED}（最容易被忽略的一类）。</li>
 * </ul>
 *
 * <h2>告警抑制不是"少喊两句"，而是为了让报告可信</h2>
 * 一个持续三天的异常，如果每轮都作为"新发现"上报，运维到第二天就不再看了。
 * 所以同一 {@code (ruleCode, kind)} 在 {@code alert-suppress-minutes} 内只报一次，
 * 其余的进 {@code suppressed} 而不是消失 —— <b>抑制必须是可见的</b>，
 * 否则"没报"会被误读成"没问题"。
 */
public class PerceptionService {

    private static final Logger log = LoggerFactory.getLogger(PerceptionService.class);

    /** 无异常时报告也是要落一条轨迹的：否则"巡检在跑"这件事本身无法证明。 */
    private static final String TRIGGER = "SCHEDULED";

    private final ReconRepository repository;
    private final AgentRunRepository runs;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** {@code (ruleCode:kind)} → 上次上报时间。进程内，重启归零（见类注释）。 */
    private final Map<String, Instant> lastAlert = new HashMap<>();

    public PerceptionService(ReconRepository repository, AgentRunRepository runs,
                             AgentProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.runs = runs;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 一条趋势：某个规则最近一次生产任务的样子，以及和上一次的比较。 */
    public record RuleTrend(String ruleCode,
                            long jobId,
                            JobStatus status,
                            long diffRows,
                            long leftRows,
                            double diffRate,
                            Double previousDiffRate,
                            Double changeRatio,
                            List<String> newDiffTypes,
                            double highShare,
                            int windowJobs) {

        /** 环比没有上一期时为 {@code null} —— 0% 与"不知道"是两件事。 */
        public String describeRate() {
            String base = String.format("%.2f%%（%d/%d）", diffRate * 100, diffRows, leftRows);
            if (previousDiffRate == null) {
                return base + "，无上一期可比";
            }
            return String.format("%s，上期 %.2f%%，环比 %s", base, previousDiffRate * 100,
                    changeRatio == null ? "不适用" : String.format("%+.0f%%", (changeRatio - 1) * 100));
        }
    }

    public enum Level { INFO, WARN, HIGH }

    /** 一条发现。{@code kind} 与 {@code ruleCode} 一起构成抑制键。 */
    public record Finding(String ruleCode, String kind, Level level, String message) {
        public String key() {
            return ruleCode + ":" + kind;
        }
    }

    public record PerceptionReport(Instant scannedAt,
                                   int windowJobs,
                                   List<RuleTrend> trends,
                                   List<Finding> findings,
                                   List<Finding> suppressed) {

        public boolean clean() {
            return findings.isEmpty();
        }

        /** 一句话结论，进日志与轨迹；不推送（R18）。 */
        public String describe() {
            if (trends.isEmpty()) {
                return "巡检：" + scannedAt + " 无已完成的生产任务，跳过";
            }
            StringBuilder sb = new StringBuilder("巡检：").append(trends.size()).append(" 条规则，")
                    .append(findings.size()).append(" 个新发现");
            if (!suppressed.isEmpty()) {
                sb.append("，").append(suppressed.size()).append(" 个在抑制窗口内未重复上报");
            }
            for (Finding finding : findings) {
                sb.append("\n  [").append(finding.level()).append("] ").append(finding.ruleCode())
                        .append(' ').append(finding.message());
            }
            return sb.toString();
        }
    }

    /**
     * 跑一轮巡检。异常不外抛 —— 定时任务抛出去不会让调度停摆，
     * 只会给每个周期留一条看起来像崩溃的堆栈；调用方拿到的应当是"这轮没扫成"。
     */
    public PerceptionReport scan() {
        Instant now = clock.instant();
        int window = Math.max(2, properties.getPerception().getWindowJobs());
        List<ReconJob> jobs = repository.recentProdJobs(window * 20);

        Map<String, List<ReconJob>> byRule = new LinkedHashMap<>();
        for (ReconJob job : jobs) {
            if (!job.status().finished()) {
                continue;   // 正在跑的没有结论，算进环比等于拿半成品当事实
            }
            List<ReconJob> bucket = byRule.computeIfAbsent(job.ruleCode(), code -> new ArrayList<>());
            if (bucket.size() < window) {
                bucket.add(job);   // jobs 已按 id 倒序，天然是"最近 N 次"
            }
        }

        List<RuleTrend> trends = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        List<Finding> suppressed = new ArrayList<>();

        for (Map.Entry<String, List<ReconJob>> entry : byRule.entrySet()) {
            String ruleCode = entry.getKey();
            List<ReconJob> recent = entry.getValue();
            RuleTrend trend = trend(ruleCode, recent, window);
            trends.add(trend);
            for (Finding finding : analyse(trend, recent)) {
                if (isSuppressed(finding, now)) {
                    suppressed.add(finding);
                } else {
                    findings.add(finding);
                    lastAlert.put(finding.key(), now);
                }
            }
        }
        trends.sort(Comparator.comparing(RuleTrend::ruleCode));

        PerceptionReport report = new PerceptionReport(now, window, trends, findings, suppressed);
        persist(report);
        return report;
    }

    // ------------------------------------------------------------------

    private RuleTrend trend(String ruleCode, List<ReconJob> recent, int window) {
        ReconJob latest = recent.get(0);
        double diffRate = diffRate(latest);
        Double previous = recent.size() > 1 ? diffRate(recent.get(1)) : null;
        Double changeRatio = null;
        if (previous != null && previous > 0) {
            changeRatio = diffRate / previous;
        } else if (previous != null && diffRate > 0) {
            // 上一期 0%、这一期 >0%：环比是"从无到有"，不是某个倍数 —— 用比例会得到无穷大
            changeRatio = null;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 1; i < recent.size(); i++) {
            seen.addAll(repository.summaryByType(recent.get(i).id()).keySet());
        }
        List<String> fresh = new ArrayList<>();
        for (String type : repository.summaryByType(latest.id()).keySet()) {
            if (!seen.contains(type)) {
                fresh.add(type);
            }
        }

        return new RuleTrend(ruleCode, latest.id(), latest.status(), latest.diffRows(), latest.leftRows(),
                diffRate, previous, changeRatio, fresh, highShare(latest), recent.size());
    }

    /**
     * 差异率 = 差异行 / 左侧行数。
     *
     * <p>分母用左侧而不是左右之和：左侧是"应收/应有"的锚，量级稳定；
     * 左右之和会在单边缺失集中爆发时同时被抬高，把差异率的涨幅自己吃掉一半。
     * 左侧没有数据时退回右侧（否则一条右侧单边任务会永远算 0%）。
     */
    private static double diffRate(ReconJob job) {
        long base = job.leftRows() > 0 ? job.leftRows() : job.rightRows();
        return base <= 0 ? 0.0 : (double) job.diffRows() / base;
    }

    private double highShare(ReconJob job) {
        Map<String, Long> bySeverity = repository.summaryBySeverity(job.id());
        long total = bySeverity.values().stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            return 0.0;
        }
        long high = bySeverity.entrySet().stream()
                .filter(e -> "HIGH".equalsIgnoreCase(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        return (double) high / total;
    }

    private List<Finding> analyse(RuleTrend trend, List<ReconJob> recent) {
        List<Finding> found = new ArrayList<>();
        AgentProperties.Perception config = properties.getPerception();

        if (trend.changeRatio() != null && trend.changeRatio() >= config.getDiffRateJumpRatio()) {
            found.add(new Finding(trend.ruleCode(), "DIFF_RATE_JUMP", Level.HIGH,
                    String.format("差异率 %s，为上期的 %.2f 倍（阈值 %.2f）",
                            trend.describeRate(), trend.changeRatio(), config.getDiffRateJumpRatio())));
        }
        if (!trend.newDiffTypes().isEmpty()) {
            found.add(new Finding(trend.ruleCode(), "NEW_DIFF_TYPE", Level.WARN,
                    "出现窗口内从未有过的差异类型：" + String.join(", ", trend.newDiffTypes())));
        }
        if (trend.highShare() >= config.getHighShareThreshold()) {
            found.add(new Finding(trend.ruleCode(), "HIGH_SHARE", Level.WARN,
                    String.format("高严重度占比 %.0f%%（阈值 %.0f%%）",
                            trend.highShare() * 100, config.getHighShareThreshold() * 100)));
        }
        if (trend.status() == JobStatus.PARTIAL_FAILED) {
            found.add(new Finding(trend.ruleCode(), "PARTIAL_FAILED", Level.HIGH,
                    "最近一次任务有分片失败但整体仍被标为成功，差异数不可信（要按分片重跑，不能整批重跑）"));
        }
        if (trend.status() == JobStatus.FAILED) {
            found.add(new Finding(trend.ruleCode(), "JOB_FAILED", Level.HIGH,
                    "最近一次任务失败，环比基线停在更早那次任务上"));
        }
        if (recent.size() < 2) {
            found.add(new Finding(trend.ruleCode(), "NO_BASELINE", Level.INFO,
                    "只有一次生产任务，环比无意义（第一次跑出来的差异率还不是基线）"));
        }
        return found;
    }

    private boolean isSuppressed(Finding finding, Instant now) {
        Instant previous = lastAlert.get(finding.key());
        if (previous == null) {
            return false;
        }
        return Duration.between(previous, now).toMinutes() < properties.getAlertSuppressMinutes();
    }

    /**
     * 落一条轨迹。
     *
     * <p>M5 不推送，所以"巡检跑过没有、看到了什么"只能从库里查 —— 落库失败不改变巡检结论，
     * 因此这里吞掉异常并 warn：把观测写坏，不该连累观测本身。
     */
    private void persist(PerceptionReport report) {
        try {
            long runId = runs.startRun("定时巡检（最近 " + report.windowJobs() + " 次生产任务）", TRIGGER, "scheduler");
            String payload;
            try {
                payload = objectMapper.writeValueAsString(report);
            } catch (Exception e) {
                payload = report.describe();
            }
            runs.recordStep(runId, runs.nextSeq(runId), "OBSERVE", "perception_scan",
                    null, payload, null, false,
                    report.clean() ? "OK" : "ANOMALY", null);
            runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, null, null, null, null);
            log.info(report.describe());
        } catch (RuntimeException e) {
            log.warn("巡检轨迹写入失败（不影响本次结论）: {}", e.toString(), e);
        }
    }
}
