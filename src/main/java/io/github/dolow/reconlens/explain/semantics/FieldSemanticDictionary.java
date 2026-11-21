package io.github.dolow.reconlens.explain.semantics;

import io.github.dolow.reconlens.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 字段语义字典（K3）：给"这列是什么口径"一个问题一个可查、可复算的答案。
 *
 * <p><b>知识形态</b>：YAML 进仓库（classpath:semantics/field-semantics.yml），与规则/模式库
 * 同款哲学——git 可审、可 diff、改动走 PR。客户可加外部目录
 * （{@code recon.explain.semantics.dir}）追加/覆盖，同 (table, column) 外部赢。
 * 导入连接器时 importer 顺带登记骨架（列存在性），语义由人工补录——
 * 机器登记的是事实，人工登记的是理解，两者来源不同、混不得。
 *
 * <p><b>消费点</b>：
 * <ul>
 *   <li>{@code recon_describe_table} 升级：列 + 语义 + 枚举含义（模型写规则的上下文质量直接提升）；</li>
 *   <li>{@link CaliberConflictChecker}：规则两侧引用的列口径冲突 → WARN（知识变成硬判定）。</li>
 * </ul>
 *
 * <p>加载失败只降级为空字典（describe 照样回结构，只是没有语义层），绝不让坏知识挡主链路。
 */
@Component
public class FieldSemanticDictionary {

    private static final Logger log = LoggerFactory.getLogger(FieldSemanticDictionary.class);

    private static final String CLASSPATH_LOCATION = "semantics/field-semantics.yml";

    private final ReconProperties.Explain.Semantics config;

    /** (table, column) → 语义。小写归一键，查询端归一后取。 */
    private volatile Map<String, FieldSemantics> entries = Map.of();

    public FieldSemanticDictionary(ReconProperties properties) {
        this.config = properties.getExplain().getSemantics();
        reload();
    }

    public boolean enabled() {
        return config.isEnabled();
    }

    public Optional<FieldSemantics> find(String table, String column) {
        if (!config.isEnabled() || table == null || column == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(key(table, column)));
    }

    /** 一张表的全部语义标注（describeTable 展示用）。 */
    public List<FieldSemantics> forTable(String table) {
        if (!config.isEnabled() || table == null) {
            return List.of();
        }
        String prefix = normalize(table) + '.';
        return entries.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    public int size() {
        return entries.size();
    }

    private static String key(String table, String column) {
        return normalize(table) + '.' + normalize(column);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 加载

    private synchronized void reload() {
        Map<String, FieldSemantics> merged = new LinkedHashMap<>();
        loadClasspath(merged);
        loadExternal(merged);
        this.entries = Map.copyOf(merged);
        log.info("字段语义字典就绪：{} 条标注", merged.size());
    }

    @SuppressWarnings("unchecked")
    private void parseInto(Map<String, FieldSemantics> merged, String source, InputStream in, boolean external) {
        Object loaded;
        try {
            loaded = new Yaml().load(in);
        } catch (RuntimeException e) {
            log.warn("语义字典 YAML 解析失败（跳过整份文件）：{} — {}", source, e.toString());
            return;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            log.warn("语义字典 YAML 根节点必须是映射（跳过）：{}", source);
            return;
        }
        Object tables = ((Map<String, Object>) root).get("tables");
        if (!(tables instanceof Map<?, ?> tableMap)) {
            log.warn("语义字典 YAML 缺 tables 段（跳过）：{}", source);
            return;
        }
        for (Map.Entry<?, ?> tableEntry : ((Map<Object, Object>) tableMap).entrySet()) {
            String table = String.valueOf(tableEntry.getKey());
            if (!(tableEntry.getValue() instanceof Map<?, ?> tableBody)) {
                continue;
            }
            Object columns = ((Map<String, Object>) tableBody).get("columns");
            if (!(columns instanceof Map<?, ?> columnMap)) {
                continue;
            }
            for (Map.Entry<?, ?> columnEntry : ((Map<Object, Object>) columnMap).entrySet()) {
                String column = String.valueOf(columnEntry.getKey());
                if (!(columnEntry.getValue() instanceof Map<?, ?> body)) {
                    continue;
                }
                try {
                    FieldSemantics semantics = parseField(table, column, (Map<String, Object>) body);
                    if (!external && merged.containsKey(key(table, column))) {
                        log.warn("内置语义标注重复（后者覆盖）：{}.{}", table, column);
                    }
                    merged.put(key(table, column), semantics);
                } catch (RuntimeException e) {
                    log.warn("语义标注无效（跳过该条）：{}.{} — {}", table, column, e.toString());
                }
            }
        }
    }

    private FieldSemantics parseField(String table, String column, Map<String, Object> body) {
        String semantic = str(body.get("semantic"));
        String caliber = str(body.get("caliber"));
        String unit = str(body.get("unit"));
        Map<String, String> enums = new LinkedHashMap<>();
        if (body.get("enum") instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> enums.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        if (semantic == null && caliber == null && unit == null && enums.isEmpty()) {
            throw new IllegalArgumentException("语义标注至少要有一项（semantic/caliber/unit/enum）");
        }
        return new FieldSemantics(table, column, semantic, caliber, unit, enums);
    }

    private void loadClasspath(Map<String, FieldSemantics> merged) {
        try (InputStream in = new ClassPathResource(CLASSPATH_LOCATION).getInputStream()) {
            parseInto(merged, "classpath:" + CLASSPATH_LOCATION, in, false);
        } catch (IOException e) {
            log.warn("内置语义字典加载失败（语义层降级为空）：{}", e.toString());
        }
    }

    private void loadExternal(Map<String, FieldSemantics> merged) {
        String dir = config.getDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            log.warn("外部语义字典目录不存在：{}（只用内置）", root);
            return;
        }
        try (Stream<Path> files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    parseInto(merged, "file:" + p, in, true);
                } catch (IOException e) {
                    log.warn("外部语义字典文件 {} 加载失败（跳过）：{}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("外部语义字典目录读取失败（只用内置）：{} — {}", root, e.toString());
        }
    }

    private static String str(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
