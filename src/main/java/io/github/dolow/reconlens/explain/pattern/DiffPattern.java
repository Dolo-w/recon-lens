package io.github.dolow.reconlens.explain.pattern;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.explain.CauseKey;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一条经验模式（K2 模式库的最小单元）：机械特征匹配 → 一句可复算的候选假设。
 *
 * <p><b>机器可复算优先</b>（v4 设计原则 1）：匹配条件全部是差异记录上已有的机械特征
 * （delta 比率区间 / 方向 / 值类型 / 差异类型 / 字段名形状），没有一项需要"AI 感觉像"。
 * 同一个模式在任何环境、任何时间对同一条差异给出同一个判定——这是它与 LLM 联想的本质区别。
 *
 * <p><b>fail 保守</b>（v4 设计原则 3）：无条件匹配的模式是全命中炸弹，构造时直接拒绝；
 * {@code deltaRatio} 为空的差异不参与比率类条件。
 *
 * @param id         模式 ID（大写下划线，K1 处置台账的 reasonId 用同一套命名）
 * @param family     大类：金额量级 / 存在性 / 时间 / 状态语义
 * @param name       人话名
 * @param conditions 条件组，<b>任一</b>条件命中即匹配（OR）——
 *                   "±100 倍"这类双方向病，用两个条件而不是两个模式
 * @param hypothesis 候选假设（一句话病因）
 * @param probeHint  下一步核对什么
 */
