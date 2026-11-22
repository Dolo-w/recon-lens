package io.github.dolow.reconlens.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 一条<b>业界知识</b>：先验的、跨客户的行业经验（常见病因 / 厂商行为 / 口径惯例），
 * 与 {@link DiffKnowledgeEntry}（本系统自己跑出来的归因经验）互补。
 *
 * <p>形态是「适用条件 + 知识文本」：{@link AppliesWhen} 只在<b>代码层</b>参与匹配
 * （决定注不注入），模型永远只看到 {@code text}——模型消费知识，不查询知识。
 *
 * <p>信任走梯度不走闸门：AI 蒸馏的条目按来源初始化置信度，注入计数 +
 * 人工核销反馈自动涨落，跌破 {@code graveyardAt} 自动停注入——错误知识由闭环信号淘汰，
 * 不需要人工逐条核证。
 */
public record IndustryKnowledge(
        Long id,
        String entryKey,
        AppliesWhen appliesWhen,
        String text,
        String source,
        Origin origin,
        double confidence,
        Status status,
        int usedCount,
        int rejectedCount,
        String updatedAt) {

    /** 来源通道，决定初始置信度。 */
    public enum Origin {
        /** 人工精读源码/文档后手写的种子条目（YAML）。 */
        SEED(0.7),
        /** AI 蒸馏管道自动生成。 */
        HARVEST(0.4),
        /** 对话起草、人工确认过。 */
        CHAT(0.7);

        private final double initialConfidence;

        Origin(double initialConfidence) {
            this.initialConfidence = initialConfidence;
        }

        public double initialConfidence() {
            return initialConfidence;
        }
    }

    /** ACTIVE 正常注入；GRAVEYARD 置信度跌破阈值，停注入但保留可查。 */
    public enum Status { ACTIVE, GRAVEYARD }

    /**
     * 适用条件——全部可选，空 = 不限制。匹配语义是「全部声明的条件都命中」；
     * 条件里「不知道」的维度不参与判定（不知道 ≠ 不匹配）。
     *
     * @param ruleCodes 适用的规则编码（小写归一后比较）
     * @param diffTypes 适用的差异类型（小写归一后比较）
     * @param fieldRegex 字段名正则（整串匹配）
     * @param directions 适用的方向（+ / - / 0，来自 CauseKey.directionOf）
     */
    public record AppliesWhen(List<String> ruleCodes, List<String> diffTypes,
                              String fieldRegex, List<String> directions) {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        public AppliesWhen {
            ruleCodes = norm(ruleCodes);
            diffTypes = norm(diffTypes);
            directions = norm(directions);
            fieldRegex = fieldRegex == null || fieldRegex.isBlank() ? null : fieldRegex;
        }

        private static List<String> norm(List<String> in) {
            if (in == null || in.isEmpty()) {
                return List.of();
            }
            return in.stream().filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toLowerCase()).distinct().toList();
        }

        /** 结构合法性：fieldRegex 给了就必须能编译。 */
        public boolean structurallyValid() {
            if (fieldRegex == null) {
                return true;
            }
            try {
                Pattern.compile(fieldRegex);
                return true;
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        /** 机器准入第二闸：完全无条件（全空）的条目会对每条差异生效，等于常驻加税，构造即拒绝。 */
        public boolean hasAnyCondition() {
            return !ruleCodes.isEmpty() || !diffTypes.isEmpty()
                    || fieldRegex != null || !directions.isEmpty();
        }

        public boolean matches(String ruleCode, String diffType, String field, String direction) {
            if (!ruleCodes.isEmpty() && (ruleCode == null || !ruleCodes.contains(ruleCode.toLowerCase()))) {
                return false;
            }
            if (!diffTypes.isEmpty() && (diffType == null || !diffTypes.contains(diffType.toLowerCase()))) {
                return false;
            }
            if (!directions.isEmpty() && (direction == null || !directions.contains(direction.toLowerCase()))) {
                return false;
            }
            if (fieldRegex != null) {
                if (field == null) {
                    return false;
                }
                if (!field.matches(fieldRegex)) {
                    return false;
                }
            }
            return true;
        }

        public String toJson() {
            try {
                return MAPPER.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("appliesWhen 序列化失败", e);
            }
        }

        public static AppliesWhen fromJson(String json) {
            try {
                return MAPPER.readValue(json, AppliesWhen.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("appliesWhen 解析失败: " + e.getMessage(), e);
            }
        }
    }

    /** 机器准入总闸：结构合法 + 有条件 + 文本与来源非空。失败返回原因，通过返回 null。 */
    public String admissionRejection() {
        if (entryKey == null || entryKey.isBlank()) {
            return "entryKey 必填";
        }
        if (text == null || text.isBlank() || text.length() > 500) {
            return "text 必填且不超过 500 字（知识是短句不是文章）";
        }
        if (source == null || source.isBlank()) {
            return "source 必填（知识必须可溯源）";
        }
        if (appliesWhen == null || !appliesWhen.structurallyValid()) {
            return "appliesWhen 缺失或 fieldRegex 非法";
        }
        if (!appliesWhen.hasAnyCondition()) {
            return "appliesWhen 全空：无条件条目会对每条差异注入，先想清楚适用范围";
        }
        return null;
    }

    public IndustryKnowledge withConfidence(double next) {
        return new IndustryKnowledge(id, entryKey, appliesWhen, text, source, origin,
                next, status, usedCount, rejectedCount, updatedAt);
    }
}
