package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.explain.CauseKey;
import io.github.dolow.reconlens.store.IndustryKbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 业界知识层的业务闸门：机器准入、注入过滤、置信度生命周期。
 *
 * <p><b>准入</b>（全自动，无人工核证）：结构合法 + 有条件 + 可溯源 + 去重 → 直接入库。
 * 质量不靠前置拦截，靠后置闭环：错误知识会在核销反馈里被驳回降权，跌破阈值进 graveyard。
 *
 * <p><b>注入</b>：按差异特征条件过滤 + 置信分层（≥0.6 直接注入，0.3~0.6 带 AI 生成标注，
 * <0.3 不注入）+ 数量与字符预算封顶。条目量是字典级（几百封顶），内存过滤足够。
 *
 * <p><b>涨落</b>：归因被人工确认 → 命中条目置信 +步长；被改判（人工给出不同结论）→ 驳回
 * -步长，这是近似信号（按差异特征反查当时会命中的条目，可能含少量误伤），
 * javadoc 不装精确——方向正确、偏差有界，够用。
 */
@Service
public class IndustryKbService {

    private static final Logger log = LoggerFactory.getLogger(IndustryKbService.class);

    /** 直接注入的置信度线（SEED/CHAT 初始就在线上）。 */
    static final double DIRECT_INJECT = 0.6;
    /** 低于此置信度不注入。 */
    static final double MIN_INJECT = 0.3;
    /** 跌破进 graveyard。 */
    static final double GRAVEYARD_AT = 0.2;
    static final double ADOPT_STEP = 0.05;
    static final double REJECT_STEP = 0.1;
    static final double CONFIDENCE_CAP = 0.9;
    /** 单次归因注入条目数上限。 */
    static final int MAX_INJECT = 4;
    /** 注入文本总字符预算。 */
    static final int CHAR_BUDGET = 1200;

    private final IndustryKbRepository repository;

    public IndustryKbService(IndustryKbRepository repository) {
        this.repository = repository;
    }

    /** 机器准入：通过即入库；拒绝返回原因。entry_key 冲突视为更新（版本纪律在调用方）。 */
    public AdmitResult admit(IndustryKnowledge candidate) {
        String rejection = candidate.admissionRejection();
        if (rejection != null) {
            return AdmitResult.rejected(rejection);
        }
        Optional<IndustryKnowledge> existing = repository.findByKey(candidate.entryKey());
        if (existing.isEmpty()) {
            IndustryKnowledge saved = repository.insert(candidate);
            log.info("业界知识入库: {} (origin={}, confidence={})",
                    saved.entryKey(), saved.origin(), saved.confidence());
            return AdmitResult.created(saved);
        }
        // 同 key：文本没变就是重复采集，变了就是版本演进——都算更新，不动置信度
        repository.upsert(candidate);
        return AdmitResult.updated(candidate);
    }

    /**
     * 纯选择：按差异特征选适用条目（含条目数上限），无副作用。
     * 批量归因场景对每条差异分别 select 再按 entryKey 去重合并，避免同一知识重复计数。
     */
    public List<IndustryKnowledge> select(String ruleCode, String diffType, String field, String direction) {
        try {
            return selectInternal(ruleCode, diffType, field, direction);
        } catch (RuntimeException e) {
            // 知识层是归因的增强不是依赖：读不到（表缺失/库故障）就当无知识，绝不挡归因主链路
            log.warn("业界知识查询失败，按无知识处理: {}", e.toString());
            return List.of();
        }
    }

    private List<IndustryKnowledge> selectInternal(String ruleCode, String diffType, String field,
                                                   String direction) {
        List<IndustryKnowledge> hits = new ArrayList<>();
        for (IndustryKnowledge e : repository.findActive()) {
            if (e.confidence() < MIN_INJECT || hits.size() >= MAX_INJECT) {
                break;
            }
            if (e.appliesWhen().matches(ruleCode, diffType, field, direction)) {
                hits.add(e);
            }
        }
        return hits;
    }

    /**
     * 拼注入上下文并记录使用计数。低置信条目带「AI 生成」标注——归因文本里出处可见，
     * 人能识别哪句是机器联想。空列表返回空串。
     */
    public String contextOf(List<IndustryKnowledge> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(160);
        int chars = 0;
        int appended = 0;
        sb.append("\n## 业界经验（供参考，须结合本差异实际判断）\n");
        for (IndustryKnowledge e : entries) {
            int cost = e.text().length() + 24;
            if (chars + cost > CHAR_BUDGET) {
                break;
            }
            chars += cost;
            appended++;
            try {
                repository.updateUsed(e.usedCount() + 1, e.entryKey());
            } catch (RuntimeException ex) {
                log.debug("注入计数失败（不影响归因）: {} — {}", e.entryKey(), ex.toString());
            }
            sb.append("- ");
            if (e.confidence() < DIRECT_INJECT) {
                sb.append("[AI 生成·低置信] ");
            }
            sb.append(e.text()).append('\n');
        }
        // 一条都没拼进去（预算异常收紧）就不给半个标题
        return appended > 0 ? sb.toString() : "";
    }

