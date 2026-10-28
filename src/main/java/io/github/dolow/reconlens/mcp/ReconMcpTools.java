package io.github.dolow.reconlens.mcp;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.DiagnosisService;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.model.ModelBuilder;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具的落地实现：Agent 通过这些方法跑对账、查差异、拿归因。
 *
 * <p>这一层只做"把引擎能力翻译成人和模型都读得懂的文本"，不引入任何对账逻辑：
 * 规则来自 {@link RuleCatalog}，执行走 {@link ReconEngine}，查询走 {@link ReconRepository}，
 * 归因走 {@link ExplainService} —— 与 REST 接口共用同一套服务，所以两边结果永远一致。
 */
@Component
public class ReconMcpTools {

    private final RuleCatalog catalog;
    private final ReconEngine engine;
    private final ReconRepository repository;
    private final ExplainService explain;
    private final ModelBuilder modelBuilder;
    private final DiagnosisService diagnosis;
    /**
     * 弱依赖的试算触发口（rule 包接口，agent 装配提供实现）：
     * agent 关闭时没有实现 Bean，define_rule 落草稿但不试算。
     */
    private final org.springframework.beans.factory.ObjectProvider<io.github.dolow.reconlens.rule.RuleTrialRunner> trialRunner;
    /**
     * 试算排队口（同样弱依赖）：有它就把试算丢后台、立刻返回会话号；
     * 没有它（Agent 未启用）就退回同步试算 —— 慢，但至少能定义规则。
     */
    private final org.springframework.beans.factory.ObjectProvider<io.github.dolow.reconlens.rule.RuleTrialScheduler> trialScheduler;

    public ReconMcpTools(RuleCatalog catalog, ReconEngine engine, ReconRepository repository, ExplainService explain,
                         ModelBuilder modelBuilder, DiagnosisService diagnosis,
                         org.springframework.beans.factory.ObjectProvider<io.github.dolow.reconlens.rule.RuleTrialRunner> trialRunner,
                         org.springframework.beans.factory.ObjectProvider<io.github.dolow.reconlens.rule.RuleTrialScheduler> trialScheduler) {
        this.catalog = catalog;
        this.engine = engine;
        this.repository = repository;
        this.explain = explain;
        this.modelBuilder = modelBuilder;
        this.diagnosis = diagnosis;
        this.trialRunner = trialRunner;
        this.trialScheduler = trialScheduler;
    }

    /**
     * 任务级诊断：一次返回"哪里不对 + 疑似原因 + 建议动作"。
     *
     * <p><b>不依赖 Agent 开关</b>：诊断读的是已经存在的任务数据，和"要不要有 Agent"无关。
     * 如果把它挂在 {@code recon.agent.enabled} 后面，"这任务为什么挂了"这个问题
     * 就得先打开一个开关才能问 —— 那是把可观测性和实验性功能绑在一起。
     */
    public ReconMcpTools.DiagnosisView diagnose(long jobId) {
        DiagnosisService.Diagnosis result = diagnosis.diagnose(jobId);
        Map<String, Object> view = new LinkedHashMap<>();
        McpToolFactory.put(view, "jobId", result.jobId());
        McpToolFactory.put(view, "ruleCode", result.ruleCode());
        McpToolFactory.put(view, "status", result.status());
        McpToolFactory.put(view, "verdict", result.verdict().name());
        McpToolFactory.put(view, "summary", result.summary());
        McpToolFactory.put(view, "facts", result.facts().stream()
                .map(fact -> Map.of("kind", fact.kind(), "detail", fact.detail())).toList());
        McpToolFactory.put(view, "suggestions", result.suggestions().stream()
                .map(item -> Map.of("action", item.action(), "reason", item.reason())).toList());
        McpToolFactory.put(view, "samples", result.samples().stream()
                .map(sample -> Map.of("diffId", sample.diffId(), "diffType", sample.diffType(),
                        "severity", sample.severity(), "bizKey", sample.bizKey(),
                        "explanation", sample.explanation()))
                .toList());
        return new DiagnosisView(result.describe(), view);
    }

    /** 诊断的工具返回值：人读文本 + 结构化视图。 */
    public record DiagnosisView(String text, Map<String, Object> view) {
    }

