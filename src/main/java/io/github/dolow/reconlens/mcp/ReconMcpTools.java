package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleRegistry;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具的落地实现：Agent 通过这些方法跑对账、查差异、拿归因。
 *
 * <p>这一层只做"把引擎能力翻译成人和模型都读得懂的文本"，不引入任何对账逻辑：
 * 规则来自 {@link RuleRegistry}，执行走 {@link ReconEngine}，查询走 {@link ReconRepository}，
 * 归因走 {@link ExplainService} —— 与 REST 接口共用同一套服务，所以两边结果永远一致。
 */
@Component
public class ReconMcpTools {

    private final RuleRegistry rules;
    private final ReconEngine engine;
    private final ReconRepository repository;
    private final ExplainService explain;

    public ReconMcpTools(RuleRegistry rules, ReconEngine engine, ReconRepository repository, ExplainService explain) {
        this.rules = rules;
        this.engine = engine;
        this.repository = repository;
        this.explain = explain;
    }

    /** 列出已加载的规则，让模型自己挑 ruleCode。 */
    public String listRules() {
        List<RuleSpec> all = rules.all();
        StringBuilder sb = new StringBuilder();
        sb.append("已加载 ").append(all.size()).append(" 条对账规则，调 recon_run 时把 code 作为 ruleCode：\n");
        for (RuleSpec rule : all) {
            sb.append("\n- ").append(rule.code());
            if (rule.name() != null && !rule.name().isBlank()) {
                sb.append("（").append(rule.name()).append("）");
            }
            sb.append('\n');
            if (rule.description() != null && !rule.description().isBlank()) {
                sb.append("    说明：").append(rule.description()).append('\n');
            }
            sb.append("    左侧：").append(rule.left().describe()).append('\n');
            sb.append("    右侧：").append(rule.right().describe()).append('\n');
            sb.append("    比对字段：").append(rule.compare().stream().map(FieldRule::field).collect(Collectors.joining("、"))).append('\n');
            sb.append("    分片 ").append(rule.shard().count())
                    .append("，并行 ").append(rule.shard().effectiveParallelism())
                    .append("，单分片最多重试 ").append(rule.maxAttempts()).append(" 次");
        }
        return sb.toString();
    }

    /** 同步跑一次对账，直接返回任务摘要。 */
    public String runReconciliation(String ruleCode, String batchId, String remark) {
        RuleSpec rule = rules.get(ruleCode);
        String batch = blankToNull(batchId);
        if (batch == null) {
            batch = "mcp-" + System.currentTimeMillis();
        }
        String note = blankToNull(remark) == null ? "由 MCP 工具发起" : remark;
        ReconJob job = engine.run(rule.code(), batch, note);
        return jobSummary(job.id()) + "\n下一步：recon_list_diffs 看差异明细，recon_explain_job 生成归因。";
    }

