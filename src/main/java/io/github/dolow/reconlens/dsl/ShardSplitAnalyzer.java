package io.github.dolow.reconlens.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分片口径同源判定：从 {@code shard.whereLeft / whereRight} 里抽出「切分键表达式」，比较两侧是不是同一个。
 *
 * <p>为什么需要它：{@link RuleParser} 原来的检查是「这段 where 里有没有出现过某个业务键的子串」，
 * 它挡不住真正危险的那一类 —— 两侧各自都引用了键，但引用的是<b>不同的键</b>
 * （{@code MOD(id, 4)} vs {@code MOD(order_no, 4)}），于是同一笔业务被切到不同分片，
 * 结果是一堆凭空多出来的单边缺失差异，而任务状态依旧是 SUCCESS。
 *
 * <p>判定三分（不判二分，是因为「抽不出来」不等于「错」）：
 * <ul>
 *   <li>{@link Verdict#SAME}：两侧归一化后是同一个表达式 → 放行；</li>
 *   <li>{@link Verdict#DIFFERENT}：两侧都抽出来了、但不相等 → 确定会切错；</li>
 *   <li>{@link Verdict#UNKNOWN}：抽不出来 / 谓词有歧义 → <b>只告警，不拒绝</b>。
 *       按业务键做哈希、按区间切分都是合法写法，默认拒绝会误杀一大片。</li>
 * </ul>
 *
 * <p>识别的切分形态（其余一律 UNKNOWN）：
 * <pre>
 *   MOD(&lt;expr&gt;, {shards}) = {index}
 *   CRC32(&lt;expr&gt;) % {shards} = {index}
 *   ORA_HASH(&lt;expr&gt;, {shards}) = {index}
 *   &lt;bucket_col&gt; = {index}          ← 桶列形态，模型表的 biz_key_bucket 写法
 * </pre>
 * 桶列形态不要求同一段 where 里出现 {@code <bucket_col> < {shards}}：实测存在
 * {@code shard_bucket = {index} AND {shards} = {shards}} 这种「为了凑齐两个占位符而写的恒真谓词」，
 * 它不影响抽取 —— 只认携带 {@code {index}} 的那一个谓词。
 *
 * <p>比较的是<b>整个切分表达式</b>（函数 + 键），不只是键：{@code MOD(order_no, {shards})} 与
 * {@code CRC32(order_no) % {shards}} 会给出完全不同的分片，如果只比键就会把这一种漏掉。
 *
 * <p>这是一个纯文本函数：不碰数据库、不碰 Spring、不依赖 {@link RuleSpec}，
 * 因此可以被引擎（解析期告警）和 agent（闸门 1 安全校验）同时复用。
 */
public final class ShardSplitAnalyzer {

    /** 结构化错误码：两侧切分键不同。前端/LLM 自纠正循环按码处理，不解析文案。 */
    public static final String VIOLATION_CODE = "SHARD_SPLIT_KEY";

    /** 三分判定结果。 */
    public enum Verdict {
        SAME, DIFFERENT, UNKNOWN
    }

    /**
     * @param verdict  判定结论
     * @param leftKey  **归一化后的整个切分表达式**（函数 + 键，例如 {@code MOD(ORDER_NO)}、{@code SHARD_BUCKET}）；抽不出来时为 null
     * @param rightKey 同上，右侧
     * @param reason   给人（和 LLM）看的一句话，直接进告警/违规说明
     */
    public record Result(Verdict verdict, String leftKey, String rightKey, String reason) {

        public boolean same() {
            return verdict == Verdict.SAME;
        }

        public boolean different() {
            return verdict == Verdict.DIFFERENT;
        }
    }

    private static final Pattern MOD = Pattern.compile("^MOD\\(\\s*(.*?)\\s*,\\s*\\{shards}\\s*\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRC32 = Pattern.compile("^CRC32\\(\\s*(.*?)\\s*\\)\\s*%\\s*\\{shards\\}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORA_HASH = Pattern.compile("^ORA_HASH\\(\\s*(.*?)\\s*,\\s*\\{shards}\\s*\\)$", Pattern.CASE_INSENSITIVE);
    /** 桶列形态只认「纯列名」，{@code id % 8 = {index}} 这种写死分母的一律不放进来（见设计文档 13.1 E）。 */
    private static final Pattern COLUMN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** 去表别名前缀：{@code t.order_no → order_no}、{@code db.t.id → id}。 */
    private static final Pattern ALIAS_PREFIX = Pattern.compile("(?i)\\b[a-z_][a-z0-9_]*\\.");

    public Result analyze(ShardSpec shard) {
        if (shard == null) {
            return new Result(Verdict.SAME, null, null, "没有 shard 配置：单分片不存在切错");
        }
        return analyze(shard.count(), shard.whereLeft(), shard.whereRight());
    }

    public Result analyze(int shardCount, String whereLeft, String whereRight) {
        if (shardCount <= 1) {
            // 单分片没有切分语义：既然不切，就不存在「切到不同分片」。
            // 注意这条的前提是解析器拒绝 count = 1 + where（RuleParser#rejectSingleShardWhere），
            // 那份依赖在 ShardSplitAnalyzerTest 里被断言住了。
            return new Result(Verdict.SAME, null, null, "shard.count <= 1：不切分就不存在切错");
        }

        Extracted left = extract(whereLeft, "shard.whereLeft");
        Extracted right = extract(whereRight, "shard.whereRight");

        if (left.key == null || right.key == null) {
            String reason = left.key == null ? left.reason : right.reason;
            return new Result(Verdict.UNKNOWN, left.key, right.key, reason);
        }
        if (left.key.equals(right.key)) {
            return new Result(Verdict.SAME, left.key, right.key, "两侧都按 " + left.key + " 切分");
        }
        return new Result(Verdict.DIFFERENT, left.key, right.key,
                "左侧按 " + left.key + " 切分，右侧按 " + right.key + " 切分");
    }

    // ---------- 抽取 ----------

    private record Extracted(String key, String reason) {
    }

    private Extracted extract(String where, String side) {
        if (where == null || where.isBlank()) {
            return new Extracted(null, side + " 为空");
        }

        List<String> carrying = new ArrayList<>();
        for (String predicate : splitTopLevelAnd(where)) {
            if (predicate.contains("{index}")) {
                carrying.add(predicate);
            }
        }
        if (carrying.isEmpty()) {
            return new Extracted(null, side + " 里没有携带 {index} 的谓词");
        }
        if (carrying.size() > 1) {
            // 两个谓词都想决定分片 → 与其猜，不如交给人；猜错的代价是"看起来同源"。
            return new Extracted(null, side + " 里有 " + carrying.size() + " 个谓词同时携带 {index}，无法确定切分键");
        }

        String predicate = carrying.get(0);
        String[] sides = splitEquality(predicate);
        if (sides == null) {
            return new Extracted(null, side + " 的切分谓词不是等值比较: " + predicate);
        }
        boolean leftHasIndex = sides[0].contains("{index}");
        boolean rightHasIndex = sides[1].contains("{index}");
        if (leftHasIndex == rightHasIndex) {
            return new Extracted(null, side + " 无法判断哪一侧是分片序号: " + predicate);
        }

        String expression = (leftHasIndex ? sides[1] : sides[0]).trim();
        return extractKey(expression, side);
    }

    private Extracted extractKey(String expression, String side) {
        String stripped = stripRedundantOuterParens(expression.trim());
        Matcher match = MOD.matcher(stripped);
        if (match.matches()) {
            return new Extracted("MOD(" + normalize(match.group(1)) + ")", null);
        }
        match = ORA_HASH.matcher(stripped);
        if (match.matches()) {
            return new Extracted("ORA_HASH(" + normalize(match.group(1)) + ")", null);
        }
        match = CRC32.matcher(stripped);
        if (match.matches()) {
            return new Extracted("CRC32(" + normalize(match.group(1)) + ")", null);
        }
        String candidate = normalize(stripped);
        if (COLUMN.matcher(candidate).matches()) {
            // 桶列形态：<bucket_col> = {index}
            return new Extracted(candidate, null);
        }
        return new Extracted(null, side + " 的切分表达式不在已知形态内，无法判定: " + expression);
    }

    // ---------- 文本处理 ----------

    /**
     * 按顶层 AND 拆谓词链：括号内与引号内的 AND 不算分隔符，
     * 否则 {@code CONCAT(a, ' AND ', b)} 这种字面量会被拆坏。
     */
    static List<String> splitTopLevelAnd(String where) {
        List<String> parts = new ArrayList<>(4);
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < where.length(); i++) {
            char c = where.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    if (i + 1 < where.length() && where.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quoted = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> quoted = true;
                case '(' -> depth++;
                case ')' -> depth--;
                default -> {
                    if (depth == 0 && isAnd(where, i)) {
                        addIfNotBlank(parts, where.substring(start, i));
                        i += 2;
                        start = i + 1;
                    }
                }
            }
        }
        addIfNotBlank(parts, where.substring(start));
        return parts;
    }

    private static boolean isAnd(String text, int index) {
        if (index + 3 > text.length()) {
            return false;
        }
        if (!text.regionMatches(true, index, "AND", 0, 3)) {
            return false;
        }
        boolean leftBoundary = index == 0 || !isWordChar(text.charAt(index - 1));
        boolean rightBoundary = index + 3 == text.length() || !isWordChar(text.charAt(index + 3));
        return leftBoundary && rightBoundary;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void addIfNotBlank(List<String> parts, String part) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) {
            parts.add(trimmed);
        }
    }

    /** 找顶层的单个 {@code =}（排除 {@code <=} {@code >=} {@code !=} {@code ==}），返回左右两段。 */
    static String[] splitEquality(String predicate) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < predicate.length(); i++) {
            char c = predicate.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    if (i + 1 < predicate.length() && predicate.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quoted = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '=' && depth == 0) {
                char prev = i > 0 ? predicate.charAt(i - 1) : ' ';
                char next = i + 1 < predicate.length() ? predicate.charAt(i + 1) : ' ';
                if (prev != '<' && prev != '>' && prev != '!' && prev != '=' && next != '=') {
                    return new String[]{predicate.substring(0, i), predicate.substring(i + 1)};
                }
            }
        }
        return null;
    }

    /**
     * 归一化：去外层冗余括号 → 去反引号/双引号 → 去表别名前缀 → 折叠空白 → 统一大写。
     * 目的是让 {@code MOD(t.id, {shards})} 与 {@code mod( id ,{shards} )} 判成同一个键。
     *
     * <p>已知代价：字符串字面量里的 {@code .} 也会被当成别名分隔符处理（例如 {@code 'a.b'}），
     * 分片键表达式里几乎不会出现这种字面量，为了一个纯函数保持简单，这里不做词法切分。
     */
    static String normalize(String expression) {
        String text = stripRedundantOuterParens(expression.trim());
        text = text.replace("`", "").replace("\"", "");
        text = ALIAS_PREFIX.matcher(text).replaceAll("");
        return text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** 只剥掉「真的包住整个表达式」的那层括号：{@code (a AND b)} 剥，{@code (a) AND (b)} 不剥。 */
    private static String stripRedundantOuterParens(String expression) {
        String text = expression;
        while (text.length() > 1 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')' && wrapsWhole(text)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static boolean wrapsWhole(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '\'') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }
}