    /** 列出规则库里的规则，让模型自己挑 ruleCode。 */
    public String listRules() {
        List<RuleCatalog.Resolved> all = catalog.allResolved();
        StringBuilder sb = new StringBuilder();
        sb.append("规则库里有 ").append(all.size()).append(" 条对账规则，调 recon_run 时把 code 作为 ruleCode：\n");
        for (RuleCatalog.Resolved resolved : all) {
            RuleSpec rule = resolved.spec();
            sb.append("\n- ").append(rule.code());
            if (resolved.version() != null) {
                sb.append("（v").append(resolved.version()).append("）");
            }
            if (rule.name() != null && !rule.name().isBlank()) {
                sb.append(" ").append(rule.name());
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
        sb.append("\n\n要改对账维度或比对字段，调 recon_define_rule 提交新版本 —— 落为草稿并自动跑影子试算，")
          .append("审批（recon_submit_approval）通过后才生效，不用改代码、不用重启。");
        return sb.toString();
    }

    /**
     * 起草的结果事实。
     *
     * <p>会话侧按它写「人话回执」，MCP 侧按它拼「给 agent 的操作指引」——两边共用同一份事实，
     * 但不共用同一段文字：给 agent 的「下一步调 recon_trial_status」不能出现在人机会话里。
     */
    public record DraftReceipt(String code, int version, String operator,
                               String leftDesc, String rightDesc, String dimension, String compares,
                               Long runId, String trialState, String trialDetail) {

        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            out.put("version", version);
            out.put("operator", operator);
            out.put("dimension", dimension);
            out.put("compares", compares);
            out.put("trialState", trialState);
            if (runId != null) {
                out.put("runId", runId);
            }
            if (trialDetail != null) {
                out.put("trialDetail", trialDetail);
            }
            return out;
        }

        /** 给 MCP 宿主大模型看的回执：带工具名与接口路径，便于它接着调。 */
        public String agentText() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("规则 ").append(code).append(" 已落库为草稿 v").append(version)
                    .append("（来源 MCP，操作人 ").append(operator).append("）。")
                    .append("草稿不生效，recon_run 现在还用不了它；对账维度：左 ").append(leftDesc)
                    .append("，右 ").append(rightDesc).append("，比对字段 ").append(compares).append("。\n");
            switch (trialState) {
                case "queued" -> sb.append("影子试算已排队（会话 #").append(runId).append("，单次约 1 分钟）；")
                        .append("结论用 recon_trial_status 查（ruleCode/version 或 runId 都能定位），不要重复提交。\n");
                case "agent-disabled" -> sb.append("影子试算未跑：Agent 能力未启用（recon.agent.enabled=false），")
                        .append("请启用后重新提交，或由人在 REST 上跑试算。\n");
                case "done" -> sb.append("影子试算已完成：可批性 ").append(trialDetail).append("。\n");
                case "rejected" -> sb.append("影子试算未跑成（闸门拒绝）：").append(trialDetail)
                        .append("\n规则仍是草稿，修正后可再提交新版本。\n");
                default -> sb.append("影子试算未排队：").append(trialDetail)
                        .append("\n规则已落库为草稿，稍后可重试试算。\n");
            }
            sb.append("要生效：先把试算结论讲给用户，用户确认后由用户领取一次性确认码")
                    .append("（POST /api/rule-reviews/").append(code).append("/").append(version)
                    .append("/confirmation），再调 recon_submit_approval（ruleCode=").append(code)
                    .append(", version=").append(version)
                    .append(", decision=APPROVED, confirmedBy=用户标识, confirmationCode=用户给你的码）。")
                    .append("未启用 Agent 工具时，也可由人在 REST 审批接口走 submit → approve。");
            return sb.toString();
        }
    }

    /**
     * 定义/修改一条规则：body 是规则文件里的那份配置（JSON 或 YAML 都行）。
     *
     * <p><b>MCP 通路不允许直通生效</b>：落库为 DRAFT 草稿并自动跑影子试算，
     * 审批（{@code recon_submit_approval}）通过前 {@code recon_run} 用不了这条规则。
     * 直通 ACTIVE 的口子只留给 REST legacy（api-key + 显式开关 + warn 留痕）与种子导入 ——
     * 否则宿主大模型随手一次 {@code recon_define_rule} 就把三道闸门整个绕过去了。
     */
    public String defineRule(String ruleBody, String operator) {
        return draft(ruleBody, operator).agentText();
    }

