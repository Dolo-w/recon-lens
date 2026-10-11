package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则目录（YAML 文件）的加载器。运行时规则以数据库为准（见 {@code rule.RuleCatalog}），
 * 这里加载到的文件只在「规则库还没有这个 code」时作为初始版本导入（seed），
 * 所以目录为空不再是致命错误——MySQL profile 下运维可能还没准备 file:./rules/。
 *
 * <p>规则文件可以放在 classpath（打包内置），也可以放在 file: 目录。
 */
@Component
public class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    private final Map<String, RuleSpec> rules;
    private final List<SeedDocument> documents;

    public RuleRegistry(ReconProperties properties) {
        Loaded loaded = load(properties.getRulesLocation());
        this.rules = loaded.rules();
        this.documents = loaded.documents();
        log.info("规则目录 {} 提供 {} 条规则: {}", properties.getRulesLocation(), rules.size(), rules.keySet());
    }

    public RuleSpec get(String code) {
        RuleSpec spec = rules.get(code);
        if (spec == null) {
            throw new RuleException("未找到规则 " + code + "，规则目录里可用的规则: " + rules.keySet());
        }
        return spec;
    }

    public List<RuleSpec> all() {
        return List.copyOf(rules.values());
    }

    public Set<String> codes() {
        return rules.keySet();
    }

    /** 规则文件原文，供规则库首次导入（seed）时原样留档。 */
    public List<SeedDocument> documents() {
        return documents;
    }

    /** 一条规则文件的原文：code 是规则编号，source 是文件名（用于报错定位），yaml 是内容。 */
    public record SeedDocument(String code, String source, String yaml) {
    }

    private record Loaded(Map<String, RuleSpec> rules, List<SeedDocument> documents) {
    }

    private Loaded load(String location) {
        String pattern = location.endsWith("/") ? location + "*.yml" : location;
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new RuleException("规则位置不可用: " + location + " (" + e.getMessage() + ")");
        }
        if (resources.length == 0) {
            log.warn("规则位置下没有找到任何 *.yml: {}（如果规则已经写进数据库，这是正常的）", pattern);
            return new Loaded(Map.of(), List.of());
        }
        List<Resource> ordered = new ArrayList<>(List.of(resources));
        ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

        RuleParser parser = new RuleParser();
        Map<String, RuleSpec> loaded = new LinkedHashMap<>();
        List<SeedDocument> docs = new ArrayList<>(ordered.size());
        for (Resource resource : ordered) {
            String source = resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                RuleSpec spec = parser.parse(new StringReader(yaml), source);
                if (loaded.putIfAbsent(spec.code(), spec) != null) {
                    throw new RuleException("规则 code 重复: " + spec.code() + "（" + source + "）");
                }
                docs.add(new SeedDocument(spec.code(), source, yaml));
            } catch (IOException e) {
                throw new RuleException("读取规则文件失败: " + source + " (" + e.getMessage() + ")");
            }
        }
        return new Loaded(Map.copyOf(loaded), List.copyOf(docs));
    }
}
