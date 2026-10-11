package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动时把规则目录下的 YAML 全部加载进来。规则文件可以放在 classpath（打包内置），
 * 也可以放在 file: 目录（运维改配置、重启即生效，不需要重新构建）。
 */
@Component
public class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    private final Map<String, RuleSpec> rules;

    public RuleRegistry(ReconProperties properties) {
        this.rules = load(properties.getRulesLocation());
        log.info("已加载 {} 条对账规则: {}", rules.size(), rules.keySet());
    }

    public RuleSpec get(String code) {
        RuleSpec spec = rules.get(code);
        if (spec == null) {
            throw new RuleException("未找到规则 " + code + "，已加载的规则: " + rules.keySet());
        }
        return spec;
    }

    public List<RuleSpec> all() {
        return List.copyOf(rules.values());
    }

    public Set<String> codes() {
        return rules.keySet();
    }

    private Map<String, RuleSpec> load(String location) {
        String pattern = location.endsWith("/") ? location + "*.yml" : location;
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new RuleException("规则位置不可用: " + location + " (" + e.getMessage() + ")");
        }
        if (resources.length == 0) {
            throw new RuleException("规则位置下没有找到任何 *.yml: " + pattern);
        }
        List<Resource> ordered = new ArrayList<>(List.of(resources));
        ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

        RuleParser parser = new RuleParser();
        Map<String, RuleSpec> loaded = new LinkedHashMap<>();
        for (Resource resource : ordered) {
            String source = resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                RuleSpec spec = parser.parse(in, source);
                if (loaded.putIfAbsent(spec.code(), spec) != null) {
                    throw new RuleException("规则 code 重复: " + spec.code() + "（" + source + "）");
                }
            } catch (IOException e) {
                throw new RuleException("读取规则文件失败: " + source + " (" + e.getMessage() + ")");
            }
        }
        return Map.copyOf(loaded);
    }
}