public record DiffPattern(String id, String family, String name,
                          List<Condition> conditions, String hypothesis, String probeHint) {

    public DiffPattern {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("模式 id 不能为空");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("模式 " + id + " 没有任何匹配条件：无条件 = 全命中，禁止");
        }
        conditions = List.copyOf(conditions);
    }

    /** 任一条件命中即匹配（OR）。 */
    public boolean matches(DiffRecord diff) {
        for (Condition c : conditions) {
            if (c.matches(diff)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个匹配条件：一张"只描述形状"的特征卡，字段全部可选——
     * 没写的维度不参与判定。多个字段同时出现时是 AND。
     */
    public record Condition(
            BigDecimal ratioMin,        // deltaRatio ∈ [min, max]（带符号，闭区间；null = 不限）
            BigDecimal ratioMax,
            BigDecimal absDeltaValueMin, // |deltaValue| ≥ 该值（时间差这类"数值落在已知区间"的病）
            BigDecimal absDeltaValueMax, // |deltaValue| ≤ 该值（抹零/分币差这类金额极小的病）
            List<String> diffTypeIn,     // diffType 精确白名单
            Pattern diffTypeRegex,       // diffType 形状（如 "_PENDING$"）
            Pattern fieldRegex,          // 字段名形状（compiled find()）
            List<String> directionIn,    // L_ONLY / R_ONLY / BOTH / NONE
            String leftType,             // N / T / D / E（单字符，null = 不限）
            String rightType) {

        public boolean matches(DiffRecord diff) {
            if (ratioMin != null || ratioMax != null) {
                if (diff.deltaRatio() == null) {
                    return false;
                }
                BigDecimal ratio = diff.deltaRatio();
                if (ratioMin != null && ratio.compareTo(ratioMin) < 0) {
                    return false;
                }
                if (ratioMax != null && ratio.compareTo(ratioMax) > 0) {
                    return false;
                }
            }
            if (absDeltaValueMin != null) {
                if (diff.deltaValue() == null || diff.deltaValue().abs().compareTo(absDeltaValueMin) < 0) {
                    return false;
                }
            }
            if (absDeltaValueMax != null) {
                if (diff.deltaValue() == null || diff.deltaValue().abs().compareTo(absDeltaValueMax) > 0) {
                    return false;
                }
            }
            if (diffTypeIn != null && !diffTypeIn.contains(diff.diffType())) {
                return false;
            }
            if (diffTypeRegex != null && !diffTypeRegex.matcher(diff.diffType()).find()) {
                return false;
            }
            if (fieldRegex != null && (diff.field() == null || !fieldRegex.matcher(diff.field()).find())) {
                return false;
            }
            if (directionIn != null && !directionIn.contains(CauseKey.directionOf(diff))) {
                return false;
            }
            if (leftType != null && !leftType.equals(CauseKey.kindOf(diff.leftValue()))) {
                return false;
            }
            if (rightType != null && !rightType.equals(CauseKey.kindOf(diff.rightValue()))) {
                return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // YAML 解析：match 可以是单个条件 map，也可以是条件 map 列表（OR）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static DiffPattern fromMap(String source, Map<String, Object> raw) {
        String id = str(raw.get("id"));
        if (id == null) {
            throw new IllegalArgumentException(source + " 里有一条模式缺 id");
        }
        String family = str(raw.get("family"));
        String name = str(raw.get("name"));
        String hypothesis = str(raw.get("hypothesis"));
        if (hypothesis == null) {
            throw new IllegalArgumentException("模式 " + id + " 缺 hypothesis：没有假设的模式没有存在意义");
        }
        String probeHint = str(raw.get("probeHint"));

        Object match = raw.get("match");
        if (match == null) {
            throw new IllegalArgumentException("模式 " + id + " 缺 match：无条件 = 全命中，禁止");
        }
        List<Condition> conditions = new ArrayList<>();
        if (match instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof java.util.Map<?, ?>)) {
                    throw new IllegalArgumentException("模式 " + id + " 的 match 列表项不是映射");
                }
                conditions.add(parseCondition(id, (Map<String, Object>) item));
            }
        } else if (match instanceof java.util.Map<?, ?>) {
            conditions.add(parseCondition(id, (Map<String, Object>) match));
        } else {
            throw new IllegalArgumentException("模式 " + id + " 的 match 必须是映射或映射列表");
        }
        return new DiffPattern(id.toUpperCase(Locale.ROOT), str(family), name, conditions, hypothesis, probeHint);
    }

    private static Condition parseCondition(String patternId, Map<String, Object> map) {
        BigDecimal ratioMin = number(map.get("deltaRatioMin"));
        BigDecimal ratioMax = number(map.get("deltaRatioMax"));
        // deltaRatioIn: [min, max] 便捷写法
        Object ratioIn = map.get("deltaRatioIn");
        if (ratioIn instanceof List<?> pair) {
            if (pair.size() != 2) {
                throw new IllegalArgumentException("模式 " + patternId + " 的 deltaRatioIn 必须是 [min, max]");
            }
            ratioMin = number(pair.get(0));
            ratioMax = number(pair.get(1));
        }
        BigDecimal absDeltaValueMin = number(map.get("absDeltaValueMin"));
        BigDecimal absDeltaValueMax = number(map.get("absDeltaValueMax"));

        List<String> diffTypeIn = strList(map.get("diffTypeIn"));
        Pattern diffTypeRegex = map.get("diffTypeRegex") == null ? null
                : Pattern.compile(String.valueOf(map.get("diffTypeRegex")));
        Pattern fieldRegex = map.get("fieldRegex") == null ? null
                : Pattern.compile(String.valueOf(map.get("fieldRegex")));
        List<String> directionIn = strList(map.get("directionIn"));

        String leftType = singleType(map.get("leftType"), patternId);
        String rightType = singleType(map.get("rightType"), patternId);

        // valueTypes: "N/N" 便捷写法（左/右）
        Object valueTypes = map.get("valueTypes");
        if (valueTypes instanceof String vt && vt.contains("/")) {
            String[] parts = vt.split("/");
            leftType = singleType(parts[0], patternId);
            rightType = singleType(parts[1], patternId);
        }
        return new Condition(ratioMin, ratioMax, absDeltaValueMin, absDeltaValueMax, diffTypeIn,
                diffTypeRegex, fieldRegex, directionIn, leftType, rightType);
    }

    private static String singleType(Object raw, String patternId) {
        String value = str(raw);
        if (value == null) {
            return null;
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[NTDE]")) {
            throw new IllegalArgumentException("模式 " + patternId + " 的值类型必须是 N/T/D/E，收到: " + value);
        }
        return value;
    }

    private static List<String> strList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String value = str(item);
            if (value != null) {
                out.add(value);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static BigDecimal number(Object raw) {
        if (raw == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(raw).trim());
    }

    private static String str(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
