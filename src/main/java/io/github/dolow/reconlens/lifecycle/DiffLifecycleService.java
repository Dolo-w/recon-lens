package io.github.dolow.reconlens.lifecycle;

import io.github.dolow.reconlens.domain.DiffLifecycle.Action;
import io.github.dolow.reconlens.domain.DiffLifecycle.DiffAction;
import io.github.dolow.reconlens.domain.DiffLifecycle.Status;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.explain.DiffKnowledgeBase;
import io.github.dolow.reconlens.store.DiffLifecycleRepository;
import io.github.dolow.reconlens.store.ReconRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 差异生命周期状态机（豁免台账的写入口）。
 *
 * <p>规则（非法转移 fail-loud 报当前状态，绝不静默改写）：
 * <ul>
 *   <li>CLAIM：OPEN → CLAIMED；RELEASE：CLAIMED → OPEN（认领是协作信号，能退能放）；</li>
 *   <li>RESOLVE / EXEMPT：OPEN 或 CLAIMED 都可执行，且 <b>note 必填</b> ——
 *       台账里一句"处理了"没有内容等于没处理；核销不强制先认领（认领是协作信号不是硬闸）；</li>
 *   <li>UNEXEMPT：EXEMPTED → OPEN，豁免错了随时能撤，不设时限 ——
 *       财务豁免被审计打回是常态，台账必须支持；</li>
 *   <li>RESOLVED 是终态，要动它只能按"豁免错了"的思路走：先 UNEXEMPT 回 OPEN 再重新处置？
 *       不 —— RESOLVED 一样是结论，结论错了重跑对账（差异本身会被重写），台账不动旧账。</li>
 * </ul>
 *
 * <p>闭环率 = (RESOLVED + EXEMPTED) / 总差异 —— 财务画像的核心指标：
 * 报出来一百条差异、闭环了三十条，剩下七十条才是"少收的钱"还在不在的真相。
 *
 * <p><b>K1 知识回流</b>：RESOLVE / EXEMPT 的定性结论在写台账的同时 {@code teach()} 进
 * 归因知识库（{@code source=HUMAN}, 置信度 0.9）。处理过的账要能被下一批同类差异查到，
 * 否则第 101 条同病差异还要财务重新想一遍 —— 最贵的数据躺在台账里生锈。
 * 回流是 best-effort：知识库挂了不影响台账写入（台账是审计事实，知识库是成本优化）。
 */