    /** 只做"落草稿 + 排试算"，产出结构化事实；文字由调用方按读者挑。 */
    public DraftReceipt draft(String ruleBody, String operator) {
        String body = blankToNull(ruleBody);
        if (body == null) {
            throw new IllegalArgumentException("ruleBody 不能为空");
        }
        String who = blankToNull(operator) == null ? "mcp" : operator;
        RuleCatalog.Resolved resolved = catalog.defineDraftText(body, "MCP 提交的规则", who,
                RuleOrigin.MCP, null, "通过 MCP 接口提交（操作人 " + who + "）");
        RuleSpec spec = resolved.spec();
        String compares = spec.compare().stream()
                .map(FieldRule::field).collect(Collectors.joining("、"));
        String dimension = spec.left().dimensionColumns() == null || spec.left().dimensionColumns().isEmpty()
                ? spec.left().keyField()
                : String.join("+", spec.left().dimensionColumns());
        Long runId = null;
        String trialState;
        String trialDetail = null;
        try {
            io.github.dolow.reconlens.rule.RuleTrialScheduler scheduler = trialScheduler.getIfAvailable();
            io.github.dolow.reconlens.rule.RuleTrialRunner runner = trialRunner.getIfAvailable();
            if (scheduler != null) {
                runId = scheduler.submit(spec.code(), resolved.version(), who);
                trialState = "queued";
            } else if (runner == null) {
                trialState = "agent-disabled";
            } else {
                io.github.dolow.reconlens.rule.RuleTrialRunner.Result result =
                        runner.run(spec.code(), resolved.version());
                trialState = "done";
                trialDetail = result.state().approval() + "，结论 " + describeTrial(result.state());
            }
        } catch (RuleException e) {
            trialState = "rejected";
            trialDetail = e.getMessage();
        } catch (IllegalStateException e) {
            trialState = "failed";
            trialDetail = e.getMessage();
        }
        return new DraftReceipt(spec.code(), resolved.version(), who,
                spec.left().describe(), spec.right().describe(), dimension, compares,
                runId, trialState, trialDetail);
    }

    private String describeTrial(TrialState state) {
        if (state instanceof TrialState.Trialed t) {
            return t.verdict() + " / 覆盖 " + t.coverage();
        }
        if (state instanceof TrialState.Skipped s) {
            return "SKIPPED（" + s.skipReason() + "）";
        }
        return "未执行";
    }

    /** 同步跑一次对账，直接返回任务摘要。ruleJson 非空时用内联规则（不落规则库）。 */
    public String runReconciliation(String ruleCode, String ruleJson, String batchId, String remark) {
        return runWithView(ruleCode, ruleJson, batchId, remark).text();
    }

    /** 一次调用同时产出"人话"与结构化视图：分开调等于把对账跑两遍。 */
    public record RunOutcome(String text, Map<String, Object> view) {
    }

    public RunOutcome runWithView(String ruleCode, String ruleJson, String batchId, String remark) {
        RuleCatalog.Resolved resolved = resolveRun(ruleCode, ruleJson);
        String batch = blankToNull(batchId);
        if (batch == null) {
            batch = "mcp-" + System.currentTimeMillis();
        }
        String note = blankToNull(remark) == null ? "由 MCP 工具发起" : remark;
        ReconJob job = engine.run(resolved, batch, note);
        String rule = resolved.spec().code() + "（" + (resolved.version() == null ? "内联规则" : "v" + resolved.version()) + "）";
        String text = "规则 " + rule + "\n" + jobSummary(job.id());

        Map<String, Object> view = new LinkedHashMap<>();
        McpToolFactory.put(view, "ruleCode", resolved.spec().code());
        McpToolFactory.put(view, "ruleVersion", resolved.version());
        McpToolFactory.put(view, "jobId", job.id());
        McpToolFactory.put(view, "batchId", job.batchId());
        McpToolFactory.put(view, "status", job.status().name());
        McpToolFactory.put(view, "diffRows", job.diffRows());
        McpToolFactory.put(view, "matchedRows", job.matchedRows());
        McpToolFactory.put(view, "leftRows", job.leftRows());
        McpToolFactory.put(view, "rightRows", job.rightRows());
        McpToolFactory.put(view, "diffByType", repository.summaryByType(job.id()));
        McpToolFactory.put(view, "diffBySeverity", repository.summaryBySeverity(job.id()));
        McpToolFactory.put(view, "errorMsg", job.errorMsg());
        return new RunOutcome(text, view);
    }

