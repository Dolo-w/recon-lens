package io.github.dolow.reconlens.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「口述一条规则」的参数收集：把用户一句句说的话，攒成一份可以交给 {@code recon_define_rule} 的 YAML。
 *
 * <p><b>为什么需要它</b>：起草一条规则至少要四样东西（左侧表、右侧表、业务键、比对字段）。
 * 用户不会一次说全，也不会按字段顺序说。之前每次只回同一段"我需要四样东西"，看着像听懂了，
 * 实际上是复读——因为没有地方存"他已经给了哪几样"。
 *
 * <p><b>为什么只生成"最小骨架"而不是尽力猜满</b>：猜错两侧表的代价不是多一轮对话，
 * 而是用户拿着一份错的规则跑出"结果正常、没有差异"，比不答更糟。所以这里只做两件事：
 * 认出<b>明确说了的</b>，其余一律留空去问。
 *
 * <p>纯函数、不碰 IO：能单测到每一句话的解析结果，而不是靠起个服务问它。
 */
public final class DraftIntake {

    /** 等待用户补参数的动作名，与 {@code ChatIntent.DEFINE_RULE} 同名，便于在槽位里直接读。 */
    public static final String ACTION = "DEFINE_RULE";

    /** 业务键：`按 order_no 连` / `用 a 和 b 关联` / `业务键 order_no` / `按订单号连`。 */
    private static final Pattern KEY = Pattern.compile(
            "(?:按|用|以|靠)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20}"
                    + "(?:\\s*(?:和|与|、)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})*)\\s*"
                    + "(?:连|关联|匹配|对齐|做业务键|作为业务键|当业务键|串起来)");
    private static final Pattern KEY2 = Pattern.compile(
            "(?:业务键|主键|keys?)\\s*[=:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20}"
                    + "(?:\\s*(?:和|与|、)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})*)");

    /**
     * 比对字段：`比 amount` / `比对 amount 和 status` / `比对字段 amount` / `比金额`。
     *
     * <p>末尾那个单独一个「对」是给「用订单和状态对金额」这种说法留的：中文里"对金额"
     * 就是"比金额"，但它不能吃掉「对账 / 对一下 / 对上」——那几个是业务键的引子，不是比对字段。
     */
    private static final Pattern COMPARE = Pattern.compile(
            "(?:比对|比较|比|核对|对(?![账一下上]))\\s*(?:字段)?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20}"
                    + "(?:\\s*(?:和|与|、)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})*)");
    private static final Pattern COMPARE2 = Pattern.compile(
            "(?:比对字段|compare)\\s*[=:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,20}"
                    + "(?:\\s*(?:和|与|、)\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]{1,20})*)");

