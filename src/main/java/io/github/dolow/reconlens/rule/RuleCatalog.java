package io.github.dolow.reconlens.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleRegistry;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.RuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时规则目录：规则的唯一权威来源是数据库（{@code recon_rule}），
 * classpath 下的 YAML 只在"库里还没有这个 code"时作为 v1 导入一次。
 *
 * <p>因此"改一条规则的比对维度"不再需要改代码、重新打包、重启进程：
 * {@link #defineMap} 走的就是 {@link RuleParser#parseMap} 那套校验，写入前先过同一道关。
 *
 * <p>缓存只是省一次反序列化：每次定义新版本都会让该 code 的缓存失效。
 */
@Service
public class RuleCatalog {

    private static final Logger log = LoggerFactory.getLogger(RuleCatalog.class);

    private final RuleRepository repository;
    private final RuleRegistry seedDocuments;
    private final ReconProperties properties;
    private final ObjectMapper json;
    private final RuleParser parser = new RuleParser();

    private final Map<String, Resolved> latestCache = new ConcurrentHashMap<>();
    private final Map<String, Resolved> versionCache = new ConcurrentHashMap<>();

    public RuleCatalog(RuleRepository repository, RuleRegistry seedDocuments, ReconProperties properties, ObjectMapper json) {
        this.repository = repository;
        this.seedDocuments = seedDocuments;
        this.properties = properties;
        this.json = json;
    }

    /** 一条解析好的规则：{@code version}/{@code snapshot} 为 null 表示不是从规则库来的（理论上不会发生）。 */
    public record Resolved(RuleSpec spec, Integer version, String snapshot) {
    }

    @PostConstruct
    void seedFromRuleDirectory() {
        if (!properties.isSeedRules()) {
            log.info("已关闭规则目录导入（recon.seed-rules=false），规则完全以数据库为准");
            return;
        }
        List<String> imported = new java.util.ArrayList<>();
        for (RuleRegistry.SeedDocument doc : seedDocuments.documents()) {
            if (!repository.listVersions(doc.code()).isEmpty()) {
                continue; // 库里已有（可能是改过的版本），YAML 不再覆盖
            }
            Map<String, Object> root = parser.readYaml(doc.yaml(), doc.source());
            RuleSpec spec = parser.parseMap(root, doc.source());
            repository.insert(spec.code(), 1, writeJson(root, doc.source()), "seed:" + doc.source());
            imported.add(spec.code());
        }
        if (!imported.isEmpty()) {
            log.info("规则库首次导入 {} 条规则目录规则: {}", imported.size(), imported);
        } else {
            log.info("规则库已有 {} 条规则，跳过规则目录导入", repository.listLatest().size());
        }
    }

    public Resolved resolve(String code) {
        return latestCache.computeIfAbsent(code, c -> repository.findLatest(c)
                .map(this::toResolved)
                .orElseThrow(() -> new RuleException("未找到规则 " + c + "，可用规则: " + codes())));
    }

    /** version 为 null 时取最新版本。 */
    public Resolved resolve(String code, Integer version) {
        if (version == null) {
            return resolve(code);
        }
        return versionCache.computeIfAbsent(code + "#" + version, key -> repository.findByVersion(code, version)
                .map(this::toResolved)
                .orElseThrow(() -> new RuleException("规则 " + code + " 没有版本 v" + version)));
    }

    public List<Resolved> allResolved() {
        return repository.listLatest().stream().map(this::toResolved).toList();
    }

    public List<RuleSpec> all() {
        return allResolved().stream().map(Resolved::spec).toList();
    }

    public List<String> codes() {
        return repository.listLatest().stream().map(RuleRepository.RuleRow::code).toList();
    }

    public List<RuleRepository.RuleRow> versions(String code) {
        return repository.listVersions(code);
    }

    public boolean exists(String code) {
        return repository.findLatest(code).isPresent();
    }

    /** 下线规则：新任务不再能选它，历史任务与快照照旧。 */
    public int disable(String code) {
        int affected = repository.disable(code);
        if (affected > 0) {
            evict(code);
            log.info("规则 {} 已下线（{} 个版本）", code, affected);
        }
        return affected;
    }

    /** 新增/修改规则：校验 → 落一个新版本 → 让缓存失效。规则"改维度"就是再调一次这里。 */
    public Resolved defineMap(Map<String, Object> root, String source, String createdBy) {
        RuleSpec spec = parser.parseMap(root, source);
        int version = repository.nextVersion(spec.code());
        String snapshot = writeJson(root, source);
        repository.insert(spec.code(), version, snapshot, createdBy);
        evict(spec.code());
        log.info("规则 {} 落库为 v{}（来源 {}，操作人 {}）", spec.code(), version, source, createdBy);
        return new Resolved(spec, version, snapshot);
    }

    public Resolved defineJson(String jsonText, String source, String createdBy) {
        return defineMap(readJson(jsonText, source), source, createdBy);
    }

    public Resolved defineYaml(String yamlText, String source, String createdBy) {
        return defineMap(parser.readYaml(yamlText, source), source, createdBy);
    }

    public Optional<Resolved> latestIfPresent(String code) {
        return repository.findLatest(code).map(this::toResolved);
    }

    /** 把任务里存下来的规则快照解析回 {@link RuleSpec}：重跑照旧语义执行，不受后来的规则改动影响。 */
    public RuleSpec parseSnapshot(String snapshot, String source) {
        return parser.parseMap(readJson(snapshot, source), source);
    }

    /**
     * 请求里内联的规则：和入库走同一套校验，但不落规则库。
     * 任务里仍然会存快照，所以"临时换维度跑一次"照样可以断点重跑。
     */
    public Resolved inline(Map<String, Object> root, String source) {
        RuleSpec spec = parser.parseMap(root, source);
        return new Resolved(spec, null, writeJson(root, source));
    }

    public Resolved inlineYaml(String yamlText, String source) {
        return inline(parser.readYaml(yamlText, source), source);
    }

    public Resolved inlineJson(String jsonText, String source) {
        return inline(readJson(jsonText, source), source);
    }

    private void evict(String code) {
        latestCache.remove(code);
    }

    private Resolved toResolved(RuleRepository.RuleRow row) {
        String source = "规则库 " + row.code() + " v" + row.version();
        RuleSpec spec = parser.parseMap(readJson(row.specJson(), source), source);
        return new Resolved(spec, row.version(), row.specJson());
    }

    private String writeJson(Map<String, Object> root, String source) {
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw RuleException.at(source, "规则无法序列化留档: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> readJson(String text, String source) {
        try {
            Map<String, Object> root = json.readValue(text, new TypeReference<Map<String, Object>>() {
            });
            if (root == null) {
                throw RuleException.at(source, "规则体为空");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw RuleException.at(source, "规则 JSON 解析失败: " + e.getOriginalMessage());
        }
    }
}