    private RuleCatalog.Resolved resolveRun(String ruleCode, String ruleJson) {
        String inline = blankToNull(ruleJson);
        if (inline != null) {
            return inline.startsWith("{")
                    ? catalog.inlineJson(inline, "MCP 内联的 JSON 规则")
                    : catalog.inlineYaml(inline, "MCP 内联的 YAML 规则");
        }
        String code = blankToNull(ruleCode);
        if (code == null) {
            throw new IllegalArgumentException("ruleCode 不能为空（也可以用 ruleJson 内联一份规则，或用 recon_list_rules 看有哪些规则）");
        }
        return catalog.resolve(code);
    }

    /** 任务总览：状态、扫描量、差异分类、分片明细。 */
    public String jobSummary(long jobId) {
        return jobSummaryWithView(jobId).text();
    }

    /** 同一次查询产出"人话"与结构化视图（MCP 的 structuredContent 用后者）。 */
    public RunOutcome jobSummaryWithView(long jobId) {
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
        Map<String, Long> byType = repository.summaryByType(jobId);
        Map<String, Long> bySeverity = repository.summaryBySeverity(jobId);
        sb.append("差异分类：").append(formatCounts(byType)).append('\n');
        sb.append("严重级别：").append(formatCounts(bySeverity)).append('\n');
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
        Long durationMs = null;
        if (job.startedAt() != null && job.finishedAt() != null) {
            durationMs = java.time.Duration.between(job.startedAt(), job.finishedAt()).toMillis();
            sb.append("耗时 ").append(durationMs).append(" ms\n");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        McpToolFactory.put(view, "jobId", job.id());
        McpToolFactory.put(view, "ruleCode", job.ruleCode());
        McpToolFactory.put(view, "batchId", job.batchId());
        McpToolFactory.put(view, "status", job.status().name());
        McpToolFactory.put(view, "shardCount", job.shardCount());
        McpToolFactory.put(view, "shardDone", job.shardDone());
        McpToolFactory.put(view, "leftRows", job.leftRows());
        McpToolFactory.put(view, "rightRows", job.rightRows());
        McpToolFactory.put(view, "matchedRows", job.matchedRows());
        McpToolFactory.put(view, "diffRows", job.diffRows());
        McpToolFactory.put(view, "diffByType", byType);
        McpToolFactory.put(view, "diffBySeverity", bySeverity);
        McpToolFactory.put(view, "failedShards", shards.stream()
                .filter(s -> s.status() == ShardStatus.FAILED)
                .map(ReconShard::shardId).toList());
        McpToolFactory.put(view, "durationMs", durationMs);
        McpToolFactory.put(view, "errorMsg", job.errorMsg());
        return new RunOutcome(sb.toString(), view);
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
            // 直接给出下一个 offset：模型照着填就行，不必自己算 "from + size"
            sb.append("（还有 ").append(rest).append(" 条；继续看用 offset=")
                    .append(from + diffs.size()).append("）\n");
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

    /**
     * 预处理：把规则声明的扫描 + 汇总提前做一次，落成模型表并注册 {@code <code>__MODEL} 规则。
     * 适合"源数据变化慢、对账跑得频繁"的场景；代价是模型表与源表可能不同步（新鲜度）。
     */
    public String buildModel(String ruleCode, Integer shards, String operator) {
        String code = blankToNull(ruleCode);
        if (code == null) {
            throw new IllegalArgumentException("ruleCode 不能为空，先用 recon_list_rules 看有哪些规则");
        }
        ModelBuilder.ModelBuild build = modelBuilder.build(code, shards, operator);
        StringBuilder sb = new StringBuilder();
        sb.append("已为规则 ").append(build.ruleCode()).append("（v").append(build.ruleVersion())
                .append("）建好模型表，分片 ").append(build.shards()).append("：\n");
        for (ModelBuilder.ModelBuild.Table table : build.tables()) {
            sb.append("- ").append(table.side()).append(" 侧：").append(table.table())
                    .append("，").append(table.rows()).append(" 行，构建耗时 ").append(table.buildMillis()).append(" ms\n");
        }
        sb.append("已注册规则 ").append(build.modelRuleCode()).append("（v").append(build.modelRuleVersion())
                .append("，状态 ").append(build.modelRuleLifecycle()).append("）");
        if ("ACTIVE".equals(build.modelRuleLifecycle())) {
            sb.append("，现在调 recon_run 传这个 code 就是对模型表做对账，差异口径与原规则一致；");
        } else {
            sb.append("，草稿：还要走试算 + 人工审批，审批之前 recon_run 会拒绝执行 —— "
                    + "这条闸门不该被脚本绕过，要跑请先让人批；");
        }
        sb.append("源数据变化后要重新构建（再次调 recon_build_model）模型表才是新的。");
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
