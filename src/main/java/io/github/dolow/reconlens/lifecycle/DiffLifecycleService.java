package io.github.dolow.reconlens.lifecycle;

import io.github.dolow.reconlens.domain.DiffLifecycle.Action;
import io.github.dolow.reconlens.domain.DiffLifecycle.DiffAction;
import io.github.dolow.reconlens.domain.DiffLifecycle.Status;
import io.github.dolow.reconlens.domain.DiffRecord;
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
 */
@Service
public class DiffLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DiffLifecycleService.class);

    private static final int MAX_NOTE = 1000;

    private final DiffLifecycleRepository lifecycle;
    private final ReconRepository recon;

    public DiffLifecycleService(DiffLifecycleRepository lifecycle, ReconRepository recon) {
        this.lifecycle = lifecycle;
        this.recon = recon;
    }

    /**
     * 执行一次处置动作。
     *
     * @throws IllegalArgumentException 差异不存在 / operator 缺失 / note 缺失或超长 / 非法状态转移
     */
    public DiffAction apply(long diffId, Action action, String operator, String note) {
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
        Status current = lifecycle.currentStatus(diffId).orElse(Status.OPEN);
        validateTransition(current, action);
        Status next = io.github.dolow.reconlens.domain.DiffLifecycle.resultOf(action);
        DiffAction recorded = lifecycle.insert(diffId, diff.jobId(), action, next, who, trimmedNote);
        log.info("差异处置: diff={} {} {} -> {} by {} ({})", diffId, current, action, next, who, trimmedNote);
        return recorded;
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
