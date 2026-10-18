package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.Coverage;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.domain.TrialVerdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T0–T7 → {@code (verdict, coverage, reasons)} 的<b>唯一</b>推导点。
 *
 * <p>设计文档里那张 5×5 矩阵只是这个类的外在投影。把推导写成纯函数（无 I/O、无时间、无随机），
 * 是为了让"为什么这条规则被判成 {@code INCONCLUSIVE × SAMPLE_PARTIAL}"能对着一个 record 复算 ——
 * 否则一旦判定错了，只能靠重跑数据库去猜。
 *
 * <h2>两条顺序规则，都不是随手写的</h2>
 *
 * <p><b>① {@code coverage = NONE} 或 {@code FIELDS_ONLY} 时，verdict 只能是 {@code INCONCLUSIVE}。</b>
 * 理由是语义而非矩阵约束：{@code NONE} 意味着"闸门自己坏了"（canary 未过 / 整版闸门不可信），
 * {@code FIELDS_ONLY} 意味着"压根没物化，只做了静态校验"。这两种情况下<b>不存在"规则坏"的结论</b> ——
 * 说一条规则 FAIL，前提是"我们真的验过它"。
 * 顺带它也解掉了一个真实的构造冲突：{@code canary 失败 + T1 也失败} 如果先判 FAIL，
 * 就会得到 {@code FAIL × NONE}，而这一格是非法组合、构造即抛异常，等于让闸门故障升级成服务不可用。
 *
 * <p><b>② T1–T4 的硬失败优先于 T5 的"样本不足"。</b>T5 不过时样本本来就不可靠，
 * 若此时 T1 已经明确失败（规则跑不通），判 {@code FAIL} 比判 {@code INCONCLUSIVE} 更诚实 ——
 * 前者是"这条规则有问题"，后者是"我们没验清楚"，而这两件事的人工处置完全相反。
 */
public class TrialVerdictResolver {

    /**
     * 直接持有配置对象而不是把四个数字拷进来：{@link GateVersion} 也是从同一个对象算的。
     * 拷贝会制造一个隐蔽的裂缝 —— 改了阈值之后，版本号变了（旧 canary 失效、重新自检），
     * 但判据还在用旧值，于是"重新自检通过"与"实际判定"用的不是一套阈值。
     */
    private final ReconProperties.Trial config;

    public TrialVerdictResolver(ReconProperties.Trial config) {
        this.config = config;
    }

    public TrialState resolve(TrialOutcome outcome) {
        Set<CoverageReason> reasons = new LinkedHashSet<>();

        if (outcome.blockage() != null) {
            reasons.add(outcome.blockage());
        }
        TrialOutcome.Canary canary = outcome.canary();
        if (canary != null && !canary.runnable()) {
            reasons.add(CoverageReason.CANARY_NO_CLEAN_ROW);
        } else if (canary != null && !canary.passed()) {
            reasons.add(CoverageReason.CANARY_FAILED);
        }

        TrialOutcome.SampleStats sample = outcome.sample() == null
                ? TrialOutcome.SampleStats.empty() : outcome.sample();

        // 上游 SQL 自带 LIMIT：物化成功了，但物化到的只是原查询被截断后的一角。
        // 只在"真的物化了"时记 —— 未物化时 coverage 已经是 FIELDS_ONLY（比 SAMPLE_PARTIAL 更差），
        // 再挂一条"样本被截断"会让报告自相矛盾（样本行数是 0，却说样本被截断），
        // 也会把审核人的注意力从"根本没验"引到"样本不太好"。
        if (outcome.materialized() && outcome.upstreamTruncated()) {
            reasons.add(CoverageReason.UPSTREAM_LIMIT);
        }

        // T2 的摘要溢出：两次都是空摘要，"一致"只是两个空集合相等。这一项没验成，
        // 不能算 PASS——但也不该判 FAIL（规则本身没证据说明它坏了），落 INCONCLUSIVE。
        if (outcome.materialized() && outcome.digestOverflowed()) {
            reasons.add(CoverageReason.DIGEST_OVERFLOW);
        }

        boolean sampleTooSmall = false;
        boolean commonKeysTooFew = false;
        boolean shardCoverageShort = false;
        if (outcome.materialized()) {
            sampleTooSmall = sample.leftRows() < config.getMinSampleRows()
                    || sample.rightRows() < config.getMinSampleRows();
            commonKeysTooFew = sample.commonKeys() < config.getMinCommonKeys();
            shardCoverageShort = !shardCoverageEnough(sample);
            if (sampleTooSmall || commonKeysTooFew) {
                reasons.add(CoverageReason.SAMPLE_TOO_SMALL);
            }
            if (shardCoverageShort) {
                reasons.add(CoverageReason.SHARD_COVERAGE);
            }
        }

        Coverage coverage = deriveCoverage(outcome.materialized(), reasons);
        boolean canaryNotRunnable = canary != null && !canary.runnable();
        TrialVerdict verdict = deriveVerdict(outcome, coverage, canaryNotRunnable,
                sampleTooSmall || commonKeysTooFew || shardCoverageShort);
        List<CoverageReason> ordered = reasons.stream()
                .sorted(Comparator.comparingInt(CoverageReason::severity).reversed())
                .toList();
        return new TrialState.Trialed(verdict, coverage, ordered);
    }

