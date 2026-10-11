package io.github.dolow.reconlens.dsl;

import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.engine.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 YAML 解析成 {@link RuleSpec}，并把所有配置错误在启动时一次性暴露出来。
 * <p>规则是配置不是代码，所以这里的每条校验都尽量给出"改哪里"的提示，
 * 而不是等到跑对账时才报空指针。
 */
public final class RuleParser {

    private static final Logger LOG = LoggerFactory.getLogger(RuleParser.class);

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final Yaml yaml = new Yaml();

    public RuleSpec parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, path.getFileName().toString());
        } catch (IOException e) {
            throw new RuleException("读取规则文件失败: " + path + " (" + e.getMessage() + ")");
        }
    }

    public RuleSpec parse(InputStream in, String source) {
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8), source);
    }

    public RuleSpec parse(Reader reader, String source) {
        Object loaded;
        try {
            loaded = yaml.load(reader);
        } catch (RuntimeException e) {
            throw RuleException.at(source, "YAML 语法错误: " + e.getMessage());
        }
        Map<String, Object> root = asMap(loaded, source, "顶层");
        if (root == null) {
            throw RuleException.at(source, "文件为空");
        }

        String code = requiredString(root, "code", source);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw RuleException.at(source, "code 只能是大写字母、数字与下划线，例如 BILL_VS_SETTLE，当前为 " + code);
        }

        SideSpec left = parseSide(root, "left", source);
        SideSpec right = parseSide(root, "right", source);
        ShardSpec shard = parseShard(root, source);
        List<FieldRule> compare = parseCompare(root, source);
        MissingSpec missing = parseMissing(root, source);
        DuplicateSpec duplicate = parseDuplicate(root, source, missing);

        int maxAttempts = 1;
        Map<String, Object> options = asMap(root.get("options"), source, "options");
        if (options != null) {
            maxAttempts = optionalInt(options, "maxAttempts", source, 1);
            if (maxAttempts < 1) {
                throw RuleException.at(source, "options.maxAttempts 至少为 1");
            }
        }

        validateDiffTypes(source, compare, missing, duplicate);
        warnIfShardMaySplitTheSameKey(source, left, right, shard);

        String name = optionalString(root, "name", code);
        String description = optionalString(root, "description", "");
        return new RuleSpec(code, name, description, left, right, shard, compare, missing, duplicate, maxAttempts);
    }

    /**
     * 分片切分必须让"同一个业务键"落到同一个分片，否则两侧各自的代理主键会把同一笔业务切到不同分片，
     * 结果是凭空多出一堆单边缺失差异。这里只告警不报错 —— 按业务键做哈希/取模是合法写法。
     */
    private void warnIfShardMaySplitTheSameKey(String source, SideSpec left, SideSpec right, ShardSpec shard) {
        if (shard.count() <= 1) {
            return;
        }
        for (String[] side : new String[][]{{"shard.whereLeft", shard.whereLeft()}, {"shard.whereRight", shard.whereRight()}}) {
            String where = side[1];
            if (where == null) {
                continue;
            }
            if (!where.contains(left.keyField()) && !where.contains(right.keyField())) {
                LOG.warn("[{}] {} 没有引用业务键 {} / {}，请确认两侧用的是同一个切分口径（例如按 id 取模会把同一笔业务切到不同分片）: {}",
                        source, side[0], left.keyField(), right.keyField(), where);
            }
        }
    }

    private SideSpec parseSide(Map<String, Object> root, String key, String source) {
        Map<String, Object> side = asMap(root.get(key), source, key);
        if (side == null) {
            throw RuleException.at(source, "缺少 " + key + " 段");
        }
        String table = optionalString(side, "table", null);
        String sql = optionalString(side, "sql", null);
        if ((table == null) == (sql == null)) {
            throw RuleException.at(source, key + " 必须且只能配置 table 或 sql 之一");
        }
        String keyField = requiredString(side, "keyField", source + "." + key);
        FieldType keyType = FieldType.from(optionalString(side, "keyType", "STRING"));

        String orderBy = optionalString(side, "orderBy", keyField);
        if (!orderBy.equalsIgnoreCase(keyField)) {
            throw RuleException.at(source, key + ".orderBy 必须等于 keyField（归并式对账依赖按业务键升序取数），当前 orderBy="
                    + orderBy + ", keyField=" + keyField);
        }
        String label = optionalString(side, "label", key);
        return new SideSpec(label, table, sql, keyField, keyType, orderBy);
    }

    private ShardSpec parseShard(Map<String, Object> root, String source) {
        Map<String, Object> shard = asMap(root.get("shard"), source, "shard");
        if (shard == null) {
            return new ShardSpec(1, 1, null, null);
        }
        int count = optionalInt(shard, "count", source, 1);
        if (count < 1) {
            throw RuleException.at(source, "shard.count 至少为 1");
        }
        int parallelism = optionalInt(shard, "parallelism", source, count);
        if (parallelism < 1) {
            throw RuleException.at(source, "shard.parallelism 至少为 1");
        }
        String whereLeft = optionalString(shard, "whereLeft", null);
        String whereRight = optionalString(shard, "whereRight", null);
        if (count > 1) {
            validateShardWhere(source, "whereLeft", whereLeft);
            validateShardWhere(source, "whereRight", whereRight);
        }
        return new ShardSpec(count, parallelism, whereLeft, whereRight);
    }

    private void validateShardWhere(String source, String key, String where) {
        if (where == null || where.isBlank()) {
            throw RuleException.at(source, "shard.count > 1 时必须配置 shard." + key);
        }
        if (!where.contains("{index}") || !where.contains("{shards}")) {
            throw RuleException.at(source, "shard." + key + " 必须同时包含 {index} 与 {shards} 占位符，当前为 " + where);
        }
    }

    private List<FieldRule> parseCompare(Map<String, Object> root, String source) {
        Object raw = root.get("compare");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw RuleException.at(source, "compare 至少要配置一个比对字段");
        }
        List<FieldRule> rules = new ArrayList<>(list.size());
        int index = 0;
        for (Object item : list) {
            index++;
            String where = "compare[" + index + "]";
            Map<String, Object> map = asMap(item, source, where);
            if (map == null) {
                throw RuleException.at(source, where + " 必须是键值映射");
            }
            String field = requiredString(map, "field", source + "." + where);
            FieldType type = FieldType.from(optionalString(map, "type", "STRING"));
            String diffType = requiredString(map, "diffType", source + "." + where);
            Severity severity = Severity.from(optionalString(map, "severity", "MEDIUM"));
            boolean ignoreCase = optionalBool(map, "ignoreCase", source, false);
            BigDecimal tolerance = optionalDecimal(map, "tolerance", source);
            BigDecimal tolerancePercent = optionalDecimal(map, "tolerancePercent", source);
            if (type != FieldType.DECIMAL && type != FieldType.LONG && tolerance != null) {
                throw RuleException.at(source, where + ".tolerance 只对 DECIMAL/LONG 字段有意义，当前 type=" + type);
            }
            if (type != FieldType.DECIMAL && type != FieldType.LONG && tolerancePercent != null) {
                throw RuleException.at(source, where + ".tolerancePercent 只对 DECIMAL/LONG 字段有意义，当前 type=" + type);
            }
            if (tolerance != null && tolerance.signum() < 0) {
                throw RuleException.at(source, where + ".tolerance 不能为负数");
            }
            if (tolerancePercent != null && tolerancePercent.signum() < 0) {
                throw RuleException.at(source, where + ".tolerancePercent 不能为负数");
            }
            rules.add(new FieldRule(field, type, ignoreCase, tolerance, tolerancePercent, diffType, severity));
        }
        return rules;
    }

    private MissingSpec parseMissing(Map<String, Object> root, String source) {
        Map<String, Object> missing = asMap(root.get("missing"), source, "missing");
        if (missing == null) {
            throw RuleException.at(source, "缺少 missing 段：请显式声明单边缺失用哪个 diffType，避免差异被静默丢弃");
        }
        String leftMissing = requiredString(missing, "leftMissingDiffType", source + ".missing");
        String rightMissing = requiredString(missing, "rightMissingDiffType", source + ".missing");
        Severity severity = Severity.from(optionalString(missing, "severity", "HIGH"));
        return new MissingSpec(leftMissing, rightMissing, severity);
    }

    private DuplicateSpec parseDuplicate(Map<String, Object> root, String source, MissingSpec missing) {
        Map<String, Object> duplicate = asMap(root.get("duplicate"), source, "duplicate");
        if (duplicate == null) {
            // 默认开启重复键检测：同一业务键出现多条本身就是最危险的差异之一
            return new DuplicateSpec("DUPLICATE_KEY", Severity.from("MEDIUM"));
        }
        String diffType = optionalString(duplicate, "diffType", "DUPLICATE_KEY");
        Severity severity = Severity.from(optionalString(duplicate, "severity", "MEDIUM"));
        return new DuplicateSpec(diffType, severity);
    }

    private void validateDiffTypes(String source, List<FieldRule> compare, MissingSpec missing, DuplicateSpec duplicate) {
        Set<String> seen = new HashSet<>();
        for (FieldRule rule : compare) {
            addUnique(seen, rule.diffType(), source, "compare." + rule.field() + ".diffType");
        }
        addUnique(seen, missing.leftMissingDiffType(), source, "missing.leftMissingDiffType");
        addUnique(seen, missing.rightMissingDiffType(), source, "missing.rightMissingDiffType");
        addUnique(seen, duplicate.diffType(), source, "duplicate.diffType");
    }

    private void addUnique(Set<String> seen, String diffType, String source, String where) {
        if (!seen.add(diffType)) {
            throw RuleException.at(source, where + " 与其他配置重复（同一规则内 diffType 必须唯一）: " + diffType);
        }
    }

    // ---------- YAML 取值辅助 ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String source, String where) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw RuleException.at(source, where + " 必须是键值映射");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private String requiredString(Map<String, Object> map, String key, String source) {
        String value = optionalString(map, key, null);
        if (value == null || value.isBlank()) {
            throw RuleException.at(source, "缺少必填项 " + key);
        }
        return value;
    }

    private String optionalString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int optionalInt(Map<String, Object> map, String key, String source, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw RuleException.at(source, key + " 必须是整数，当前为 " + value);
        }
    }

    private boolean optionalBool(Map<String, Object> map, String key, String source, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.parseBoolean(text);
        }
        throw RuleException.at(source, key + " 必须是 true/false，当前为 " + value);
    }

    private BigDecimal optionalDecimal(Map<String, Object> map, String key, String source) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw RuleException.at(source, key + " 必须是数字，当前为 " + value);
        }
    }
}