@Service
public class DiffLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DiffLifecycleService.class);

    private static final int MAX_NOTE = 1000;
    private static final int MAX_REASON_ID = 64;

    private final DiffLifecycleRepository lifecycle;
    private final ReconRepository recon;
    private final DiffKnowledgeBase knowledge;

    public DiffLifecycleService(DiffLifecycleRepository lifecycle, ReconRepository recon,
                                DiffKnowledgeBase knowledge) {
        this.lifecycle = lifecycle;
        this.recon = recon;
        this.knowledge = knowledge;
    }

    /**
     * 执行一次处置动作。
     *
     * @param reasonId 定性结论分类 ID（K2 模式库分类法），可空 —— K1 阶段自由文本兜底
     * @throws IllegalArgumentException 差异不存在 / operator 缺失 / note 缺失或超长 / 非法状态转移
     */
    public DiffAction apply(long diffId, Action action, String operator, String note, String reasonId) {
        DiffRecord diff = recon.findDiff(diffId).orElseThrow(() ->
                new IllegalArgumentException("差异不存在: " + diffId));
        String who = operator == null ? "" : operator.trim();
        if (who.isEmpty()) {
            throw new IllegalArgumentException("operator 必填 —— 不知道谁改的，就没法追溯");
        }
        String trimmedNote = note == null ? "" : note.trim();
        if (action == Action.RESOLVE || action == Action.EXEMPT) {
            if (trimmedNote.isEmpty()) {
                throw new IllegalArgumentException(action == Action.EXEMPT
                        ? "豁免必须写依据（note）：为什么这条差异可以不处理"
                        : "核销必须写原因（note）：这条差异最后是怎么处理掉的");
            }
            if (trimmedNote.length() > MAX_NOTE) {
                throw new IllegalArgumentException("note 超过 " + MAX_NOTE + " 字符，写进台账前先提炼");
            }
        }
        String trimmedReason = reasonId == null ? "" : reasonId.trim();
        if (trimmedReason.length() > MAX_REASON_ID) {
            throw new IllegalArgumentException("reasonId 超过 " + MAX_REASON_ID + " 字符：它是分类 ID 不是说明文");
        }
        Status current = lifecycle.currentStatus(diffId).orElse(Status.OPEN);
        validateTransition(current, action);
        Status next = io.github.dolow.reconlens.domain.DiffLifecycle.resultOf(action);
        DiffAction recorded = lifecycle.insert(diffId, diff.jobId(), action, next, who, trimmedNote,
                trimmedReason.isEmpty() ? null : trimmedReason);
        log.info("差异处置: diff={} {} {} -> {} by {} ({})", diffId, current, action, next, who, trimmedNote);
        if (action == Action.RESOLVE || action == Action.EXEMPT) {
            teachBackflow(diffId, action, diff, trimmedNote, who);
        }
        return recorded;
    }

    /**
     * K1 知识回流：定性结论 teach 进知识库，并把差异说明同步成人工结论。
     *
     * <p>失败绝不向上抛：台账已经落库（事实成立），知识库写不进去只损失"下次自动带出答案"，
     * 损失一条 warn 日志比让核销动作整体失败便宜得多 —— 和 {@code DiffKnowledgeBase.remember}
     * 的容错立场一致。
     */
    private void teachBackflow(long diffId, Action action, DiffRecord diff, String note, String operator) {
        try {
            String ruleCode = recon.findJob(diff.jobId()).map(job -> job.ruleCode()).orElse(null);
            if (ruleCode == null) {
                return;
            }
            // 核销的知识是"这类差异的病因"；豁免的知识是"这类差异可以不处理"——动作不同，沉淀的形态不同
            String kbAction = action == Action.EXEMPT ? "此类差异已确认可豁免" : null;
            if (knowledge.teach(ruleCode, diff, note, kbAction, operator)) {
                // 人给了结论，差异说明跟着换 —— 界面上不能还挂着模型那句 0.5 的猜测
                recon.updateExplanation(diffId, action == Action.EXEMPT
                        ? note + " 建议动作：此类差异已确认可豁免"
                        : note);
            }
        } catch (RuntimeException e) {
            log.warn("处置结论回流知识库失败（不影响台账）：diff={} {} — {}", diffId, action, e.toString());
        }
    }

    /** 单条差异的处置历史，最新在前。 */
    public java.util.List<DiffAction> history(long diffId) {
        recon.findDiff(diffId).orElseThrow(() -> new IllegalArgumentException("差异不存在: " + diffId));
        return lifecycle.history(diffId);
    }

    /**
     * 闭环汇总。口径与差异列表/summary 一致：jobId 为空 = 全库且只算 PROD 任务。
     */
    public Snapshot summary(Long jobId) {
        Map<Status, Long> byStatus = lifecycle.countByStatus(jobId);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long closed = byStatus.get(Status.RESOLVED) + byStatus.get(Status.EXEMPTED);
        return new Snapshot(jobId, total, byStatus,
                total == 0 ? null : (double) closed / total);
    }

    private static void validateTransition(Status current, Action action) {
        boolean ok = switch (action) {
            case CLAIM -> current == Status.OPEN;
            case RELEASE -> current == Status.CLAIMED;
            case RESOLVE -> current == Status.OPEN || current == Status.CLAIMED;
            case EXEMPT -> current == Status.OPEN || current == Status.CLAIMED;
            case UNEXEMPT -> current == Status.EXEMPTED;
        };
        if (!ok) {
            throw new IllegalArgumentException("非法转移：差异当前状态 " + current + "，不能执行 " + action
                    + "。状态机：OPEN→CLAIM→CLAIMED→RELEASE→OPEN；OPEN/CLAIMED→RESOLVE/EXEMPT；"
                    + "EXEMPTED→UNEXEMPT→OPEN");
        }
    }

    /** 闭环率快照。{@code closureRate} 为 null 表示没有差异（0/0 不冒充 0% 或 100%）。 */
    public record Snapshot(Long jobId, long total, Map<Status, Long> byStatus, Double closureRate) {
    }
}
