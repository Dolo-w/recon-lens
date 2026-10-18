package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.Errors;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.llm.PromptGuard;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.rule.SpecHasher;
import io.github.dolow.reconlens.store.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次会话的四阶段状态机：感知 → 规划 → 行动 → 沉淀（设计 §4）。
 *
 * <h2>为什么"规划"必须是一个独立阶段</h2>
 * 因为它的核心产出是<b>一次判断：要不要用 LLM</b>。
 * 现有规则能回答的问题（{@code ruleCode} 命中、维度对得上）走确定性路径，
 * 零 LLM 调用、零 token、结果可复现 —— 这是成本分层里的 L0，也是整套成本模型里唯一
 * "运行次数越多越省"的结构性来源。把这一步揉进"行动"里，很容易退化成"每次都问一下模型"。
 *
 * <h2>三条硬边界</h2>
 * <ol>
 *   <li><b>绝不调用审批</b>：会话最多把规则推到 {@code DRAFT} + 试算完成，剩下的一步必须由人在
 *       REST 上做。{@code AgentBoundaryTest} 里有一条断言 agent 包连 {@code RuleLifecycleService}
 *       都不许依赖 —— 这条约束写进注释会被慢慢磨掉，写成测试才会拦住人；</li>
 *   <li><b>失败不向外抛</b>：预算耗尽落 {@code BLOCKED}，其它异常落 {@code FAILED}，
 *       都带着 {@code recon_agent_step} 的完整轨迹返回。MCP 工具最不需要的就是一个 500；</li>
 *   <li><b>每一步都落库</b>：{@code payload} 可截断（有 {@code truncated} 标记），{@code digest} 是真哈希。
 *       没有轨迹的会话不可复盘，而"AI 改了规则、没人知道它当时看到了什么"是最难查的一类事故。</li>
 * </ol>
 */
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /**
     * 单步 payload 的上限。超了截断并置 {@code truncated}：
     * 轨迹的用途是"能看懂发生了什么"，不是"完整存档" —— 后者应该走对象存储，
     * 塞进 TEXT 列的结果是某天写库失败（1406）。
     */
    private static final int MAX_STEP_PAYLOAD = 6000;

    /** LEARN 阶段一次最多补多少条归因。会话不是批处理，补几条看趋势就够了。 */
    private static final int LEARN_DIFF_LIMIT = 20;

    private final AgentRunRepository runs;
    private final RuleCatalog catalog;
    private final ReconEngine engine;
    private final ShadowTrialService trialService;
    private final RuleDrafter drafter;
    private final ExplainService explain;
    private final AgentProperties properties;
    private final ObjectMapper json;

    public AgentOrchestrator(AgentRunRepository runs, RuleCatalog catalog, ReconEngine engine,
                             ShadowTrialService trialService, RuleDrafter drafter,
                             ExplainService explain, AgentProperties properties, ObjectMapper json) {
        this.runs = runs;
        this.catalog = catalog;
        this.engine = engine;
        this.trialService = trialService;
        this.drafter = drafter;
        this.explain = explain;
        this.properties = properties;
        this.json = json;
    }

    /** 一次会话的产出。{@code status} 用的是落库值，便于调用方直接对照 {@code recon_agent_run}。 */
    public record AgentRunResult(long runId,
                                 AgentRunRepository.RunStatus status,
                                 String ruleCode,
                                 Integer draftVersion,
                                 Long jobId,
                                 String message,
                                 RuleDraft draft,
                                 ShadowTrialService.TrialResult trial,
                                 List<String> violations) {
    }

    // ------------------------------------------------------------------

    /** 观察结果：规划阶段的唯一输入。 */
    private record Observation(boolean ruleExists, Integer latestVersion, List<String> rules, String note) {

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("规则库 ").append(rules.size()).append(" 条");
            if (ruleExists) {
                sb.append("；目标规则存在，最新 v").append(latestVersion);
            } else {
                sb.append("；目标规则不存在（或未指定）→ 需要起草");
            }
            if (note != null) {
                sb.append("；").append(note);
            }
            return sb.toString();
        }
    }

    private enum Path {
        EXISTING_RULE, DRAFT_RULE
    }

    private record Plan(Path path, String ruleCode, String reason) {

        Map<String, Object> asMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("path", path.name());
            root.put("ruleCode", ruleCode);
            root.put("reason", reason);
            root.put("llmRequired", path == Path.DRAFT_RULE);
            return root;
        }
    }

    private record ActResult(Long jobId, RuleDraft draft, ShadowTrialService.TrialResult trial,
                             String ruleCode, Integer draftVersion, String blockedReason) {

        static ActResult blocked(String reason, RuleDraft draft, String ruleCode, Integer draftVersion) {
            return new ActResult(null, draft, null, ruleCode, draftVersion, reason);
        }
    }

    // ------------------------------------------------------------------

    /**
     * 跑完一次会话（同步）：先开一个会话记录，再执行。
     *
     * <p>拆成 {@code startRun + execute} 两步是为了让异步入口能<b>先拿到 runId</b>：
     * {@code recon_draft_rule} 必须立刻返回一个可以轮询的号，而不是等 20 秒后才知道 ranId。
     */
    public AgentRunResult run(AgentGoal goal, String operator) {
        String who = operator == null || operator.isBlank() ? "agent" : operator;
        long runId = runs.startRun(goal.describe(), goal.trigger().name(), who);
        return execute(runId, goal, who);
    }

    /**
     * 在一个已经建好的会话里执行四阶段。<b>不抛业务异常</b>：
     * 预算耗尽 → BLOCKED，其它异常 → FAILED，两者都带轨迹返回。
     *
     * @param runId    已由 {@link AgentRunRepository#startRun} 创建的会话号
     * @param operator 发起人（落草稿的 {@code created_by}）
     */
    public AgentRunResult execute(long runId, AgentGoal goal, String operator) {
        String who = operator == null || operator.isBlank() ? "agent" : operator;
        AgentBudget budget = AgentBudget.of(properties);
        int[] seq = {0};
        String ruleCode = goal.ruleCode();
        Integer draftVersion = null;
        RuleDraft draft = null;
        try {
            // ① 感知
            long t = System.nanoTime();
            Observation observation = observe(goal);
            recordStep(runId, ++seq[0], "OBSERVE", "inspect-rule-library", goal.describe(),
                    observation.summary(), "OK", t);

            // ② 规划
            t = System.nanoTime();
            Plan plan = plan(goal, observation);
            String planJson = toJson(plan.asMap());
            runs.updatePlan(runId, PromptGuard.scrub(planJson, MAX_STEP_PAYLOAD),
                    planJson.length() > MAX_STEP_PAYLOAD);
            recordStep(runId, ++seq[0], "PLAN", plan.path().name().toLowerCase(),
                    observation.summary(), planJson, "OK", t);

            // ③ 行动
            ActResult act = act(plan, goal, budget, runId, seq, who, observation);
            draft = act.draft();
            if (act.ruleCode() != null) {
                ruleCode = act.ruleCode();
            }
            draftVersion = act.draftVersion();
            if (act.blockedReason() != null) {
                runs.finishRun(runId, AgentRunRepository.RunStatus.BLOCKED, ruleCode, null, draftVersion,
                        act.blockedReason());
                return new AgentRunResult(runId, AgentRunRepository.RunStatus.BLOCKED, ruleCode, draftVersion,
                        null, act.blockedReason(), draft, null,
                        draft == null ? List.of() : draft.lastViolations());
            }

            // ④ 沉淀
            learn(runId, act.jobId(), seq);

            String message = act.jobId() != null
                    ? "已按现有规则跑完对账，任务 #" + act.jobId() + "（差异明细用 recon_list_diffs 看）"
                    : draft != null && draft.drafted()
                    ? "草稿 " + ruleCode + " v" + draftVersion + " 已落库并完成影子试算（"
                    + trialSummary(act.trial()) + "）；审批不由 Agent 执行，"
                    + "请人工走 POST /api/rules/" + ruleCode + "/" + draftVersion + "/approve"
                    : "已完成";
            runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, ruleCode, act.jobId(),
                    draftVersion, null);
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.SUCCESS, ruleCode, draftVersion,
                    act.jobId(), message, draft, act.trial(), List.of());
        } catch (AgentBudget.ExceededException e) {
            // 预算耗尽 = 按规则不该继续，不是故障：落 BLOCKED，让运维看的是"这次被预算拦了"
            log.warn("Agent 会话 #{} 预算耗尽：{}", runId, e.getMessage());
            runs.finishRun(runId, AgentRunRepository.RunStatus.BLOCKED, ruleCode, null, draftVersion, e.getMessage());
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.BLOCKED, ruleCode, draftVersion,
                    null, e.getMessage(), draft, null,
                    draft == null ? List.of() : draft.lastViolations());
        } catch (RuntimeException e) {
            log.error("Agent 会话 #{} 执行失败：{}", runId, Errors.describe(e));
            runs.finishRun(runId, AgentRunRepository.RunStatus.FAILED, ruleCode, null, draftVersion,
                    Errors.describe(e));
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.FAILED, ruleCode, draftVersion,
                    null, "会话执行失败：" + Errors.describe(e), draft, null, List.of());
        }
    }

    // ------------------------------------------------------------------

    private Observation observe(AgentGoal goal) {
        List<String> rules = new ArrayList<>();
        for (RuleCatalog.Resolved resolved : catalog.allResolved()) {
            RuleSpec spec = resolved.spec();
            rules.add(spec.code() + "（v" + resolved.version() + "，" + spec.compare().size() + " 字段，"
                    + spec.shard().count() + " 分片）"
                    + (spec.description() == null || spec.description().isBlank() ? "" : "：" + spec.description()));
        }
        String target = goal.ruleCode();
        if (target == null || target.isBlank()) {
            return new Observation(false, null, rules, null);
        }
        try {
            if (!catalog.exists(target)) {
                return new Observation(false, null, rules, "规则 " + target + " 不在规则库中");
            }
            Integer version = catalog.resolve(target).version();
            return new Observation(true, version, rules, null);
        } catch (RuntimeException e) {
            // resolve 失败（例如审批后内容被改、已降级回 REVIEWING）不该让整个会话崩在这里：
            // 感知阶段的职责是"报告看到了什么"，判死是闸门 3 的事
            return new Observation(false, null, rules, "读取规则 " + target + " 失败：" + Errors.describe(e));
        }
    }

    private Plan plan(AgentGoal goal, Observation observation) {
        if (observation.ruleExists() && goal.wantsReconciliation()) {
            return new Plan(Path.EXISTING_RULE, goal.ruleCode(),
                    "规则库已有 " + goal.ruleCode() + " v" + observation.latestVersion()
                            + "，走确定性路径：零 LLM 调用，结果可复现");
        }
        if (observation.ruleExists()) {
            // 规则已存在但这次会话只要草稿（recon_draft_rule）：拿它当基线改，不要顺手跑生产对账。
            // 一个叫"起草"的动作产生 PROD 任务，是语义越界，也会让巡检的环比基线莫名多出一条。
            return new Plan(Path.DRAFT_RULE, goal.ruleCode(),
                    "已有 " + goal.ruleCode() + " v" + observation.latestVersion()
                            + " 作为基线；本次只起草新版本，不执行对账");
        }
        String reason = goal.rawInput() == null || goal.rawInput().isBlank()
                ? "目标规则不存在，且没有业务描述可起草 → 只能转人工"
                : "现有规则回答不了这个目标，交给 RuleDrafter 起草（受预算与闸门 1 约束）";
        return new Plan(Path.DRAFT_RULE, goal.ruleCode(), reason);
    }

    private ActResult act(Plan plan, AgentGoal goal, AgentBudget budget, long runId, int[] seq,
                          String operator, Observation observation) {
        if (plan.path() == Path.EXISTING_RULE) {
            return actExisting(plan, goal, runId, seq);
        }
        return actDraft(plan, goal, budget, runId, seq, operator, observation);
    }

    /** 确定性路径：直接跑对账，一个 token 都不花。 */
    private ActResult actExisting(Plan plan, AgentGoal goal, long runId, int[] seq) {
        RuleCatalog.Resolved resolved = catalog.resolve(plan.ruleCode());
        String batch = goal.batchId() == null || goal.batchId().isBlank()
                ? "agent-" + runId
                : goal.batchId();
        long t = System.nanoTime();
        ReconJob job = engine.run(resolved, batch, "由 Agent 会话 #" + runId + " 发起");
        String payload = "任务 #" + job.id() + "｜" + job.status()
                + "｜扫描 左=" + job.leftRows() + " 右=" + job.rightRows()
                + "｜匹配 " + job.matchedRows() + "｜差异 " + job.diffRows();
        recordStep(runId, ++seq[0], "ACT", "run-reconciliation", plan.reason(), payload,
                job.status().name(), t);
        return new ActResult(job.id(), null, null, resolved.spec().code(), null, null);
    }

    private ActResult actDraft(Plan plan, AgentGoal goal, AgentBudget budget, long runId, int[] seq,
                               String operator, Observation observation) {
        if (goal.rawInput() == null || goal.rawInput().isBlank()) {
            return ActResult.blocked("没有业务描述，无法起草规则（现有规则也不匹配）", null, plan.ruleCode(), null);
        }
        long t = System.nanoTime();
        RuleDraft draft = drafter.draft(goal.rawInput(), plan.ruleCode(), budget);
        runs.bumpLlm(runId, budget.rounds(), budget.tokens());
        for (RuleDraft.Round round : draft.rounds()) {
            String payload = round.detail() != null ? round.detail()
                    : round.violations().isEmpty() ? "通过" : String.join("\n", round.violations());
            recordStep(runId, ++seq[0], "ACT", "draft-round-" + round.index(), plan.reason(), payload,
                    round.status(), t);
            t = System.nanoTime();
        }
        if (!draft.drafted()) {
            String reason = draft.blockedReason()
                    + (draft.lastViolations().isEmpty() ? "" : "；最后一次违规：" + draft.lastViolations().get(0));
            return ActResult.blocked(reason, draft, plan.ruleCode(), null);
        }

        // 落库必须走 defineDraft（DRAFT 状态）：直接 defineMap 会让 Agent 生成的东西立刻对业务可见，
        // 那等于把三道闸门里最外面一道（人工审核）绕过去了
        t = System.nanoTime();
        String draftReason = "Agent 起草（会话 #" + runId + "）："
                + (draft.reasoning() == null ? "-" : draft.reasoning())
                + (draft.assumptions().isEmpty() ? "" : "；假设：" + String.join("；", draft.assumptions()));
        RuleCatalog.Resolved saved = catalog.defineDraft(draft.ruleBody(), "Agent 会话 #" + runId,
                operator, RuleOrigin.AGENT, observation.latestVersion(), draftReason);
        runs.updateDraft(runId, saved.spec().code(), saved.version());
        Map<String, Object> savedPayload = new LinkedHashMap<>();
        savedPayload.put("code", saved.spec().code());
        savedPayload.put("version", saved.version());
        savedPayload.put("baseVersion", observation.latestVersion());
        savedPayload.put("reasoning", draft.reasoning());
        savedPayload.put("assumptions", draft.assumptions());
        recordStep(runId, ++seq[0], "ACT", "save-draft", plan.reason(), toJson(savedPayload), "OK", t);

        // 试算：闸门 2 是"这条规则值不值得批"的证据来源，Agent 自己不能跳过
        t = System.nanoTime();
        ShadowTrialService.TrialResult trial;
        try {
            trial = trialService.trial(saved.spec().code(), saved.version());
        } catch (RuleException e) {
            // 起草时已过闸门 1，这里再被拒只可能是基线在这一瞬间变了 —— 记为 BLOCKED，等人工确认
            recordStep(runId, ++seq[0], "ACT", "shadow-trial", "试算", Errors.describe(e), "BLOCKED", t);
            return ActResult.blocked("草稿落库后试算被闸门 1 拒绝：" + Errors.describe(e),
                    draft, saved.spec().code(), saved.version());
        }
        recordStep(runId, ++seq[0], "ACT", "shadow-trial", "试算 " + saved.spec().code() + " v" + saved.version(),
                trialPayload(trial), trialStatus(trial.state()), t);
        return new ActResult(null, draft, trial, saved.spec().code(), saved.version(), null);
    }

    /**
     * 试算结论的一句话（{@code describe} 只认 {@link TrialState.Trialed}，其余变体给类名）。
     * <b>只用于人读的消息，不进 {@code status} 列</b> —— 那一列是 VARCHAR(16)。
     */
    private static String trialSummary(ShadowTrialService.TrialResult trial) {
        if (trial == null) {
            return "未试算";
        }
        return trial.state() instanceof TrialState.Trialed trialed
                ? TrialVerdictResolver.describe(trialed)
                : trial.state().getClass().getSimpleName();
    }

    /** 步骤标签：{@code PASS} / {@code INCONCLUSIVE} / {@code Skipped} —— 短到能进 VARCHAR(16)。 */
    private static String trialStatus(TrialState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return state instanceof TrialState.Trialed trialed
                ? trialed.verdict().name()
                : state.getClass().getSimpleName();
    }

    private String trialPayload(ShadowTrialService.TrialResult trial) {
        Map<String, Object> root = new LinkedHashMap<>();
        TrialState state = trial.state();
        root.put("state", state.getClass().getSimpleName());
        if (state instanceof TrialState.Trialed trialed) {
            root.put("verdict", trialed.verdict().name());
            root.put("coverage", trialed.coverage().name());
            root.put("reasons", trialed.reasons().stream().map(Enum::name).toList());
            root.put("approval", trialed.approval().name());
        }
        if (trial.report() != null) {
            root.put("checks", trial.report().checks());
            root.put("notes", trial.report().notes());
        }
        return toJson(root);
    }

    /** 沉淀：把这次任务的差异补齐归因（L1/L2 成本分层的入口在 {@code ExplainService} 里）。 */
    private void learn(long runId, Long jobId, int[] seq) {
        if (jobId == null) {
            return;
        }
        long t = System.nanoTime();
        int explained = explain.explainJob(jobId, LEARN_DIFF_LIMIT);
        recordStep(runId, ++seq[0], "LEARN", "explain-diffs", "任务 #" + jobId,
                "补齐 " + explained + " 条归因（解释器=" + explain.llmName() + "）", "OK", t);
    }

    private void recordStep(long runId, int seq, String phase, String action, String input,
                            String payload, String status, long startedNanos) {
        int costMs = (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        String scrubbed = PromptGuard.scrub(payload, MAX_STEP_PAYLOAD);
        boolean truncated = payload != null && scrubbed.length() < payload.length();
        String digest = payload == null ? null : SpecHasher.sha256(payload);
        runs.recordStep(runId, seq, phase, action, PromptGuard.scrub(input, 500), scrubbed, digest,
                truncated, statusLabel(status), costMs);
    }

    /**
     * {@code status} 是 VARCHAR(16) 的短标签（{@code OK} / {@code SAFETY_VIOLATION} / {@code PASS}）。
     *
     * <p>超长时截断并告警，<b>而不是让它把整步写入顶掉</b>：轨迹写不进去意味着 A8 的整条链路断在这里，
     * 而"标签被截短"只是个观感问题 —— 全文已经在 {@code payload} 里了。
     * 这个兜底是单测撞出来的：把一句试算结论（44 字符）塞进 status，H2 直接 22001。
     */
    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "OK";
        }
        if (status.length() <= 16) {
            return status;
        }
        log.warn("step status 超长（{} 字符，上限 16），已截断；详情应该放 payload：{}",
                status.length(), status);
        return status.substring(0, 16);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