    /**
     * 中文里的填充词。
     *
     * <p>「就订单和状态对账吧」按"和"切出来是 {@code 就订单 / 状态}——不洗掉"就"，
     * 订单就翻译不成 {@code order_no}，业务键会落成 {@code status}（实测踩过）。
     * 「比金额吧」的"吧"同理。这里只洗词首词尾，不动词中间的字符。
     */
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "^(?:就|那|这|用|按|按照|根据|依据|把|拿|以|跟|和|与|用)+");
    private static final Pattern TRAILING_NOISE = Pattern.compile("(?:的|吧|呢|啊|了|呀|嘛|哦)+$");

    /** 规则编码：全大写标识符；表名不在候选里，避免把表名当编码。 */
    private static final Pattern CODE = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\b");

    /**
     * 「根据订单和状态对账」这类说法里的字段线索。
     *
     * <p>它不是参数本身：用户说的是业务词（订单、状态），规则要的是列名（order_no、status）。
     * 所以在知道是哪张表之前只能先存着，等拿到列名再翻译 —— 这也解释了为什么这里存的是原文。
     */
    private static final Pattern HINT = Pattern.compile(
            "(?:根据|按照|依据|按|用)\\s*([^，,。；;\\n]{2,24}?)\\s*(?:来)?"
                    + "(?:对账|核对|匹配|对上|对一下|对(?![账一下上])\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]"
                    + "|对(?=[吧啊呢呀嘛\\s]*$))");
    private static final Pattern HINT2 = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9_]+\\s*[和与、+]\\s*[\\u4e00-\\u9fa5A-Za-z0-9_]+)\\s*"
                    + "(?:对账|核对|对比|对一下)");

    /**
     * 「翻一下顺序」「左右对调」「反过来」：两边都定了之后，这是一句明确的操作，不是新参数。
     *
     * <p>只认成对出现的词（翻/换/对调… + 顺序/左右/两边…），否则「换个规则」这种正常句子会被误判。
     */
    private static final Pattern SWAP = Pattern.compile(
            "(?:左右|两边|两侧|两张表|两个表|它们|表)\\s*(?:的)?\\s*(?:顺序|位置|方向)?\\s*"
                    + "(?:翻|换|对调|调换|互换|倒|反过来)"
                    + "|(?:翻|换|对调|调换|互换|倒)(?:一下|个|过来|个位置)?\\s*(?:顺序|左右|两边|两侧|位置)"
                    + "|反过来");

    /** 「1. order_no  VARCHAR(32)」里的人话列清单。 */
    private static final Pattern COLUMN = Pattern.compile("(?m)^\\s*\\d+\\.\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern AMOUNTY =
            Pattern.compile("amount|price|fee|cost|balance|total|金额|价钱|费用|余额|总额");

    private DraftIntake() {
    }

    /**
     * 已经收集到的四样（+ 编码）。字段全为 null 表示还没开始。
     *
     * <p>用 String 而不是结构化类型：这些值最后要拼进 YAML，中转成对象只会多一层转换，
     * 而且在槽位里（JSON）也更好存。
     */
    public record Hints(String code, String name, String left, String right, String key, String compare,
                        String hint) {

        public static final Hints EMPTY = new Hints(null, null, null, null, null, null, null);

        static Hints from(Map<String, String> map) {
            if (map == null || map.isEmpty()) {
                return EMPTY;
            }
            return new Hints(map.get("code"), map.get("name"), map.get("left"), map.get("right"),
                    map.get("key"), map.get("compare"), map.get("hint"));
        }

        Map<String, String> toMap() {
            Map<String, String> map = new LinkedHashMap<>();
            put(map, "code", code);
            put(map, "name", name);
            put(map, "left", left);
            put(map, "right", right);
            put(map, "key", key);
            put(map, "compare", compare);
            put(map, "hint", hint);
            return map;
        }

        private static void put(Map<String, String> map, String key, String value) {
            if (value != null && !value.isBlank()) {
                map.put(key, value);
            }
        }

        /** 后者的非空值覆盖前者：用户后面补充的更可信。 */
        Hints merge(Hints other) {
            if (other == null) {
                return this;
            }
            return new Hints(
                    other.code != null ? other.code : code,
                    other.name != null ? other.name : name,
                    other.left != null ? other.left : left,
                    other.right != null ? other.right : right,
                    other.key != null ? other.key : key,
                    other.compare != null ? other.compare : compare,
                    other.hint != null ? other.hint : hint);
        }

        /**
         * 拿到真实列名之后，把「订单和状态」翻译成 order_no / status。
         *
         * <p>返回的是<b>新的一份</b>：解析成功时会把 hint 清掉（已经变成参数了，再留着会在下一轮
         * 覆盖用户后来的更正）。
         *
         * <p>认不出任何一个就原样返回——宁可继续问，也不要按"看起来像"的列名生成规则。
         */
        public Hints resolveFrom(List<String> columns) {
            if (columns == null || columns.isEmpty()) {
                return this;
            }
            String newKey = key;
            String newCompare = compare;
            String newHint = hint;
            if (newHint != null && !newHint.isBlank()) {
                // 带 + 的多半是复合业务键（客户+订单号），不是"键 + 比对字段"
                boolean composite = newHint.contains("+");
                List<String> matched = new ArrayList<>();
                for (String token : newHint.split("\\s*(?:和|与|、|,|，|\\+|\\s)\\s*")) {
                    String column = matchColumn(token, columns);
                    if (column != null && !matched.contains(column)) {
                        matched.add(column);
                    }
                }
                if (!matched.isEmpty()) {
                    newHint = null;
                    if (composite) {
                        if (newKey == null) {
                            newKey = String.join("和", matched);
                        }
                    } else if (matched.size() > 1) {
                        // 「订单和状态对账」说的是**对账维度**（两侧靠什么连），不是"键 + 比对字段"。
                        // 实测里按"第一个当键、第二个当比对"翻是错的：用户接着会说「比金额」，
                        // 而金额才是比对字段。所以多词一律当复合维度，比对字段仍然去问。
                        if (newKey == null) {
                            newKey = String.join("和", matched);
                        }
                    } else if (newKey == null) {
                        newKey = matched.get(0);
                    }
                }
            }
            // 「比金额」这类中文直接说的字段，也在这里翻成列名；翻不出来就清空，让下一轮再问
            newKey = toColumn(newKey, columns);
            newCompare = toColumn(newCompare, columns);
            return new Hints(code, name, left, right, newKey, newCompare, newHint);
        }

        /** 已经是列名就原样；不是就按别名翻；翻不出来返回 null（宁可再问，也不猜）。 */
        private static String toColumn(String value, List<String> columns) {
            if (value == null || value.isBlank()) {
                return null;
            }
            // 复合键（order_no和status）已经是解析结果，别再拿整串去查列名
            if (value.contains("和") || value.contains("+") || value.contains("、")) {
                return value;
            }
            for (String column : columns) {
                if (column.equalsIgnoreCase(value.trim())) {
                    return column;
                }
            }
            return matchColumn(value, columns);
        }

        /**
         * 直接把两张候选表填进来。
         *
         * <p>给「就这两个吧」这类回答用：用户指的是上一句刚列出来的候选，
         * 而不是重新说了两个表名。词表/模型都认不出这种说法（它没有表名），
         * 所以必须由"正在等这张表"的状态来兜。
         */
        public Hints withTables(String first, String second) {
            // 只有一张候选表时不能左右同名：那会生成一条"自己跟自己比、永远没差异"的规则
            if (first == null || second == null || first.equalsIgnoreCase(second)) {
                return this;
            }
            return new Hints(code, name,
                    left != null ? left : first,
                    right != null ? right : second,
                    key, compare, hint);
        }

        /**
         * 把模型（或别处）认出来的槽位并进来：<b>已经有的不动</b>，只补空位。
         *
         * <p>模型没有写权限，它只提值；「用户早就说了的不许被模型改写」这条守在这里，
         * 而不是靠提示词自觉。
         */
        public Hints withSlots(Map<String, String> newSlots) {
            if (newSlots == null || newSlots.isEmpty()) {
                return this;
            }
            return new Hints(
                    code != null ? code : value(newSlots, "code"),
                    name != null ? name : value(newSlots, "name"),
                    left != null ? left : value(newSlots, "left"),
                    right != null ? right : value(newSlots, "right"),
                    key != null ? key : value(newSlots, "key"),
                    compare != null ? compare : value(newSlots, "compare"),
                    hint);
        }

        private static String value(Map<String, String> map, String key) {
            String value = map.get(key);
            return value == null || value.isBlank() ? null : value.trim();
        }

        public boolean complete() {
            return left != null && right != null && key != null && compare != null;
        }

        /**
         * 比对字段落在业务键里：按 key 比 key 永远相等，任务会以「0 差异」成功。
         *
         * <p>比报错更糟：看起来对上了，其实什么都没验。返回一句人话原因，{@code null} 表示没问题。
         */
        public String compareInKey() {
            if (key == null || compare == null) {
                return null;
            }
            for (String part : key.split("\\s*(?:和|与|、|,|，|\\+|\\s)\\s*")) {
                if (part.equalsIgnoreCase(compare)) {
                    return "「" + compare + "」已经算在业务键里了，按它比永远没有差异";
                }
            }
            return null;
        }

        /** 只把比对字段退回去，其余原样保留——下一轮只重问这一样。 */
        public Hints withoutCompare() {
            return new Hints(code, name, left, right, key, null, hint);
        }

        /** 左右对调：「翻一下顺序」之后，回显、列名翻译、YAML 都要按新左右来。 */
        Hints swapped() {
            return new Hints(code, name, right, left, key, compare, hint);
        }

        /** 还缺什么，按"人问的顺序"排：先两张表，再业务键，最后比对字段。 */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (left == null) {
                missing.add("左侧表");
            }
            if (right == null) {
                missing.add("右侧表");
            }
            if (key == null) {
                missing.add("业务键（两侧靠什么连起来）");
            }
            if (compare == null) {
                missing.add("比对字段（比什么）");
            }
            return missing;
        }

        /** 只问下一个缺的：一次问四个等于没问。 */
        public String ask() {
            return switch (missing().get(0)) {
                case "左侧表" -> "左侧用哪张表？";
                case "右侧表" -> "右侧用哪张表？";
                case "业务键（两侧靠什么连起来）" -> "两侧靠哪一列连起来（业务键）？";
                default -> "比哪一个字段？（比如 amount、status）";
            };
        }

        /**
         * 生成最小可跑骨架。故意只写一个 compare 字段：{@code diffType} 在一条规则内必须唯一，
         * 而可用的 DiffKind 只有几个——硬塞两个同类型字段会直接被校验拒掉，
         * 不如先把一条跑通，第二列让用户看过结果再加。
         */
        public String toYaml(String effectiveCode) {
            String type = AMOUNTY.matcher(compare.toLowerCase(Locale.ROOT)).find() ? "DECIMAL" : "STRING";
            String diffType = "DECIMAL".equals(type) ? "AMOUNT_MISMATCH" : "VALUE_MISMATCH";
            String name = this.name == null ? "会话起草 " + left + " vs " + right : this.name;
            StringBuilder yaml = new StringBuilder();
            yaml.append("code: ").append(effectiveCode).append('\n');
            yaml.append("name: ").append(name).append('\n');
            appendSide(yaml, "left", left, key);
            appendSide(yaml, "right", right, key);
            yaml.append("compare:\n");
            yaml.append("  - { field: ").append(compare).append(", type: ").append(type)
                    .append(", diffType: ").append(diffType).append(", severity: HIGH }\n");
            yaml.append("missing:\n");
            yaml.append("  leftMissingDiffType: MISSING_LEFT\n");
            yaml.append("  rightMissingDiffType: MISSING_RIGHT\n");
            return yaml.toString();
        }

        // 单列键用 keyField + orderBy（引擎要求 orderBy 必须等于 keyField）；
        // 复合键只能用 dimension，而 dimension 下不允许 orderBy —— 三者互斥是 DSL 的硬约束。
        /** 还没收齐时的预览：缺的写成 {@code ?}，让人一眼看出还差什么。 */
        public String toYamlPreview() {
            StringBuilder yaml = new StringBuilder();
            yaml.append("code: ").append(code == null ? "?（落库时自动生成）" : code).append('\n');
            yaml.append("name: ").append(name == null ? "会话起草" : name).append('\n');
            appendSidePreview(yaml, "left", left, key);
            appendSidePreview(yaml, "right", right, key);
            yaml.append("compare:\n");
            yaml.append("  - { field: ").append(compare == null ? "?" : compare)
                    .append(", type: ?, diffType: ?, severity: HIGH }\n");
            yaml.append("missing:\n");
            yaml.append("  leftMissingDiffType: MISSING_LEFT\n");
            yaml.append("  rightMissingDiffType: MISSING_RIGHT\n");
            return yaml.toString();
        }

        private static void appendSidePreview(StringBuilder yaml, String side, String table, String key) {
            yaml.append(side).append(":\n");
            yaml.append("  table: ").append(table == null ? "?" : table).append('\n');
            yaml.append("  keyType: STRING\n");
            yaml.append("  keyField: ").append(key == null ? "?" : key).append('\n');
        }

        private static void appendSide(StringBuilder yaml, String side, String table, String key) {
            String[] keys = key.split("\\s*(?:和|、|,|，)\\s*");
            yaml.append(side).append(":\n");
            yaml.append("  table: ").append(table).append('\n');
            yaml.append("  keyType: STRING\n");
            if (keys.length == 1) {
                yaml.append("  keyField: ").append(keys[0]).append('\n');
                yaml.append("  orderBy: ").append(keys[0]).append('\n');
            } else {
                yaml.append("  dimension: [").append(String.join(", ", keys)).append("]\n");
            }
        }
    }

    /**
     * 把这一句话并进之前的收集结果。
     *
     * @param message    用户这一句
     * @param knownTables 可对账的表名（来自 {@code recon_list_tables}），用来认出"说的是哪张表"
     * @param prior      上一轮已经收集到的（可能是 {@link Hints#EMPTY}）
     */
    public static Hints parse(String message, Collection<String> knownTables, Hints prior) {
        Hints base = prior == null ? Hints.EMPTY : prior;
        if (message == null || message.isBlank()) {
            return base;
        }
        String text = message.toLowerCase(Locale.ROOT);
        Hints merged = base.merge(new Hints(
                codeIn(message, knownTables),
                null,
                table(text, knownTables, "左"),
                table(text, knownTables, "右"),
                first(KEY.matcher(text), KEY2.matcher(text)),
                first(COMPARE.matcher(text), COMPARE2.matcher(text)),
                hintIn(text)));
        // 「翻一下顺序」：两边都定了，这是对调，不是新参数。换边后左/右的顺序变了，
        // 下游的 resolveFrom / 回显都得跟着变，否则用户会看到"说了半天没动"。
        if (merged.left() != null && merged.right() != null
                && !merged.left().equals(merged.right()) && SWAP.matcher(text).find()) {
            return merged.swapped();
        }
        return merged;
    }

    /**
     * 「裸值」回答：用户没带动词，只回了一个列名 / 业务词——问完之后的常见回法。
     *
     * <p>模型在线时这件事归模型；没有模型（离线、没 key、测试）时这里必须兜住，
     * 否则系统拿着同一个问题一遍遍复读——实测里「order_no」被原样问回了四次。
     *
     * <p>只接两种价值：业务键（可以是 {@code a 和 b}）与比对字段（只能一个，DSL 里一条 compare
     * 只有一个 field）。一个词认不出就整句不接——不拿半句去填，宁可再问。
     *
     * @param missing {@link Hints#missing()} 的第一项，决定这句话往哪个槽位填
     */
    static Hints answer(String message, Hints prior, String missing, List<String> columns) {
        Hints base = prior == null ? Hints.EMPTY : prior;
        if (message == null || columns == null || columns.isEmpty() || missing == null) {
            return base;
        }
        String text = message.trim();
        // 超过一句短语、或带句读，就不是「回一个值」了：「订单号是什么意思」不能被当成列名
        if (text.isEmpty() || text.length() > 40 || text.matches(".*[，,。；;！!？?\\n].*")) {
            return base;
        }
        if (missing.startsWith("业务键")) {
            if (base.key() != null) {
                return base;
            }
            List<String> matched = new ArrayList<>();
            for (String token : text.split("\\s*(?:和|与|、|,|，|\\+|\\s)\\s*")) {
                String column = matchColumn(token, columns);
                if (column == null) {
                    return base;
                }
                if (!matched.contains(column)) {
                    matched.add(column);
                }
            }
            if (matched.isEmpty()) {
                return base;
            }
            return new Hints(base.code(), base.name(), base.left(), base.right(),
                    String.join("和", matched), base.compare(), base.hint());
        }
        if (missing.startsWith("比对字段")) {
            if (base.compare() != null) {
                return base;
            }
            String column = matchColumn(text, columns);
            return column == null ? base : new Hints(base.code(), base.name(), base.left(), base.right(),
                    base.key(), column, base.hint());
        }
        return base;
    }

    /**
     *
     * <p>给草稿账本当锚点用：用户之后会说「那条按订单和状态对账的」，而规则体里只有
     * {@code order_no}。不把原话存下来，这句话就永远对不上任何一条草稿。
     */
    public static String spokenWords(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String text = message.toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        String hint = hintIn(text);
        if (hint != null) {
            words.add(hint);
        }
        Matcher compare = COMPARE.matcher(text);
        if (compare.find()) {
            String cleaned = clean(compare.group(1));
            if (cleaned != null) {
                words.add(cleaned);
            }
        }
        return words.stream()
                .filter(word -> word.chars().anyMatch(c -> c > 127))   // 只要中文业务词，丢掉 amount/order_no
                .distinct()
                .collect(java.util.stream.Collectors.joining("，"));
    }

    /** 从 {@code recon_describe_table} 的人话输出里取列名。 */    public static List<String> columnsIn(String text) {
        List<String> columns = new ArrayList<>();
        if (text == null) {
            return columns;
        }
        Matcher matcher = COLUMN.matcher(text);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

    /** 「根据订单和状态对账」里的字段线索；没有就返回 null。 */
    static String hintIn(String text) {
        Matcher matcher = HINT.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Matcher pair = HINT2.matcher(text);
        return pair.find() ? pair.group(1).trim() : null;
    }

    /** 洗掉中文填充词：词首"就/那/这/用/按…"、词尾"的/吧/呢…"。 */
    static String clean(String token) {
        if (token == null) {
            return null;
        }
        String text = token.trim();
        text = LEADING_NOISE.matcher(text).replaceFirst("");
        text = TRAILING_NOISE.matcher(text).replaceFirst("");
        return text.isBlank() ? null : text;
    }

    /**
     * 把业务词翻成列名。
     *
     * <p>先精确匹配，再按中文别名找（订单→order、状态→status…），最后退化成包含匹配。
     * 都不中就是 null —— 宁可不填，也不要猜一个不存在的列去生成规则。
     */
    static String matchColumn(String token, List<String> columns) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String needle = clean(token);
        for (String column : columns) {
            if (column.equalsIgnoreCase(needle)) {
                return column;
            }
        }
        String fragment = switch (needle) {
            case "订单", "订单号", "单号" -> "order";
            case "状态" -> "status";
            case "金额", "钱", "款" -> "amount";
            case "客户", "客户号" -> "customer";
            case "时间", "更新时间" -> "time";
            case "主键", "编号", "ID", "id" -> "id";
            default -> needle.toLowerCase(Locale.ROOT);
        };
        for (String column : columns) {
            if (column.toLowerCase(Locale.ROOT).contains(fragment)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 认出这句话里点名的表。
     *
     * <p>优先按"左/右"这两个字定位；没有方位词时按出现顺序：第一张当左、第二张当右。
     * 只认 {@code knownTables} 里的名字——猜一个不存在的表名，用户会拿到一条永远跑不通的规则。
     */
    static String table(String text, Collection<String> knownTables, String side) {
        String explicit = explicitSide(text, knownTables, side);
        if (explicit != null) {
            return explicit;
        }
        // 句子里已经有"左/右"定位过任何一张表时，不再做"第一张当左"的位置推断：
        // 否则「右 bill_right」会把 bill_right 同时塞给左侧，生成一条两边同表的规则。
        boolean anyExplicit = explicitSide(text, knownTables, "左") != null
                || explicitSide(text, knownTables, "右") != null;
        if (anyExplicit) {
            return null;
        }
        List<String> ordered = orderedTables(text, knownTables);
        if (ordered.isEmpty()) {
            return null;
        }
        // 没有方位词：第一张给左、第二张给右，多的不管（多出来多半是句中提到，不是两侧）
        return "左".equals(side) ? ordered.get(0) : (ordered.size() > 1 ? ordered.get(1) : null);
    }

    private static String explicitSide(String text, Collection<String> knownTables, String side) {
        for (String table : knownTables) {
            String lower = table.toLowerCase(Locale.ROOT);
            int at = text.indexOf(lower);
            while (at >= 0) {
                String before = text.substring(Math.max(0, at - 4), at);
                if (before.contains(side)) {
                    return table;
                }
                at = text.indexOf(lower, at + 1);
            }
        }
        return null;
    }

    private static List<String> orderedTables(String text, Collection<String> knownTables) {
        List<Map.Entry<Integer, String>> hits = new ArrayList<>();
        for (String table : knownTables) {
            int at = text.indexOf(table.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                hits.add(Map.entry(at, table));
            }
        }
        hits.sort(Map.Entry.comparingByKey());
        List<String> names = new ArrayList<>();
        for (Map.Entry<Integer, String> hit : hits) {
            if (!names.contains(hit.getValue())) {
                names.add(hit.getValue());
            }
        }
        return names;
    }

    /** 规则编码：句子里的大写标识符，且不是表名。 */
    static String codeIn(String message, Collection<String> knownTables) {
        Matcher matcher = CODE.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            boolean isTable = knownTables.stream().anyMatch(t -> t.equalsIgnoreCase(candidate));
            if (!isTable) {
                return candidate;
            }
        }
        return null;
    }

    private static String first(Matcher primary, Matcher secondary) {
        String value = group(primary);
        return value != null ? value : group(secondary);
    }

    private static String group(Matcher matcher) {
        if (matcher.find()) {
            String raw = matcher.group(1).trim();
            return raw.isEmpty() ? null : raw.replaceAll("\\s+", " ");
        }
        return null;
    }
}
