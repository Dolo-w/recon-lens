package io.github.dolow.reconlens.explain.pattern;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 经验模式库（K2，L0 层）：机械特征 → 候选假设，跨规则、跨客户生效。
 *
 * <p><b>知识在哪、以什么形态存</b>：YAML 文件进仓库（classpath:patterns/diff-patterns.yml），
 * 与规则 YAML 同款哲学——git 可审、可 diff、改动走 PR，改坏一个模式不会污染任何人的数据。
 * 客户可再加一个外部目录（{@code recon.explain.patterns.dir}），里面的 *.yml 与内置库合并，
 * 同 id 外部覆盖内置（客户定制自己的病），文件改动按 mtime 热加载。
 *
 * <p><b>降级立场</b>（v4 验收 3）：任何一个 YAML 加载失败都只是一条 warn——
 * 模式库是"归因更聪明"的增强，不是主链路的依赖；坏知识绝不能挡住对账与归因。
 * 加载失败的文件被跳过，其余照常可用；全挂了 = L0 层空转，归因退回 L1 → LLM → 模板。
 *
 * <p>不是数据库表的理由（与知识库存储选型结论一致）：模式是"代码的一部分"而非"运行时数据"，
 * 改它就该有 commit 记录；量级是几十条，内存比对零成本。
 */
@Component
public class PatternLibrary {

    private static final Logger log = LoggerFactory.getLogger(PatternLibrary.class);

    private static final String CLASSPATH_LOCATION = "patterns/diff-patterns.yml";

    private final ReconProperties.Explain.Patterns config;

    /** 内置 + 外部合并后的全量模式（不可变）。 */
    private volatile List<DiffPattern> patterns = List.of();

    /** 外部文件的 mtime 快照，变了才重载（便宜：文件数是个位数）。 */
    private volatile Map<Path, Long> externalStamps = Map.of();

    public PatternLibrary(ReconProperties properties) {
        this.config = properties.getExplain().getPatterns();
        reload();
    }

    /**
     * 匹配一条差异，返回候选模式（库内声明顺序，截到 maxCandidates）。
     * 关闭/空库/匹配失败都返回空列表 —— 调用方据此落到下一层。
     */
    public List<DiffPattern> match(DiffRecord diff) {
        if (!config.isEnabled()) {
            return List.of();
        }
        checkExternalReload();
        List<DiffPattern> hits = new ArrayList<>();
        for (DiffPattern pattern : patterns) {
            try {
                if (pattern.matches(diff)) {
                    hits.add(pattern);
                    if (hits.size() >= config.getMaxCandidates()) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                // 单条模式的判定出错不拖累其余模式：模式库自身坏一点，归因主链路不能跟着坏
                log.warn("模式 {} 匹配异常（跳过）：{}", pattern.id(), e.toString());
            }
        }
        return hits;
    }

    /** 全量模式（给 recon_list_patterns / REST 展示）。 */
    public List<DiffPattern> all() {
        checkExternalReload();
        return patterns;
    }

    public int size() {
        return all().size();
    }

    public boolean enabled() {
        return config.isEnabled();
    }

    // ------------------------------------------------------------------ 加载

    private synchronized void reload() {
        Map<String, DiffPattern> merged = new LinkedHashMap<>();
        loadClasspath(merged);
        loadExternal(merged);
        List<DiffPattern> next = List.copyOf(merged.values());
        this.patterns = next;
        log.info("经验模式库就绪：{} 条模式（内置 + 外部合并）", next.size());
    }

    private void loadClasspath(Map<String, DiffPattern> merged) {
        try (InputStream in = new ClassPathResource(CLASSPATH_LOCATION).getInputStream()) {
            parseInto(merged, "classpath:" + CLASSPATH_LOCATION, in, false);
        } catch (IOException e) {
            log.warn("内置模式库加载失败（L0 层降级为空）：{}", e.toString());
        }
    }

    private void loadExternal(Map<String, DiffPattern> merged) {
        String dir = config.getDir();
        if (dir == null || dir.isBlank()) {
            this.externalStamps = Map.of();
            return;
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            log.warn("外部模式库目录不存在：{}（只用内置库）", root);
            this.externalStamps = Map.of();
            return;
        }
        Map<Path, Long> stamps = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    parseInto(merged, "file:" + p, in, true);
                    try {
                        stamps.put(p, Files.getLastModifiedTime(p).toMillis());
                    } catch (IOException ignored) {
                        // mtime 读不到就放弃对该文件的热加载，不影响内容已加载
                    }
                } catch (IOException e) {
                    log.warn("外部模式文件 {} 加载失败（跳过）：{}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("外部模式库目录读取失败（只用内置库）：{} — {}", root, e.toString());
        }
        this.externalStamps = Map.copyOf(stamps);
    }

    /** 单个 YAML 源解析进 merged；{@code external=true} 时同 id 覆盖内置。 */
    @SuppressWarnings("unchecked")
    private void parseInto(Map<String, DiffPattern> merged, String source, InputStream in, boolean external) {
        Object loaded;
        try {
            loaded = new Yaml().load(in);
        } catch (RuntimeException e) {
            log.warn("模式库 YAML 解析失败（跳过整份文件）：{} — {}", source, e.toString());
            return;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            log.warn("模式库 YAML 根节点必须是映射（跳过）：{}", source);
            return;
        }
        Object items = ((Map<String, Object>) root).get("patterns");
        if (!(items instanceof List<?> list)) {
            log.warn("模式库 YAML 缺 patterns 列表（跳过）：{}", source);
            return;
        }
        int added = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            try {
                DiffPattern pattern = DiffPattern.fromMap(source, (Map<String, Object>) item);
                if (!external && merged.containsKey(pattern.id())) {
                    log.warn("内置模式 id 重复（后者覆盖）：{}", pattern.id());
                }
                merged.put(pattern.id(), pattern);
                added++;
            } catch (RuntimeException e) {
                // 单条模式坏了跳过该条，不是跳过整份文件
                log.warn("模式定义无效（跳过该条）：{} — {}", source, e.toString());
            }
        }
        log.info("模式库来源 {} 装载 {} 条", source, added);
    }

    /** 外部文件 mtime 变了才重载（每次 match 调用都会走到这里，必须便宜）。 */
    private void checkExternalReload() {
        if (externalStamps.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, Long> entry : externalStamps.entrySet()) {
            try {
                long current = Files.getLastModifiedTime(entry.getKey()).toMillis();
                if (current != entry.getValue()) {
                    log.info("外部模式文件变更，重新加载模式库：{}", entry.getKey());
                    reload();
                    return;
                }
            } catch (IOException ignored) {
                // 文件被删了等异常：静默，等下次目录扫描自然修正
            }
        }
    }
}