    /** 任务总览：状态、扫描量、差异分类、分片明细。 */
    public String jobSummary(long jobId) {
        ReconJob job = repository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + jobId));
        StringBuilder sb = new StringBuilder();
        sb.append("任务 #").append(job.id())
                .append("｜规则 ").append(job.ruleCode())
                .append("｜批次 ").append(orNone(job.batchId()))
                .append("｜状态 ").append(job.status()).append('\n');
        sb.append("分片 ").append(job.shardDone()).append('/').append(job.shardCount()).append(" 完成")
                .append("｜扫描 左=").append(job.leftRows()).append(" 右=").append(job.rightRows())
                .append("｜匹配 ").append(job.matchedRows())
                .append("｜差异 ").append(job.diffRows()).append('\n');
        sb.append("差异分类：").append(formatCounts(repository.summaryByType(jobId))).append('\n');
        sb.append("严重级别：").append(formatCounts(repository.summaryBySeverity(jobId))).append('\n');
        List<ReconShard> shards = repository.listShards(jobId);
        if (!shards.isEmpty()) {
            sb.append("分片明细：").append(shards.stream()
                    .map(s -> "#" + s.shardId() + " " + s.status()
                            + "(尝试" + s.attempts() + ", 左" + s.scannedLeft() + "/右" + s.scannedRight()
                            + ", 差异" + s.diffRows() + ")")
                    .collect(Collectors.joining("；"))).append('\n');
        }
        if (job.errorMsg() != null && !job.errorMsg().isBlank()) {
            sb.append("失败原因：").append(job.errorMsg()).append('\n');
        }
        if (job.startedAt() != null && job.finishedAt() != null) {
            sb.append("耗时 ").append(java.time.Duration.between(job.startedAt(), job.finishedAt()).toMillis()).append(" ms\n");
        }
        return sb.toString();
    }

    /** 分页看差异明细。 */
    public String listDiffs(long jobId, String diffType, int offset, int limit) {
        int size = limit <= 0 ? 50 : Math.min(limit, 500);
        int from = Math.max(offset, 0);
        String type = blankToNull(diffType);
        long total = repository.countDiffs(jobId, type);
        List<DiffRecord> diffs = repository.listDiffs(jobId, type, from, size);
        StringBuilder sb = new StringBuilder();
        sb.append("任务 #").append(jobId).append("｜差异共 ").append(total).append(" 条");
        if (type != null) {
            sb.append("（只看 ").append(type).append("）");
        }
        sb.append("，本次返回 ").append(diffs.size()).append(" 条（offset=").append(from).append("）：\n");
        for (DiffRecord diff : diffs) {
            sb.append("#").append(diff.id()).append(" [").append(diff.severity()).append("] ").append(diff.diffType())
                    .append(" 业务键=").append(diff.bizKey());
            if (diff.field() != null && !diff.field().isBlank()) {
                sb.append(" 字段=").append(diff.field());
            }
            sb.append(" 左=").append(orNone(diff.leftValue())).append(" → 右=").append(orNone(diff.rightValue()));
            if (diff.deltaRatio() != null) {
                sb.append("（相对偏差 ").append(diff.deltaRatio().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()).append("%）");
            }
            if (diff.explanation() != null && !diff.explanation().isBlank()) {
                sb.append("\n    归因：").append(diff.explanation());
            }
            sb.append('\n');
        }
        long rest = total - diffs.size() - from;
        if (rest > 0) {
            sb.append("（还有 ").append(rest).append(" 条：调大 limit 或换 offset 继续看）\n");
        }
        return sb.toString();
    }

    /** 给单条差异生成归因（LLM 不可用时自动降级为规则模板）。 */
    public String explainDiff(long diffId) {
        DiffRecord diff = repository.findDiff(diffId)
                .orElseThrow(() -> new IllegalArgumentException("差异不存在：" + diffId));
        String text = explain.explain(diffId);
        return "差异 #" + diffId + "（" + diff.diffType() + "，业务键 " + diff.bizKey() + "）的归因，解释器=" + explain.llmName() + "：\n" + text;
    }

    /** 给整个任务批量补归因，并回显前几条。 */
    public String explainJob(long jobId, int limit) {
        int size = limit <= 0 ? 50 : Math.min(limit, 500);
        int done = explain.explainJob(jobId, size);
        StringBuilder sb = new StringBuilder();
        sb.append("任务 #").append(jobId).append(" 已生成 ").append(done).append(" 条归因")
                .append("（解释器=").append(explain.llmName())
                .append(explain.llmAvailable() ? "，LLM 可用" : "，LLM 未启用或未配置，已用规则模板降级").append("）\n");
        for (DiffRecord diff : repository.listDiffs(jobId, null, 0, 5)) {
            sb.append("#").append(diff.id()).append(" ").append(diff.diffType())
                    .append(" 业务键=").append(diff.bizKey())
                    .append("：").append(orNone(diff.explanation())).append('\n');
        }
        return sb.toString();
    }

    private static String formatCounts(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return "（无）";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("，"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }
}
