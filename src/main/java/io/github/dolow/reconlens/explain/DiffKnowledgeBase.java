package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.store.DiffKnowledgeRepository;
import io.github.dolow.reconlens.store.DiffKnowledgeRepository.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 归因知识库：L1 层，零 LLM 成本。
 *
 * <p>它只做四件事 —— 查、记、学、列：
 * <ol>
 *   <li>{@link #lookup} 按 {@link CauseKey} 查 top-1；命中且置信度够 → 直接用，不再调模型；</li>
 *   <li>{@link #remember} 把模型的归因追加为新候选（{@code source=LLM}, {@code confidence=0.5}）；</li>
 *   <li>{@link #teach} 运营确认/修正后追加候选（{@code source=HUMAN}, {@code confidence=0.9}）；</li>
 *   <li>{@link #candidates} 列出键下完整候选集 —— few-shot 注入与运营复核用。</li>
 * </ol>
 *
 * <p><b>K2 多候选语义</b>：同形状差异可以有几种真实病因（9 月的税、10 月的手续费）。
 * 写路径只追加、永不覆盖 —— 人工确认过的知识在结构上就不可能被模型的下一次胡说冲掉。
 * 读路径取 top-1（置信度 → 命中数 → 新鲜度）；旧候选还在库里，降级场景可被翻出来。
 *
 * <p><b>为什么 {@code remember} 的置信度只有 0.5</b>：模型的自述不是事实。0.5 意味着
 * "可以复用，但一有人工修正就该被覆盖"。这是知识库里唯一可信的信任梯度 ——
 * 如果模型也能写 0.9，人机两条来源就无从区分，"人工确认"这个动作也就没有价值了。
 *
 * <p><b>命中率为什么有进程内计数器</b>：{@code recon_diff_kb.misses} 只能记在"已存在的行"上。
 * 完全没查到时没有行可以加一，命中率的分母就永久缺一块 —— 会得到一个虚高的命中率，
 * 正好是最容易自我安慰的那个方向。所以这里用 {@link Snapshot} 显式区分两种 miss。
 */
@Component
public class DiffKnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(DiffKnowledgeBase.class);

    private static final BigDecimal LLM_CONFIDENCE = new BigDecimal("0.500");

    private final DiffKnowledgeRepository repository;
    private final boolean enabled;
    private final BigDecimal minConfidence;

    /** 进程内计数：重启归零，所以要配合日志看趋势而不是当累计值。 */
    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong absent = new AtomicLong();
    private final AtomicLong lowConfidence = new AtomicLong();

    public DiffKnowledgeBase(DiffKnowledgeRepository repository, ReconProperties properties) {
        this.repository = repository;
        ReconProperties.Explain.Knowledge config = properties.getExplain().getKnowledge();
        this.enabled = config.isEnabled();
        this.minConfidence = BigDecimal.valueOf(config.getMinConfidence());
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 查一条归因。命中时顺手 {@code hits+1} —— 计的是"这一条被用过几次"，
     * 不是"被查过几次"：查了但被置信度挡下的，应该进 {@code misses}。
     */
    public Optional<Entry> lookup(String ruleCode, DiffRecord diff) {
        if (!enabled) {
            return Optional.empty();
        }
        CauseKey key = CauseKey.of(ruleCode, diff);
        lookups.incrementAndGet();
        Optional<Entry> found = repository.findTopByKey(key.digest());
        if (found.isEmpty()) {
            absent.incrementAndGet();
            return Optional.empty();
        }
        Entry entry = found.get();
        if (entry.confidence().compareTo(minConfidence) < 0) {
            lowConfidence.incrementAndGet();
            repository.bumpMisses(entry.id());
            log.debug("知识库命中但置信度不足，仍有 {} < {}：{}", entry.confidence(), minConfidence, key.describe());
            return Optional.empty();
        }
        repository.bumpHits(entry.id());
        hits.incrementAndGet();
        return Optional.of(entry);
    }

    /**
     * 把模型的归因追加为候选。同键同措辞不重复落（多候选模式的去重闸）。
     *
     * <p>写库失败不让归因失败：知识库是成本优化，不是正确性依赖 ——
     * 这里 {@code try/catch} 而不是向上抛，是为了让"库表不存在 / 索引冲突"
     * 变成一条 warn，而不是让一批差异全部没有解释。
     *
     * @return 真的写进去了才返回 {@code true}
     */
    public boolean remember(String ruleCode, DiffRecord diff, String cause) {
        if (!enabled || cause == null || cause.isBlank()) {
            return false;
        }
        CauseKey key = CauseKey.of(ruleCode, diff);
        String clipped = clip(cause, 1000);
        Entry entry = new Entry(0L, key.digest(), key.ruleCode(), key.diffType(), key.fieldName(),
                key.featureSignature(), clipped, null, LLM_CONFIDENCE,
                Entry.SOURCE_LLM, 0, 0, "agent", null, null);
        try {
            if (repository.existsByCauseKeyAndCause(key.digest(), clipped)) {
                return false;
            }
            return repository.insert(entry);
        } catch (RuntimeException e) {
            log.warn("归因写回知识库失败（不影响本次解释）：{} — {}", key.describe(), e.toString());
            return false;
        }
    }

    /**
     * 人工确认/修正。<b>追加</b>一条 {@code HUMAN}/0.9 候选，不覆盖旧行 ——
     * 9 月的"税"和 10 月的"手续费"可能都是对的，覆盖会把上个月的正确知识抹掉。
     * 这是置信度升到 0.9 的唯一入口。
     *
     * <p>同键同措辞的重复 teach 是幂等的（运营手抖点两次不该堆两行）。
     */
    public boolean teach(String ruleCode, DiffRecord diff, String cause, String action, String correctedBy) {
        if (cause == null || cause.isBlank()) {
            throw new IllegalArgumentException("人工修正的归因不能为空");
        }
        CauseKey key = CauseKey.of(ruleCode, diff);
        String clipped = clip(cause, 1000);
        try {
            if (repository.existsByCauseKeyAndCause(key.digest(), clipped)) {
                return true;
            }
            Entry entry = new Entry(0L, key.digest(), key.ruleCode(), key.diffType(), key.fieldName(),
                    key.featureSignature(), clipped, clip(action, 1000),
                    new BigDecimal("0.900"), Entry.SOURCE_HUMAN, 0, 0, correctedBy, null, null);
            return repository.insert(entry);
        } catch (RuntimeException e) {
            log.warn("人工归因写入知识库失败：{} — {}", key.describe(), e.toString());
            return false;
        }
    }

    /** 键下完整候选集（按 top-1 同序）。few-shot 注入与"这条差异历史上答过什么"用。 */
    public List<Entry> candidates(String ruleCode, DiffRecord diff, int limit) {
        if (!enabled || limit <= 0) {
            return List.of();
        }
        CauseKey key = CauseKey.of(ruleCode, diff);
        return repository.findCandidates(key.digest(), limit);
    }

    public List<Entry> recent(int limit) {
        return repository.recent(limit);
    }

    /** 命中率快照。{@code absent} 与 {@code lowConfidence} 分开记，因为它们要补的东西完全不同。 */
    public record Snapshot(long lookups, long hits, long absent, long lowConfidence) {
        public long misses() {
            return absent + lowConfidence;
        }

        public double hitRate() {
            return lookups == 0 ? 0.0 : (double) hits / lookups;
        }

        public String describe() {
            return String.format("查 %d 次，命中 %d 次（%.0f%%），无此键 %d，置信度不足 %d",
                    lookups, hits, hitRate() * 100, absent, lowConfidence);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(lookups.get(), hits.get(), absent.get(), lowConfidence.get());
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