    /**
     * {@code coverage} 只由这里给出。<b>不是</b>"取 reasons 里最严重那个再查表"——
     * 那会把"没物化"和"物化了但样本偏"这两件性质不同的事压成同一个维度。
     */
    Coverage deriveCoverage(boolean materialized, Set<CoverageReason> reasons) {
        if (reasons.contains(CoverageReason.CANARY_FAILED) || reasons.contains(CoverageReason.GATE_UNTRUSTED)) {
            // 判定链自己不可信时，取再多行也等于没验 —— 这一格的语义是"这份判定不可信"，不是"我取了多少行"
            return Coverage.NONE;
        }
        if (!materialized) {
            return Coverage.FIELDS_ONLY;
        }
        return reasons.isEmpty() ? Coverage.FULL : Coverage.SAMPLE_PARTIAL;
    }

    private TrialVerdict deriveVerdict(TrialOutcome outcome, Coverage coverage, boolean canaryNotRunnable,
                                       boolean sampleInsufficient) {
        if (coverage == Coverage.NONE || coverage == Coverage.FIELDS_ONLY) {
            return TrialVerdict.INCONCLUSIVE;
        }
        if (Boolean.FALSE.equals(outcome.jobSucceeded())) {
            return TrialVerdict.FAIL;                   // T1 规则跑不通
        }
        if (Boolean.FALSE.equals(outcome.idempotent())) {
            return TrialVerdict.FAIL;                   // T2 同一份样本两次结果不同
        }
        if (Boolean.FALSE.equals(outcome.coverageLossExplained())) {
            return TrialVerdict.FAIL;                   // T3 少比了东西而没人知道
        }
        if (Boolean.FALSE.equals(outcome.zeroDiffExplained())) {
            return TrialVerdict.FAIL;                   // T4 差异归零但解释不了
        }
        if (canaryNotRunnable) {
            // 自检跑不了 ≠ 闸门坏了（干净键不够，说明这条规则差异率异常高）。
            // 但它也绝不是 PASS：一次没能自证的自检，不能当"通过"用。
            return TrialVerdict.INCONCLUSIVE;
        }
        if (outcome.digestOverflowed()) {
            return TrialVerdict.INCONCLUSIVE;           // T2 摘要溢出，幂等性没验成
        }
        if (sampleInsufficient) {
            return TrialVerdict.INCONCLUSIVE;           // T5 试算真空，等于没试
        }
        return TrialVerdict.PASS;
    }

    /** T5-c：{@code hit >= required}。分母恒为基线（无基线才退回总片数），绝不硬性要求 "≥2 片"。 */
    boolean shardCoverageEnough(TrialOutcome.SampleStats sample) {
        if (sample.shardCount() <= 1) {
            return true;        // 单分片规则没有"跨分片行为"可验，这一项不适用
        }
        int baseline = sample.baselineShardsWithData() > 0 ? sample.baselineShardsWithData() : sample.shardCount();
        return sample.shardsWithData() >= requiredShards(baseline);
    }

    /**
     * T5-c 的阈值公式 —— <b>本方法就是唯一出处</b>，设计文档里那张 {@code b=1..10} 表只是它的一次投影。
     * 三个约束各司其职：下限常量管"绝对最低"，比例管"相对最低"，两者取大，再由基线封顶。
     * 任一单独使用都会在某个 {@code b} 上失效（它连错两轮的原因）。
     */
    public int requiredShards(int baselineHit) {
        if (baselineHit <= 0) {
            return 0;   // 交给 T5-a 报"样本为空"，不要在这里造一个"required=0 恒通过"
        }
        return Math.min(baselineHit,
                Math.max(config.getMinShardsWithData(),
                        (int) Math.floor(config.getMinShardCoverageRatio() * baselineHit)));
    }

    /** 调试/报告用：把"为什么是这个覆盖等级"展开成人能读的一行。 */
    public static String describe(TrialState.Trialed trialed) {
        List<CoverageReason> reasons = new ArrayList<>(trialed.reasons());
        return trialed.verdict() + " / " + trialed.coverage()
                + (reasons.isEmpty() ? "" : " / 主理由=" + CoverageReason.main(reasons));
    }
}
