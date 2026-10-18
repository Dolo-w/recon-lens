package io.github.dolow.reconlens.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.domain.RuleOrigin;
import io.github.dolow.reconlens.dsl.RuleException;
import io.github.dolow.reconlens.dsl.RuleParser;
import io.github.dolow.reconlens.dsl.RuleRegistry;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.RuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时规则目录：规则的唯一权威来源是数据库（{@code recon_rule}），
 * classpath 下的 YAML 在启动时对齐库里的同名规则 —— 库里的最新启用版本与文件内容一致就什么都不做，
 * 不一致就把文件这一版落成新版本（见 {@link #seedFromRuleDirectory()}）。
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

    /**
     * 启动时把规则目录（classpath / {@code file:} 下的 YAML）与规则库对齐：
     * <ul>
     *   <li>库里没有这个 code → 作为 v1 导入；</li>
     *   <li>库里的最新启用版本与文件内容<b>逐字段相同</b> → 什么都不做（重启不会凭空长版本）；</li>
     *   <li>不同 → <b>覆盖</b>：把文件这一版落成一个新版本（覆盖是升级版本，不删旧版本，"文件是权威"）；</li>
     *   <li>该 code 已被显式下线（{@code DELETE /api/rules/{code}}）→ 不覆盖也不复活。</li>
     * </ul>
     * 想让规则库完全自己说了算（库里的改动不被文件覆盖），把 {@code recon.seed-rules} 设为 false。
     */
    /**
     * 历史行的内容哈希回填<b>无条件</b>执行：它和"规则目录是不是权威"没有关系。
     *
     * <p>以前这句挂在 {@link #seedFromRuleDirectory()} 里，生产按建议配 {@code recon.seed-rules=false}
     * 时整段提前 return —— 于是迁移前落库的行 {@code reviewed_spec_sha} 永远为 NULL，
     * 而 NULL 在篡改检测里是"从没审过，不适用"，等于给老规则永久豁免。放到单独的
     * {@code @PostConstruct} 就不依赖任何开关，且与 seed 的先后顺序无关（回填只读 spec_json）。
     */
    @PostConstruct
    void backfillLegacySpecSha() {
        backfillSpecSha();
    }

    @PostConstruct
    void seedFromRuleDirectory() {
        if (!properties.isSeedRules()) {
            log.info("已关闭规则目录导入（recon.seed-rules=false），规则完全以数据库为准");
            return;
        }
        List<String> imported = new java.util.ArrayList<>();
        List<String> refreshed = new java.util.ArrayList<>();
        List<String> untouched = new java.util.ArrayList<>();
        List<String> disabled = new java.util.ArrayList<>();
        for (RuleRegistry.SeedDocument doc : seedDocuments.documents()) {
            Map<String, Object> root = parser.readYaml(doc.yaml(), doc.source());
            RuleSpec spec = parser.parseMap(root, doc.source());
            String createdBy = "seed:" + doc.source();
            List<RuleRepository.RuleRow> versions = repository.listVersions(doc.code());
            if (versions.isEmpty()) {
                repository.insert(spec.code(), 1, writeJson(root, doc.source()), createdBy);
                imported.add(spec.code());
                continue;
            }
            Optional<RuleRepository.RuleRow> latest = versions.stream()
                    .filter(RuleRepository.RuleRow::runtimeVisible)
                    .findFirst();
            if (latest.isEmpty()) {
                disabled.add(spec.code()); // 下线是显式动作，重启不该把它复活
                continue;
            }
            RuleRepository.RuleRow current = latest.get();
            // 比较解析后的规则体而不是字符串：YAML 与 JSON 的字面差异不该算"改过"
            if (readJson(current.specJson(), "规则库 " + doc.code() + " v" + current.version()).equals(root)) {
                untouched.add(spec.code());
                continue;
            }
            int version = repository.nextVersion(doc.code());
            repository.insert(doc.code(), version, writeJson(root, doc.source()), createdBy);
            evict(doc.code());
            refreshed.add(doc.code() + " v" + current.version() + "→v" + version);
            if (!isSeeded(current.createdBy())) {
                log.warn("规则 {} 的 v{} 是 {} 改的，与规则目录 {} 内容不一致，已按文件覆盖为 v{}"
                                + "（不想被覆盖就把 recon.seed-rules 设为 false）",
                        doc.code(), current.version(), current.createdBy(), doc.source(), version);
            }
        }
        if (!imported.isEmpty()) {
            log.info("规则库首次导入 {} 条规则目录规则: {}", imported.size(), imported);
        }
        if (!refreshed.isEmpty()) {
            log.info("规则目录覆盖了 {} 条与文件不一致的规则: {}", refreshed.size(), refreshed);
        }
        if (!disabled.isEmpty()) {
            log.info("规则 {} 已被显式下线，规则目录既不覆盖也不复活（要恢复请重新定义规则）", disabled);
        }
        if (imported.isEmpty() && refreshed.isEmpty()) {
            log.info("规则目录 {} 条规则与规则库一致，未新增版本", untouched.size());
        }
    }

    private static boolean isSeeded(String createdBy) {
        return createdBy != null && createdBy.startsWith("seed:");
    }

    /**
     * 迁移后历史行没有 {@code spec_sha256}。由应用侧逐行回填，<b>不在 SQL 里算</b>：
     * 哈希必须与运行时的 {@link SpecHasher} 完全一致，SQL 侧另写一份算法等于制造第二个口径。
     */
    private void backfillSpecSha() {
        List<RuleRepository.RuleRow> missing = repository.listMissingSpecSha();
        for (RuleRepository.RuleRow row : missing) {
            repository.updateSpecSha(row.code(), row.version(), SpecHasher.sha256(row.specJson()));
        }
        if (!missing.isEmpty()) {
            log.info("回填了 {} 个历史规则版本的 spec_sha256（迁移前落库的行没有内容哈希）", missing.size());
        }
    }

    /**
     * 内容哈希比对（R9）：审批记录贴在 {@code reviewed_spec_sha} 上，而 {@code spec_json} 是普通列，
     * DBA / 脚本可以直接 UPDATE。不比对的话，审批就变成了贴在"一份它没审过的规则体"上。
     */
    private boolean contentTampered(RuleRepository.RuleRow row) {
        return row.reviewedSpecSha() != null
                && !row.reviewedSpecSha().equalsIgnoreCase(SpecHasher.sha256(row.specJson()));
    }

    /**
     * 缓存命中也要复核内容哈希。
     *
     * <p>早先 {@code resolve} 命中 {@code latestCache} 就直接返回，等于"审批后改内容"的检测
     * 只在冷缓存那一瞬间有效——进程一直活着，这条检测就永远不触发（实测能跑出被改过的规则）。
     *
     * <p>现在每次 resolve 先读一行守卫行（{@code (code, version)} 索引，单行读），
     * 用<b>库里的 {@code spec_json} 现算哈希</b>与 {@code reviewed_spec_sha} 比对：
     * 只比两个已存列是不够的——改 {@code spec_json} 的人同样能改 {@code spec_sha256}，
     * 而审批钉住的是 {@code reviewed_spec_sha}，它才是不可篡改的那一侧。
     */
    private void verifyGuard(String code, int version, String specJson, String reviewedSpecSha) {
        if (reviewedSpecSha == null || reviewedSpecSha.equalsIgnoreCase(SpecHasher.sha256(specJson))) {
            return;
        }
        // 降级回 REVIEWING：这时"生效中"的已经不是审批时那份内容，继续跑等于绕过闸门 3。
        repository.transition(code, version, RuleLifecycle.ACTIVE, RuleLifecycle.REVIEWING);
        evict(code);
        log.error("规则 {} v{} 的内容在审批后被修改（reviewed_spec_sha 不匹配），已降级回 REVIEWING，需重新审核",
                code, version);
        throw new RuleException("规则 " + code + " v" + version
                + " 的内容在审批后被修改（reviewed_spec_sha 不匹配），已降级回 REVIEWING，需重新审核后才能运行");
    }

    private void verifyReviewedContent(RuleRepository.RuleRow row) {
        verifyGuard(row.code(), row.version(), row.specJson(), row.reviewedSpecSha());
    }

    public Resolved resolve(String code) {
        // 先读守卫行（索引上的单行读），缓存命中也要过这一关。
        RuleRepository.RuleGuard guard = repository.findGuard(code)
                .orElseThrow(() -> new RuleException("未找到规则 " + code + "，可用规则: " + codes()));
        verifyGuard(code, guard.version(), guard.specJson(), guard.reviewedSpecSha());
        Resolved cached = latestCache.get(code);
        if (cached != null && cached.version() != null && cached.version() == guard.version()) {
            return cached;
        }
        RuleRepository.RuleRow row = repository.findLatest(code)
                .orElseThrow(() -> new RuleException("未找到规则 " + code + "，可用规则: " + codes()));
        Resolved resolved = toResolved(row);
        latestCache.put(code, resolved);
        return resolved;
    }

    /** version 为 null 时取最新生效版本。 */
    public Resolved resolve(String code, Integer version) {
        if (version == null) {
            return resolve(code);
        }
        RuleRepository.RuleGuard guard = repository.findGuard(code, version)
                .orElseThrow(() -> new RuleException("规则 " + code + " 没有版本 v" + version));
        verifyGuard(code, guard.version(), guard.specJson(), guard.reviewedSpecSha());
        Resolved cached = versionCache.get(code + "#" + version);
        if (cached != null) {
            return cached;
        }
        RuleRepository.RuleRow row = repository.findByVersion(code, version)
                .orElseThrow(() -> new RuleException("规则 " + code + " 没有版本 v" + version));
        Resolved resolved = toResolved(row);
        versionCache.put(code + "#" + version, resolved);
        return resolved;
    }

    public List<Resolved> allResolved() {
        List<Resolved> resolved = new ArrayList<>();
        for (RuleRepository.RuleRow row : repository.listLatest()) {
            if (contentTampered(row)) {
                // 列表里不展示"审批后被改过"的版本；真正的降级发生在 resolve（那里会写库）
                log.error("规则 {} v{} 的内容在审批后被修改，列表里跳过（resolve 时会降级回 REVIEWING）",
                        row.code(), row.version());
                continue;
            }
            resolved.add(toResolved(row));
        }
        return resolved;
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

    /**
     * 落一条<b>草稿</b>版本：{@code lifecycle=DRAFT}，{@code resolve} 不认，业务不可见。
     * 之后由闸门 3 走 DRAFT → REVIEWING → ACTIVE（见 {@code RuleLifecycleService}）。
     *
     * <p>为什么还保留 {@link #defineMap} 直通 ACTIVE：那是引入 Agent 之前的人工 REST 通路，
     * 改成草稿会让所有既有使用方（CI 脚本、运维）在升级当天发现"规则不生效了"。
     * <b>Agent 与需要审核的草稿一律走本方法</b>，两条路径写清楚，不靠约定。
     */
    public Resolved defineDraft(Map<String, Object> root, String source, String createdBy,
                                RuleOrigin origin, Integer baseVersion, String draftReason) {
        RuleSpec spec = parser.parseMap(root, source);
        int version = repository.nextVersion(spec.code());
        String snapshot = writeJson(root, source);
        repository.insert(spec.code(), version, snapshot, createdBy,
                RuleLifecycle.DRAFT, origin == null ? RuleOrigin.HUMAN : origin,
                baseVersion, draftReason, SpecHasher.sha256(snapshot));
        evict(spec.code());
        log.info("规则 {} 落库为草稿 v{}（来源 {}，起草人 {}，基于 v{}）",
                spec.code(), version, origin, createdBy, baseVersion);
        return new Resolved(spec, version, snapshot);
    }

    public Resolved defineYaml(String yamlText, String source, String createdBy) {
        return defineMap(parser.readYaml(yamlText, source), source, createdBy);
    }

    /**
     * 文本入口的草稿落库：JSON / YAML 自动识别，其余语义与 {@link #defineDraft} 完全一致。
     * MCP 等外部提交通走这里 —— 解析校验、DRAFT 状态、试算绑定一样都不少。
     */
    public Resolved defineDraftText(String body, String source, String createdBy,
                                    RuleOrigin origin, Integer baseVersion, String draftReason) {
        return body.strip().startsWith("{")
                ? defineDraft(readJson(body, source), source, createdBy, origin, baseVersion, draftReason)
                : defineDraft(parser.readYaml(body, source), source, createdBy, origin, baseVersion, draftReason);
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
        // 版本缓存也要清：审批 / 驳回 / 降级改的是某一版的状态，留着旧对象等于让缓存绕过闸门 3
        versionCache.keySet().removeIf(key -> key.startsWith(code + "#"));
    }

    /** 生命周期变更后让缓存失效（审批 / 驳回 / 内容哈希降级都要调）。 */
    public void invalidate(String code) {
        evict(code);
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
