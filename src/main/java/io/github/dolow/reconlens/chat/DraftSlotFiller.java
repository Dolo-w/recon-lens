package io.github.dolow.reconlens.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 起草时的「这句话在回答哪一问」：把口语、裸列名、顺序错乱的补充，填进还缺的槽位。
 *
 * <p><b>为什么单独一块，不塞进规划器</b>：规划器输出的是"执行哪个动作"，起草参数是
 * "动作内部的状态"。硬塞进 {@code Call} 会让一个执行计划长出七个可选字段，而且这些
 * 参数只在 DEFINE_RULE 这一条路径上有意义。
 *
 * <p><b>模型只提值，不拍板</b>：左右表必须在候选表里，业务键 / 比对字段必须是两侧都存在
 * 的列——校验在 {@link #parse} 里做。守不住这条线，"模型猜错"就会变成一条看起来正常、
 * 实际永远跑不出差异的规则；守住之后，最坏也只是多问一句。
 *
 * <p>没有 key / 模型挂了时返回空，由 {@link DraftIntake#answer} 的确定性兜底接手。
 */
public final class DraftSlotFiller {

    private static final Logger log = LoggerFactory.getLogger(DraftSlotFiller.class);

    private final LlmChatClient client;
    private final ObjectMapper json;

    public DraftSlotFiller(LlmChatClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public boolean configured() {
        return client != null && client.configured();
    }

    /**
     * 问模型这句话填得进哪个空位。
     *
     * @param tables      本次列给用户的候选表（模型只能从这里选）
     * @param leftColumns 左侧表现有列（拿不到就传空，键 / 比对字段这一轮不填）
     * @param rightColumns 右侧表现有列
     */
    public Optional<Map<String, String>> fill(String message, DraftIntake.Hints hints, List<String> tables,
                                              List<String> leftColumns, List<String> rightColumns) {
        if (!configured() || message == null || message.isBlank() || hints == null || hints.complete()) {
            return Optional.empty();
        }
        String raw = client.completeJson(systemPrompt(hints, tables, leftColumns, rightColumns), message.trim())
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<Map<String, String>> filled = parse(raw, hints, tables, leftColumns, rightColumns);
        filled.ifPresent(slots -> log.debug("起草参数由模型补出：{}", slots));
        return filled;
    }

    /** 拆出来单独测：不联网也能把"模型乱给值"的每一条都试一遍。 */
    Optional<Map<String, String>> parse(String raw, DraftIntake.Hints hints, List<String> tables,
                                        List<String> leftColumns, List<String> rightColumns) {
        JsonNode root;
        try {
            root = json.readTree(strip(raw));
        } catch (Exception e) {
            log.debug("模型返回的不是 JSON：{}", clip(raw));
            return Optional.empty();
        }
        JsonNode slots = root.path("slots");
        if (!slots.isObject()) {
            return Optional.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        String left = table(canned(slots.get("left")), tables);
        String right = table(canned(slots.get("right")), tables);
        if (hints.left() == null && left != null) {
            out.put("left", left);
        }
        if (hints.right() == null && right != null) {
            out.put("right", right);
        }
        List<String> columns = common(leftColumns, rightColumns);
        if (!columns.isEmpty()) {
            if (hints.key() == null) {
                List<String> keys = columns(slots.get("key"), columns);
                if (!keys.isEmpty()) {
                    out.put("key", String.join("和", keys));
                }
            }
            if (hints.compare() == null) {
                List<String> compares = columns(slots.get("compare"), columns);
                // DSL 里一条规则只有一个比对字段，多给的只取第一个
                if (!compares.isEmpty()) {
                    String compare = compares.get(0);
                    String key = hints.key() == null ? out.get("key") : hints.key();
                    if (!inKey(compare, key)) {
                        out.put("compare", compare);
                    }
                }
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    private String systemPrompt(DraftIntake.Hints hints, List<String> tables,
                                List<String> leftColumns, List<String> rightColumns) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("你在帮用户把一条对账规则的口述补成参数。只做翻译，不做决定：值必须来自候选，")
                .append("候选里没有就留空。\n");
        sb.append("已知：左侧表=").append(nz(hints.left()))
                .append("；右侧表=").append(nz(hints.right()))
                .append("；业务键=").append(nz(hints.key()))
                .append("；比对字段=").append(nz(hints.compare()));
        if (hints.hint() != null) {
            sb.append("；用户说过：").append(hints.hint());
        }
        sb.append('\n');
        if (!tables.isEmpty()) {
            sb.append("候选表：").append(String.join(" / ", tables)).append('\n');
        }
        if (!leftColumns.isEmpty()) {
            sb.append("左侧表的列：").append(String.join(", ", leftColumns)).append('\n');
        }
        if (!rightColumns.isEmpty()) {
            sb.append("右侧表的列：").append(String.join(", ", rightColumns)).append('\n');
        }
        sb.append("这一句要回答的是：").append(hints.missing().get(0)).append('\n');
        sb.append("输出 JSON：{\"slots\":{\"left\":\"<候选表名>\",\"right\":\"<候选表名>\",")
                .append("\"key\":\"<列名，复合键用和连接>\",\"compare\":\"<列名>\"},\"reason\":\"一句中文\"}\n");
        sb.append("规则：\n")
                .append("1. 只填还缺的，已经给的不许改。\n")
                .append("2. 左右表只能取候选表里的名字。\n")
                .append("3. 业务键和比对字段只能取两侧都有的列。\n")
                .append("4. 用户可能只回一个裸列名，也可能一句话同时给好几样，也可能在纠正上一句。\n")
                .append("5. 一个都填不了就输出 {\"slots\":{},\"reason\":\"...\"}，不要编。\n");
        return sb.toString();
    }

    /**
     * 只接受"两侧都有的列"：业务键对不上会让两侧永远匹配不上，比对字段对不上会直接校验失败。
     */
    private static List<String> columns(JsonNode node, List<String> common) {
        List<String> tokens = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return tokens;
        }
        if (node.isArray()) {
            node.forEach(item -> tokens.add(item.asText("")));
        } else {
            tokens.add(node.asText(""));
        }
        List<String> matched = new ArrayList<>();
        for (String token : tokens) {
            for (String part : token.split("\\s*(?:和|与|、|,|，|\\+|\\s)\\s*")) {
                String column = DraftIntake.matchColumn(part, common);
                if (column == null) {
                    return List.of();   // 有一个认不出就整项不接
                }
                if (!matched.contains(column)) {
                    matched.add(column);
                }
            }
        }
        return matched;
    }

    /** 比对字段落在业务键里 = 按 key 比 key，永远没有差异；模型给了也不接。 */
    private static boolean inKey(String compare, String key) {
        if (compare == null || key == null) {
            return false;
        }
        for (String part : key.split("\\s*(?:和|与|、|,|，|\\+|\\s)\\s*")) {
            if (part.equalsIgnoreCase(compare)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> common(List<String> left, List<String> right) {        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String column : left) {
            if (right.stream().anyMatch(other -> other.equalsIgnoreCase(column))) {
                out.add(column);
            }
        }
        return out;
    }

    private static String table(String name, List<String> tables) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (String table : tables) {
            if (table.equalsIgnoreCase(name.trim())) {
                return table;
            }
        }
        return null;
    }

    private static String canned(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? "（空）" : value;
    }

    private static String strip(String raw) {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
