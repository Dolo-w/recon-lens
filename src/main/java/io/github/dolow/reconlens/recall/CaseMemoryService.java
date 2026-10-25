package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.CaseMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 案例记忆：写回（人确认 vs 模型自述）与检索（按相关度 + 时间衰减）。
 *
 * <p>一条设计约束：<b>写回不改结论，只记案例</b>。原因归因仍走 {@code recon_diff_kb}，
 * 这里是"上次这类差异谁怎么处理的"，用来给人参考，不自动套用。
 */
public class CaseMemoryService {

    private static final Logger log = LoggerFactory.getLogger(CaseMemoryService.class);
    private static final int CANDIDATE_LIMIT = 50;

    private final CaseMemoryRepository repository;

    public CaseMemoryService(CaseMemoryRepository repository) {
        this.repository = repository;
    }

    /** 人确认的处理方式：可信度 0.9。 */
    public long rememberByHuman(String ruleCode, String diffType, String causeKey, String resolution,
                                String operator, Long diffId, Long jobId) {
        return rememberByHuman(ruleCode, diffType, causeKey, resolution, operator, diffId, jobId, null);
    }

    /**
     * @param datasetVersion ADR-0002 的数据版本；知道就传，不知道传 null（老数据/调用方没有任务上下文）
     */
    public long rememberByHuman(String ruleCode, String diffType, String causeKey, String resolution,
                                String operator, Long diffId, Long jobId, String datasetVersion) {
        return remember(ruleCode, diffType, causeKey, resolution, "HUMAN",
                CaseMemory.HUMAN_CONFIDENCE, operator, diffId, jobId, datasetVersion);
    }

    /** 模型建议的处理方式：可信度 0.5，且必须能看出它不是人定的。 */
    public long rememberByModel(String ruleCode, String diffType, String causeKey, String resolution,
                                Long diffId, Long jobId) {
        return rememberByModel(ruleCode, diffType, causeKey, resolution, diffId, jobId, null);
    }

    public long rememberByModel(String ruleCode, String diffType, String causeKey, String resolution,
                                Long diffId, Long jobId, String datasetVersion) {
        return remember(ruleCode, diffType, causeKey, resolution, "LLM",
                CaseMemory.LLM_CONFIDENCE, null, diffId, jobId, datasetVersion);
    }

    private long remember(String ruleCode, String diffType, String causeKey, String resolution,
                          String source, double confidence, String operator, Long diffId, Long jobId,
                          String datasetVersion) {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode 不能为空");
        }
        if (resolution == null || resolution.isBlank()) {
            throw new IllegalArgumentException("resolution 不能为空：案例记忆的价值就在于「当时怎么处理的」");
        }
        String key = causeKey == null || causeKey.isBlank() ? diffType : causeKey;
        long id = repository.insert(ruleCode, diffType == null ? "UNKNOWN" : diffType, key,
                resolution.trim(), source, confidence, operator, diffId, jobId, datasetVersion);
        log.info("case-memory 写入 id={} rule={} type={} source={} conf={}", id, ruleCode, diffType, source, confidence);
        return id;
    }

    /** 检索：同规则同类型取候选，按"可信度 × 新鲜度"重排。命中会累加 hits。 */
    public List<CaseHit> recall(String ruleCode, String diffType, int limit) {
        return recall(ruleCode, diffType, null, limit);
    }

    /**
     * @param datasetVersion 非空时只取"同数据版本"与"没记版本"的案例：口径变了，旧案例就不该被当成同源经验
     */
    public List<CaseHit> recall(String ruleCode, String diffType, String datasetVersion, int limit) {
        List<CaseMemory> candidates = repository.candidates(ruleCode, diffType, datasetVersion, CANDIDATE_LIMIT);
        Instant now = Instant.now();
        List<CaseHit> hits = new ArrayList<>();
        for (CaseMemory memory : candidates) {
            hits.add(new CaseHit(memory, memory.freshness(now)));
        }
        hits.sort(Comparator.comparingDouble(CaseHit::score).reversed());
        List<CaseHit> top = hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
        for (CaseHit hit : top) {
            repository.touch(hit.memory().id());
        }
        return top;
    }

    public Optional<CaseMemory> latestHuman(String ruleCode, String causeKey) {
        return repository.latestHuman(ruleCode, causeKey);
    }

    public List<CaseMemory> recent(String ruleCode, int limit) {
        return repository.list(ruleCode, limit);
    }

    public int count() {
        return repository.count();
    }

    /** 给回答用的一段话；没有案例时返回空串，调用方就不追加这一段。 */
    public String render(String ruleCode, String diffType, int limit) {
        return render(ruleCode, diffType, null, limit);
    }

    public String render(String ruleCode, String diffType, String datasetVersion, int limit) {
        List<CaseHit> hits = recall(ruleCode, diffType, datasetVersion, limit);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("同类差异的历史处理（按可信度×新鲜度排序，仅供参考，不自动套用）：\n");
        for (CaseHit hit : hits) {
            CaseMemory m = hit.memory();
            sb.append("· ").append(m.causeKey()).append(" → ").append(m.resolution())
                    .append("（").append(m.byHuman() ? "人确认" : "模型建议")
                    .append(" ").append(String.format("%.1f", m.confidence())).append("，")
                    .append(m.operator() == null ? "无操作人" : m.operator());
        if (m.datasetVersion() != null) {
            sb.append("，数据版本 ").append(m.datasetVersion());
        }
            if (m.hits() > 0) {
                sb.append("，被引用 ").append(m.hits()).append(" 次");
            }
            sb.append("）\n");
        }
        return sb.toString();
    }
}
