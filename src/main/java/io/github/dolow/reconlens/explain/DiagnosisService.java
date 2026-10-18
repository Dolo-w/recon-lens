package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务级诊断：一次调用回答"哪里不对 + 疑似原因 + 建议动作"。
 *
 * <h2>为什么要合成一个工具，而不是让模型串四次调用</h2>
 * 外部 Agent 要回答"这任务为什么不正常"，自己去串 {@code recon_job_summary} →
 * {@code recon_list_diffs} → {@code recon_explain_job} → 再自己总结，代价是四次往返
 * 加一次模型总结 —— 而这一步的结论<b>完全由规则决定</b>，不需要模型参与。
 * 把判断留在服务端，结果才是可复现的；让模型总结，同一个任务问两次能得到两种说法。
 *
 * <h2>成本必须为零</h2>
 * 归因走 {@link ExplainService#cheapExplain}：知识库 → 模板，<b>不调 LLM、不落库</b>。
 * 诊断是运维随手点的按钮，它不该成为账单上最大的一行。
 *
 * <h2>它不下结论，只给证据和建议</h2>
 * 每条 {@link Suggestion} 都带 {@code reason}（依据哪一个事实）。"某类差异占 78%"是事实，
 * "因此是对方推送漏了"是猜测 —— 前者进 {@link Fact}，后者只能以"疑似"的口吻出现在建议里。
 */
@Component
public class DiagnosisService {

    /** 一句话结论的枚举：让调用方不必解析中文。 */
    public enum Verdict {
        /** 一切正常，没有可诊断的问题 */
        HEALTHY,
        /** 能跑完但有需要看的信号（差异集中、高严重度偏多） */
        ATTENTION,
        /** 跑挂了或数据不可信（分片失败、单侧无数据） */
        BROKEN
    }

    public record Fact(String kind, String detail) {
    }

    public record Suggestion(String action, String reason) {
    }

    /** 一条差异的零成本归因，给"疑似原因"用。 */
    public record Sample(long diffId, String diffType, String severity, String bizKey, String explanation) {
    }

    public record Diagnosis(long jobId,
                            String ruleCode,
                            String status,
                            Verdict verdict,
                            String summary,
                            List<Fact> facts,
                            List<Suggestion> suggestions,
                            List<Sample> samples) {

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("任务 #").append(jobId).append("（").append(ruleCode).append("，").append(status).append("）：")
                    .append(summary);
            if (!samples.isEmpty()) {
                sb.append("\n疑似原因（按类型抽样）：");
                for (Sample sample : samples) {
                    sb.append("\n  - [").append(sample.diffType()).append('/').append(sample.severity()).append("] ")
                            .append(sample.bizKey()).append("：").append(sample.explanation());
                }
            }
            if (!suggestions.isEmpty()) {
                sb.append("\n建议动作：");
                for (Suggestion suggestion : suggestions) {
                    sb.append("\n  - ").append(suggestion.action()).append("（依据：").append(suggestion.reason()).append("）");
                }
            }
            return sb.toString();
        }
    }

    private static final int MAX_SAMPLES = 3;
    private static final int SAMPLE_CANDIDATES = 200;

    private final ReconRepository repository;
    private final ReconEngine engine;
    private final ExplainService explainService;

    public DiagnosisService(ReconRepository repository, ReconEngine engine, ExplainService explainService) {
        this.repository = repository;
        this.engine = engine;
        this.explainService = explainService;
    }

    public Diagnosis diagnose(long jobId) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + jobId));

        List<Fact> facts = new ArrayList<>();
        List<Suggestion> suggestions = new ArrayList<>();
        Verdict verdict = Verdict.HEALTHY;

        facts.add(new Fact("状态", job.status().name()));
        if (job.errorMsg() != null && !job.errorMsg().isBlank()) {
            facts.add(new Fact("错误信息", job.errorMsg()));
        }
        facts.add(new Fact("行数", String.format("左 %d / 右 %d / 匹配 %d / 差异 %d",
                job.leftRows(), job.rightRows(), job.matchedRows(), job.diffRows())));
        if (job.shardCount() > 0) {
            facts.add(new Fact("分片进度", job.shardDone() + "/" + job.shardCount()));
        }

        // 1) 分片失败：比 status 更早暴露问题的地方 —— PARTIAL_FAILED 恰恰是"看起来成功"的那一档
        List<ReconShard> shards = repository.listShards(jobId);
        List<ReconShard> failed = shards.stream().filter(s -> s.status() == ShardStatus.FAILED).toList();
        if (!failed.isEmpty()) {
            verdict = Verdict.BROKEN;
            facts.add(new Fact("失败分片", failed.size() + " 个：" + failed.stream()
                    .map(s -> "#" + s.shardId()).reduce((a, b) -> a + ", " + b).orElse("")));
            suggestions.add(new Suggestion(
                    "先看失败分片的 error_msg，修好后用 recon_run 的 onlyFailed 只补跑失败分片",
                    "有 " + failed.size() + " 个分片失败，本任务的差异数缺了这部分"));
        }

        // 2) 两侧都没取到数：最常见的"跑成功了但什么都没比"
        if (job.leftRows() == 0 && job.rightRows() == 0) {
            verdict = Verdict.BROKEN;
            facts.add(new Fact("取数", "左右两侧都是 0 行"));
            suggestions.add(new Suggestion(
                    "检查 batch_id / 时间窗 / 过滤条件是否真的能命中数据，再确认模型表是否已构建",
                    "两侧 0 行时，差异为 0 不代表账是平的，只代表没比"));
        } else if (job.leftRows() == 0 || job.rightRows() == 0) {
            verdict = Verdict.BROKEN;
            facts.add(new Fact("取数", "单侧为 0 行"));
            suggestions.add(new Suggestion(
                    "先确认少数据那一侧的表名/时间字段是否配错，再重跑",
                    "单侧 0 行会让整批数据都变成单边缺失，差异类型会全落在 MISSING_*"));
        }

        // 3) 差异结构：类型分布 + 严重度分布
        Map<String, Long> byType = repository.summaryByType(jobId);
        Map<String, Long> bySeverity = repository.summaryBySeverity(jobId);
        if (!byType.isEmpty()) {
            facts.add(new Fact("差异类型", render(byType)));
        }
        if (!bySeverity.isEmpty()) {
            facts.add(new Fact("严重度", render(bySeverity)));

            long total = bySeverity.values().stream().mapToLong(Long::longValue).sum();
            long high = bySeverity.getOrDefault("HIGH", 0L);
            double highShare = total == 0 ? 0 : (double) high / total;
            if (highShare >= 0.3) {
                if (verdict == Verdict.HEALTHY) {
                    verdict = Verdict.ATTENTION;
                }
                suggestions.add(new Suggestion(
                        "优先处理 HIGH 差异：先按 diff_type 分组看是不是同一批业务键，再决定补跑还是人工核",
                        String.format("高严重度占 %.0f%%", highShare * 100)));
            }
        }

        // 差异集中在单一类型 → 大概率一个病因，不是一个一个查
        Map.Entry<String, Long> dominant = byType.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        long diffTotal = byType.values().stream().mapToLong(Long::longValue).sum();
        if (dominant != null && diffTotal > 0) {
            double share = (double) dominant.getValue() / diffTotal;
            if (share >= 0.5 && byType.size() > 1) {
                if (verdict == Verdict.HEALTHY) {
                    verdict = Verdict.ATTENTION;
                }
                suggestions.add(new Suggestion(
                        "集中排查 " + dominant.getKey() + " 这一类（抽查 20 条业务键就够了），不要逐条看差异",
                        String.format("%s 占差异总数的 %.0f%%", dominant.getKey(), share * 100)));
            }
        }

        if (job.diffRows() == 0 && verdict == Verdict.HEALTHY) {
            facts.add(new Fact("差异", "0 条"));
            suggestions.add(new Suggestion(
                    "用 recon_job_summary 抽查两侧行数是否为预期量级",
                    "无差异既可能是账平，也可能是两侧取到了同一份数据"));
        }

        // 4) 抽样归因（零成本）：知识库 → 模板
        List<Sample> samples = samples(job, byType);

        String summary = switch (verdict) {
            case HEALTHY -> "没有发现异常信号";
            case ATTENTION -> "任务能跑完，但有需要人工看一眼的信号";
            case BROKEN -> "任务结果不可信，「无差异」不能当成账平";
        };
        return new Diagnosis(jobId, job.ruleCode(), job.status().name(), verdict, summary, facts, suggestions, samples);
    }

    /**
     * 每个差异类型抽一条。
     *
     * <p>按类型抽而不是取最新的三条：三条 {@code AMOUNT_MISMATCH} 会让人以为只有金额问题，
     * 而真正的新情况可能藏在只占 5% 的另一个类型里。
     */
    private List<Sample> samples(ReconJob job, Map<String, Long> byType) {
        if (byType.isEmpty()) {
            return List.of();
        }
        RuleSpec rule;
        try {
            rule = engine.ruleForJob(job);
        } catch (RuntimeException e) {
            // 规则快照解析不了时，诊断的其他部分仍然有价值，不要整条链路陪葬
            return List.of();
        }
        List<Sample> samples = new ArrayList<>();
        List<DiffRecord> candidates = repository.listDiffs(job.id(), null, 0, SAMPLE_CANDIDATES);
        Map<String, DiffRecord> firstOfType = new LinkedHashMap<>();
        for (DiffRecord diff : candidates) {
            firstOfType.putIfAbsent(diff.diffType(), diff);
        }
        for (DiffRecord diff : firstOfType.values()) {
            if (samples.size() >= MAX_SAMPLES) {
                break;
            }
            samples.add(new Sample(diff.id(), diff.diffType(), diff.severity().name(), diff.bizKey(),
                    explainService.cheapExplain(rule, job.ruleCode(), diff)));
        }
        return samples;
    }

    private static String render(Map<String, Long> counts) {
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(sb.isEmpty() ? "" : "，").append(e.getKey()).append('=').append(e.getValue()));
        return sb.toString();
    }
}
