package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleGateRejectedException;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.engine.Errors;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.llm.PromptGuard;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.rule.SpecHasher;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.RuleRepository;
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
     * 单步 payload 的上限（token，不是字符 —— 与 TurnBudget 同一把尺子）。
     *
     * <p>超了截断并置 {@code truncated}：轨迹的用途是"能看懂发生了什么"，
     * 不是"完整存档" —— 后者应该走对象存储，塞进 TEXT 列的结果是某天写库失败（1406）。
     */
    private static final int MAX_STEP_PAYLOAD = 2000;

    /** LEARN 阶段一次最多补多少条归因。会话不是批处理，补几条看趋势就够了。 */
    private static final int LEARN_DIFF_LIMIT = 20;

    /**
     * 一次会话最多换几次路径。
     *
     * <p>不设上限的 re-plan 会变成"换个说法再撞一次墙"：预算、墙钟、用户耐心都在同一个方向消耗。
     * 两次的依据是当前只有一条真正的备选路径（起草受阻 → 只做诊断），第 3 次没有新东西可试。
     */
    private static final int MAX_REPLANS = 2;

    private final AgentRunRepository runs;
    private final RuleCatalog catalog;
    private final ReconEngine engine;
    private final ShadowTrialService trialService;
    private final RuleDrafter drafter;
    private final ExplainService explain;
    private final AgentProperties properties;
    private final ObjectMapper json;
    /** 恢复时读回"已经落库的试算态"用；不参与规划，只参与从哪接着做的判断。 */
    private final RuleRepository rules;

    public AgentOrchestrator(AgentRunRepository runs, RuleCatalog catalog, ReconEngine engine,
                             ShadowTrialService trialService, RuleDrafter drafter,
                             ExplainService explain, AgentProperties properties, ObjectMapper json,
                             RuleRepository rules) {
        this.runs = runs;
        this.catalog = catalog;
        this.engine = engine;
        this.trialService = trialService;
        this.drafter = drafter;
        this.explain = explain;
        this.properties = properties;
        this.json = json;
        this.rules = rules;
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
        EXISTING_RULE, DRAFT_RULE,
        /**
         * 兜底路径：不再尝试产出规则，只把"为什么没成"钉进轨迹，交人接手。
         *
         * <p>它<b>只能由 re-plan 产生</b>，{@link #plan} 永远不会选它 —— 否则"问一句需求"
         * 会被直接翻译成"什么都不做"。存在的意义是让失败有产物：违规列表与受阻原因
         * 比一个空手 {@code BLOCKED} 有用，下一个人不必从头再探一遍。
         */
        EXPLAIN_ONLY
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
                             String ruleCode, Integer draftVersion, String blockedReason,
                             String diagnosis) {

        static ActResult blocked(String reason, RuleDraft draft, String ruleCode, Integer draftVersion) {
            return new ActResult(null, draft, null, ruleCode, draftVersion, reason, null);
        }

        /** 走完兜底路径的产物：一句诊断 + 已落库的草稿（如果有）。 */
        static ActResult diagnosed(String diagnosis, RuleDraft draft, String ruleCode, Integer draftVersion) {
            return new ActResult(null, draft, null, ruleCode, draftVersion, null, diagnosis);
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
        // 结构化目标一起落库：恢复时靠它还原 intent（"只起草"和"真的跑一次对账"是两条路）
        long runId = runs.startRun(goal.describe(), toJson(goal), goal.trigger().name(), who);
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
        return execute(runId, goal, operator, 0);
    }

    /**
     * @param startSeq 从第几步接着写。恢复时用上一个会话的步号续上 ——
     *                 {@code uk_agent_step (run_id, seq)} 不允许重号，从 0 重开会让第二步就写失败。
     */
    private AgentRunResult execute(long runId, AgentGoal goal, String operator, int startSeq) {
        String who = operator == null || operator.isBlank() ? "agent" : operator;
        AgentBudget budget = AgentBudget.of(properties);
        int[] seq = {startSeq};
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
            runs.updatePlan(runId, PromptGuard.scrub(PromptGuard.truncateTokens(planJson, MAX_STEP_PAYLOAD), 0),
                    io.github.dolow.reconlens.llm.Tokens.estimate(planJson) > MAX_STEP_PAYLOAD);
            recordStep(runId, ++seq[0], "PLAN", plan.path().name().toLowerCase(),
                    observation.summary(), planJson, "OK", t);

            // ③ 行动（受阻就换路径：次数有上限，换路径这件事本身也要落轨迹）
            ActResult act = act(plan, goal, budget, runId, seq, who, observation);
            for (int replans = 0; act.blockedReason() != null && replans < MAX_REPLANS && diagnosable(act);
                 replans++) {
                Plan next = new Plan(Path.EXPLAIN_ONLY, act.ruleCode(),
                        "上一路径（" + plan.path().name().toLowerCase() + "）受阻：" + act.blockedReason()
                                + "；改为把原因钉进轨迹，交人接手");
                long tr = System.nanoTime();
                recordStep(runId, ++seq[0], "PLAN", "replan-" + (replans + 1), act.blockedReason(),
                        toJson(next.asMap()), "OK", tr);
                plan = next;
                act = actDiagnose(next, act, runId, seq);
            }
            draft = act.draft();
            if (act.ruleCode() != null) {
                ruleCode = act.ruleCode();
            }
            draftVersion = act.draftVersion();
            if (act.blockedReason() != null) {
                // 草稿已落库就是"做了一半"：落 PARTIAL 而不是 BLOCKED ——
                // 产物还在规则库里（v 号可复用），假装整件事没发生会让下一个人重做一遍。
                AgentRunRepository.RunStatus status = draftVersion != null
                        ? AgentRunRepository.RunStatus.PARTIAL
                        : AgentRunRepository.RunStatus.BLOCKED;
                runs.finishRun(runId, status, ruleCode, null, draftVersion, act.blockedReason());
                return new AgentRunResult(runId, status, ruleCode, draftVersion,
                        null, act.blockedReason(), draft, null,
                        draft == null ? List.of() : draft.lastViolations());
            }
            if (act.diagnosis() != null) {
                // 兜底路径：目标没达成，但轨迹里留下了可复用的结论（为什么没成、卡在哪条违规）
                runs.finishRun(runId, AgentRunRepository.RunStatus.PARTIAL, ruleCode, null, draftVersion,
                        act.diagnosis());
                return new AgentRunResult(runId, AgentRunRepository.RunStatus.PARTIAL, ruleCode, draftVersion,
                        null, act.diagnosis(), draft, null,
                        draft == null ? List.of() : draft.lastViolations());
            }

            // ④ 沉淀（与恢复共用同一段：恢复时"行动"的结论可能来自库里的旧产物）
            return conclude(runId, act.jobId(),
                    act.trial() == null ? null : trialPassed(act.trial().state()),
                    act.trial() == null ? null : trialSummary(act.trial()),
                    draft, ruleCode, draftVersion, seq);
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

    /**
     * ④ 沉淀 + 定状态。抽出来是为了让 {@link #resume} 复用同一段：
     * 恢复时"行动"的结论可能不是刚算出来的，而是库里已有的草稿/试算/任务。
     *
     * @param trialPassed 试算是否通过；{@code null} = 这次没有试算（判不了"做了一半"）
     * @param trialText   试算结论的一句话（人读；{@code null} = 没试算）
     */
    private AgentRunResult conclude(long runId, Long jobId, Boolean trialPassed, String trialText,
                                    RuleDraft draft, String ruleCode, Integer draftVersion, int[] seq) {
        learn(runId, jobId, seq);

        // 草稿落了库、试算却没通过：这是"做了一半"，不是成功。
        // 实测撞到的具体场景：H2 不认 CRC32 → 每条分片 FAILED → INCONCLUSIVE，而 run 却报 SUCCESS，
        // 下游（人、脚本、审批）会以为"已经验过了"。
        boolean draftedButNotPassed = draftVersion != null && Boolean.FALSE.equals(trialPassed);
        if (draftedButNotPassed) {
            String reason = "草稿已落库（v" + draftVersion + "），但试算没通过："
                    + (trialText == null ? "未知" : trialText)
                    + "；产物留在规则库里，等人工判断 —— 这不等于这条规则可用";
            runs.finishRun(runId, AgentRunRepository.RunStatus.PARTIAL, ruleCode, null, draftVersion, reason);
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.PARTIAL, ruleCode, draftVersion,
                    null, reason, draft, null, List.of());
        }

        String message = jobId != null
                ? "已按现有规则跑完对账，任务 #" + jobId + "（差异明细用 recon_list_diffs 看）"
                : draftVersion != null
                ? "草稿 " + ruleCode + " v" + draftVersion + " 已落库并完成影子试算（"
                + (trialText == null ? "未试算" : trialText) + "）；审批不由 Agent 执行，"
                + "请人工走 POST /api/rules/" + ruleCode + "/" + draftVersion + "/approve"
                : "已完成";
        runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, ruleCode, jobId, draftVersion, null);
        return new AgentRunResult(runId, AgentRunRepository.RunStatus.SUCCESS, ruleCode, draftVersion,
                jobId, message, draft, null, List.of());
    }

    /** 恢复点：从最后一个**持久化产物**接着做，而不是从头再猜一遍。 */
    public enum ResumePoint {
        /** 没有可复用产物（感知/规划/起草中途崩的）：从头跑，沿用同一个 runId 与步号。 */
        SCRATCH,
        /** 草稿已落库：只补试算 + 沉淀。 */
        DRAFT_SAVED,
        /** 试算已落库：只补沉淀（不再重跑试算，那会多一条任务）。 */
        TRIAL_DONE,
        /** 对账任务已建：只补沉淀（归因）。 */
        JOB_DONE
    }

    /**
     * 恢复一次中断的会话。
     *
     * <p>只认两种起点：{@code RUNNING}（进程被杀了，启动巡检会自动接管）和 {@code FAILED}
     * （执行中真出了异常，救不救由人决定）。{@code SUCCESS / PARTIAL / BLOCKED} 不恢复 ——
     * 那三种都是有结论的终点，重开会把结论搅掉。
     *
     * <p>判断"从哪接着做"只看轨迹里最后一个 ACT：{@code save-draft} → 补试算；
     * {@code shadow-trial} → 补沉淀；{@code run-reconciliation} → 补归因；其余 → 从头重跑。
     */
    public AgentRunResult resume(long runId, String operator) {
        String who = operator == null || operator.isBlank() ? "agent" : operator;
        AgentRunRepository.AgentRun run = runs.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("没有这个会话：#" + runId));
        AgentRunRepository.RunStatus status = AgentRunRepository.RunStatus.valueOf(run.status());
        if (status != AgentRunRepository.RunStatus.RUNNING && status != AgentRunRepository.RunStatus.FAILED) {
            throw new IllegalStateException("会话 #" + runId + " 已经结束（" + run.status()
                    + "）：只有 RUNNING（进程中断）或 FAILED（被启动巡检标记）的会话能恢复；"
                    + "出了结论的会话重跑会把结论搅乱");
        }
        AgentGoal goal = goalOf(run);
        ResumePoint point = resumePoint(runs.listSteps(runId));
        int[] seq = {runs.nextSeq(runId) - 1};
        recordStep(runId, ++seq[0], "RESUME", "from-" + point.name().toLowerCase(java.util.Locale.ROOT),
                goal.describe(), pointExplain(run, point), "OK", System.nanoTime());
        log.info("恢复会话 #{}：从 {} 接着做", runId, point);
        try {
            return switch (point) {
                case SCRATCH -> execute(runId, goal, who, seq[0]);
                case DRAFT_SAVED -> resumeDraft(runId, run, seq);
                case TRIAL_DONE -> {
                    RuleRepository.TrialRecord record = trialRecordOf(run.ruleCode(), run.draftVersion());
                    yield conclude(runId, null, record != null && "PASS".equals(record.verdict()),
                            describeTrialRecord(record), null, run.ruleCode(), run.draftVersion(), seq);
                }
                case JOB_DONE -> conclude(runId, run.jobId(), null, null, null, run.ruleCode(), null, seq);
            };
        } catch (RuntimeException e) {
            log.error("恢复会话 #{} 失败：{}", runId, Errors.describe(e));
            runs.finishRun(runId, AgentRunRepository.RunStatus.FAILED, run.ruleCode(), null,
                    run.draftVersion(), "恢复失败：" + Errors.describe(e));
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.FAILED, run.ruleCode(),
                    run.draftVersion(), null, "恢复失败：" + Errors.describe(e), null, null, List.of());
        }
    }

    /** 草稿已在库里：只补试算，然后走公共的沉淀段。 */
    private AgentRunResult resumeDraft(long runId, AgentRunRepository.AgentRun run, int[] seq) {
        long t = System.nanoTime();
        ShadowTrialService.TrialResult trial;
        try {
            trial = trialService.trial(run.ruleCode(), run.draftVersion());
        } catch (RuleException e) {
            recordStep(runId, ++seq[0], "ACT", "shadow-trial", "恢复：试算 " + run.ruleCode(),
                    gateRejectedPayload(e), "BLOCKED", t);
            String reason = "草稿已落库（v" + run.draftVersion() + "），但恢复时试算被闸门 1 拒绝："
                    + Errors.describe(e);
            runs.finishRun(runId, AgentRunRepository.RunStatus.PARTIAL, run.ruleCode(), null,
                    run.draftVersion(), reason);
            return new AgentRunResult(runId, AgentRunRepository.RunStatus.PARTIAL, run.ruleCode(),
                    run.draftVersion(), null, reason, null, null, List.of());
        }
        recordStep(runId, ++seq[0], "ACT", "shadow-trial",
                "恢复：试算 " + run.ruleCode() + " v" + run.draftVersion(),
                trialPayload(trial), trialStatus(trial.state()), t);
        return conclude(runId, null, trialPassed(trial.state()), trialSummary(trial),
                null, run.ruleCode(), run.draftVersion(), seq);
    }

    /**
     * 轨迹里最后一个 ACT 决定还能复用什么。
     *
     * <p>只看 ACT 不看 PLAN：PLAN 只是"打算怎么做"，崩在规划阶段等于什么都没做；
     * 而 {@code draft-round-N} 虽然跑过模型，但那一轮的产物没有落库，重跑是唯一选择。
     */
    static ResumePoint resumePoint(List<AgentRunRepository.AgentStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            AgentRunRepository.AgentStep step = steps.get(i);
            if (!"ACT".equals(step.phase()) || step.action() == null) {
                continue;
            }
            String action = step.action();
            if (action.startsWith("shadow-trial")) {
                return ResumePoint.TRIAL_DONE;
            }
            if (action.startsWith("save-draft")) {
                return ResumePoint.DRAFT_SAVED;
            }
            if (action.startsWith("run-reconciliation")) {
                return ResumePoint.JOB_DONE;
            }
            return ResumePoint.SCRATCH;
        }
        return ResumePoint.SCRATCH;
    }

    private String pointExplain(AgentRunRepository.AgentRun run, ResumePoint point) {
        return switch (point) {
            case SCRATCH -> "没有可复用的产物（上次停在感知/规划/起草），从头重跑；沿用同一个会话号与步号";
            case DRAFT_SAVED -> "草稿 " + run.ruleCode() + " v" + run.draftVersion() + " 已在库里，接着补试算与沉淀";
            case TRIAL_DONE -> "试算已在库里（" + run.ruleCode() + " v" + run.draftVersion()
                    + "），不再重跑试算，接着补沉淀";
            case JOB_DONE -> "对账任务 #" + run.jobId() + " 已建，接着补归因";
        };
    }

    /** 读回已经落库的试算记录；没有就 {@code null}（= 从没试算过）。 */
    private RuleRepository.TrialRecord trialRecordOf(String ruleCode, Integer version) {
        if (ruleCode == null || version == null) {
            return null;
        }
        return rules.findTrial(ruleCode, version).orElse(null);
    }

    /** 一行试算结论：{@code PASS / INCONCLUSIVE / SAMPLE_PARTIAL / …}。 */
    private static String describeTrialRecord(RuleRepository.TrialRecord record) {
        if (record == null || record.verdict() == null || record.verdict().isBlank()) {
            return "未试算";
        }
        StringBuilder sb = new StringBuilder(record.verdict());
        if (record.coverage() != null && !record.coverage().isBlank()) {
            sb.append(" / ").append(record.coverage());
        }
        if (record.coverageReason() != null && !record.coverageReason().isBlank()) {
            sb.append(" / ").append(record.coverageReason());
        }
        return sb.toString();
    }

    /** 试算态 → 是否通过（"跳过"和"没跑"都不算通过）。 */
    private static Boolean trialPassed(TrialState state) {
        return state instanceof TrialState.Trialed trialed ? trialed.verdict() == TrialVerdict.PASS : Boolean.FALSE;
    }

    /**
     * 还原目标。优先读 {@code goal_json}；旧记录没有这一列时退回文本重建，
     * 且只给 {@code intent=DRAFT_RULE} —— 万一原来那条是想跑对账的，恢复后也只是"少跑一次"，
     * 而不是"多跑一次生产对账"。
     */
    private AgentGoal goalOf(AgentRunRepository.AgentRun run) {
        String json = runs.goalJson(run.id()).orElse(null);
        if (json != null && !json.isBlank()) {
            try {
                return this.json.readValue(json, AgentGoal.class);
            } catch (Exception e) {
                log.warn("会话 #{} 的 goal_json 解析失败，按文本重建：{}", run.id(), Errors.describe(e));
            }
        }
        return AgentGoal.of(run.goal(), run.ruleCode(), null);
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
        if (plan.path() == Path.EXPLAIN_ONLY) {
            // 走到这里说明有人把兜底路径塞进了首选：宁可报错，也不要让它悄悄变成"什么都不做"
            throw new IllegalStateException("EXPLAIN_ONLY 只能由 re-plan 产生，不能作为首选路径");
        }
        return actDraft(plan, goal, budget, runId, seq, operator, observation);
    }

    /**
     * 兜底路径：把"为什么没成"钉进轨迹，返回 PARTIAL 的产物。
     *
     * <p>不调用模型、不落草稿、不改规则库 —— 这一段只做一件事：把失败变成可交接的证据。
     */
    private ActResult actDiagnose(Plan plan, ActResult failed, long runId, int[] seq) {
        long t = System.nanoTime();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("failedReason", failed.blockedReason());
        root.put("ruleCode", plan.ruleCode());
        root.put("violations", failed.draft() == null ? List.of() : failed.draft().lastViolations());
        root.put("note", "Agent 没有别的路径可走；草稿若已落库，版本号在 draft_version 上，可直接人工接手");
        recordStep(runId, ++seq[0], "ACT", "diagnose-blocked", plan.reason(), toJson(root), "DIAGNOSED", t);
        String diagnosis = "没做成：" + failed.blockedReason()
                + (failed.draftVersion() == null ? "" : "；已落库草稿 v" + failed.draftVersion() + " 可直接接手")
                + "（诊断已记入轨迹）";
        return ActResult.diagnosed(diagnosis, failed.draft(), plan.ruleCode(), failed.draftVersion());
    }

    /**
     * 有没有东西可诊断：必须是「起草真跑过、闸门真说了不」。
     *
     * <p>没有违规就没有信息量 —— 把"大模型未启用"这类配置问题再写一遍只是换个措辞，
     * 还会把一次干净的 BLOCKED 变成 PARTIAL，让运维以为"做了点什么"。
     */
    private static boolean diagnosable(ActResult failed) {
        return failed.draft() != null && !failed.draft().lastViolations().isEmpty();
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
        return new ActResult(job.id(), null, null, resolved.spec().code(), null, null, null);
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
            // 这一轮的真实 token（模型响应 usage）与耗时（起草器自己按轮计时）优先；
            // 拿不到就按 payload 估算 / 用外部计时器
            recordStep(runId, ++seq[0], "ACT", "draft-round-" + round.index(), plan.reason(), payload,
                    round.status(), t,
                    round.tokens() > 0 ? round.tokens() : null,
                    round.costMs() > 0 ? Integer.valueOf(round.costMs()) : null);
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
            recordStep(runId, ++seq[0], "ACT", "shadow-trial", "试算", gateRejectedPayload(e), "BLOCKED", t);
            // 草稿已经在规则库里（v 号可复用）：说清「产物在哪、卡在哪」，下一个人不用重做
            return ActResult.blocked("草稿已落库（v" + saved.version() + "），但试算被闸门 1 拒绝："
                    + Errors.describe(e), draft, saved.spec().code(), saved.version());
        }
        recordStep(runId, ++seq[0], "ACT", "shadow-trial", "试算 " + saved.spec().code() + " v" + saved.version(),
                trialPayload(trial), trialStatus(trial.state()), t);
        return new ActResult(null, draft, trial, saved.spec().code(), saved.version(), null, null);
    }

    /**
     * 试算被闸门 1 拒绝时落进轨迹的 payload：结构化违规优先。
     * 只留一句话时，下一个人（或下一次 re-plan）只能靠正则从中文里抠字段。
     */
    private String gateRejectedPayload(RuleException e) {
        if (!(e instanceof RuleGateRejectedException rejected)) {
            return Errors.describe(e);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", Errors.describe(e));
        payload.put("violations", rejected.violationViews());
        return toJson(payload);
    }

    /**
     * 试算结论的一句话（{@code describe} 只认 {@link TrialState.Trialed}，其余变体给类名）。
     * <b>只用于人读的消息，不进 {@code status} 列</b> —— 那一列是 VARCHAR(16)。
     */
    private static String trialSummary(ShadowTrialService.TrialResult trial) {
        return trial == null ? "未试算" : trialSummary(trial.state());
    }

    /** 同上，但手上只有试算态（恢复时是从 {@code recon_rule} 读回来的）。 */
    private static String trialSummary(TrialState state) {
        if (state == null) {
            return "未试算";
        }
        return state instanceof TrialState.Trialed trialed
                ? TrialVerdictResolver.describe(trialed)
                : state.getClass().getSimpleName();
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
        recordStep(runId, seq, phase, action, input, payload, status, startedNanos, null, null);
    }

    /**
     * @param tokensOverride 这一步的真实 token 用量（模型响应里的 {@code usage.total_tokens}）；
     *                       {@code null} = 按落库 payload 估算
     */
    private void recordStep(long runId, int seq, String phase, String action, String input,
                            String payload, String status, long startedNanos, Integer tokensOverride) {
        recordStep(runId, seq, phase, action, input, payload, status, startedNanos, tokensOverride, null);
    }

    /**
     * 全部参数的版本。
     *
     * @param tokensOverride 这一步的真实 token 用量；{@code null} = 按落库 payload 估算
     * @param costMsOverride 这一步的真实墙钟耗时；{@code null} = 用 {@code startedNanos} 自己算。
     *                       起草的每一轮都在 {@code draft()} 内部跑完，外部计时器只能量到整段，
     *                       所以轮次的耗时必须由起草器自己报（见 {@code RuleDraft.Round#costMs()}）。
     */
    private void recordStep(long runId, int seq, String phase, String action, String input,
                            String payload, String status, long startedNanos,
                            Integer tokensOverride, Integer costMsOverride) {
        int costMs = costMsOverride != null
                ? costMsOverride
                : (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        String scrubbed = PromptGuard.scrub(PromptGuard.truncateTokens(payload, MAX_STEP_PAYLOAD), 0);
        boolean truncated = payload != null && scrubbed.length() < payload.length();
        String digest = payload == null ? null : SpecHasher.sha256(payload);
        // token 记在落表那一份（scrubbed）上：复盘时能看到的就是它，拿原文估会和实际写进库的内容对不上账。
        // 有真实用量就用真实的 —— 估算口径是字符数，会比真实 token 大一个量级（实测 5018ms 的一轮记成 2）。
        int tokensEst = tokensOverride != null
                ? tokensOverride
                : scrubbed == null ? 0 : io.github.dolow.reconlens.llm.Tokens.estimate(scrubbed);
        runs.recordStep(runId, seq, phase, action, PromptGuard.scrub(input, 500), scrubbed, digest,
                truncated, statusLabel(status), costMs, tokensEst);
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