    /** 便捷方法（单条场景）：选择 + 拼装一步到位。 */
    public String injectContext(String ruleCode, String diffType, String field, String direction) {
        return contextOf(select(ruleCode, diffType, field, direction));
    }

    /**
     * 核销反馈：人工 RESOLVE/EXEMPT 时调用。adopted=false 表示人工给出了不同结论
     * （或明确豁免），当时命中的知识条目按驳回降权；true 表示人工认可 → 加权。
     */
    public void feedback(String ruleCode, String diffType, String field, String direction, boolean adopted) {
        try {
            for (IndustryKnowledge e : repository.findActive()) {
                if (!e.appliesWhen().matches(ruleCode, diffType, field, direction)) {
                    continue;
                }
                applyFeedback(e, adopted);
            }
        } catch (RuntimeException e) {
            // 反馈失败只损失一次置信度更新，核销动作本身不能被它拖垮
            log.warn("业界知识反馈失败（不影响核销）: {}", e.toString());
        }
    }

    /**
     * 核销反馈（近似信号，方向正确、偏差有界）：人工给出处置结论时，按差异特征反查
     * 「当时会被注入的条目」逐一涨落。采纳判定用最长公共子串（≥6 字）近似——
     * 人工 note 里复述了条目的说法说明条目帮上了忙；豁免一律驳回（豁免意味着这不是病因）。
     * 不引入分词依赖，文本都在几百字量级，DP 开销可忽略。
     */
    public void feedbackOnResolution(String ruleCode, DiffRecord diff, String note, boolean exempt) {
        try {
            String direction = CauseKey.directionOf(diff);
            for (IndustryKnowledge e : repository.findActive()) {
                if (!e.appliesWhen().matches(ruleCode, diff.diffType(), diff.field(), direction)) {
                    continue;
                }
                applyFeedback(e, !exempt && longestCommonSubstring(note == null ? "" : note, e.text()) >= 6);
            }
        } catch (RuntimeException e) {
            log.warn("业界知识反馈失败（不影响核销）: {}", e.toString());
        }
    }

    private void applyFeedback(IndustryKnowledge e, boolean adopted) {
        double next = adopted
                ? Math.min(CONFIDENCE_CAP, e.confidence() + ADOPT_STEP)
                : e.confidence() - REJECT_STEP;
        IndustryKnowledge.Status nextStatus = next < GRAVEYARD_AT
                ? IndustryKnowledge.Status.GRAVEYARD : IndustryKnowledge.Status.ACTIVE;
        if (nextStatus == IndustryKnowledge.Status.GRAVEYARD && e.status() != IndustryKnowledge.Status.GRAVEYARD) {
            log.info("业界知识进 graveyard: {} (confidence {} -> {})", e.entryKey(), e.confidence(), next);
        }
        repository.updateConfidence(e.entryKey(), next, nextStatus, e.usedCount(),
                e.rejectedCount() + (adopted ? 0 : 1));
    }

    /** 最长公共子串长度（大小写不敏感）。采纳判定与测试共用。 */
    public static int longestCommonSubstring(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        int best = 0;
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                cur[j] = x.charAt(i - 1) == y.charAt(j - 1) ? prev[j - 1] + 1 : 0;
                best = Math.max(best, cur[j]);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            java.util.Arrays.fill(cur, 0);
        }
        return best;
    }

    /** ACTIVE 但零命中的条目——要么适用面窄（正常），要么过时了（该查）。 */
    public List<IndustryKnowledge> coldEntries() {
        return repository.findActive().stream().filter(e -> e.usedCount() == 0).toList();
    }

    public List<IndustryKnowledge> graveyard() {
        return repository.listAll(500).stream()
                .filter(e -> e.status() == IndustryKnowledge.Status.GRAVEYARD).toList();
    }

    public List<IndustryKnowledge> listAll(int limit) {
        return repository.listAll(limit);
    }

    public long countActive() {
        return repository.countActive();
    }

    /** 准入结果：created / updated / rejected。 */
    public record AdmitResult(Outcome outcome, IndustryKnowledge entry, String rejection) {
        public enum Outcome { CREATED, UPDATED, REJECTED }

        static AdmitResult created(IndustryKnowledge e) {
            return new AdmitResult(Outcome.CREATED, e, null);
        }

        static AdmitResult updated(IndustryKnowledge e) {
            return new AdmitResult(Outcome.UPDATED, e, null);
        }

        static AdmitResult rejected(String reason) {
            return new AdmitResult(Outcome.REJECTED, null, reason);
        }
    }
}
