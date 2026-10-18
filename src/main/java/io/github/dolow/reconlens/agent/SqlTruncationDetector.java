package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别 {@code sql:} 里<b>上游自带截断</b>（{@code LIMIT} / {@code FETCH FIRST|NEXT} / {@code TOP n}）。
 *
 * <h2>它为什么必须存在</h2>
 *
 * <p>试算物化的写法是 {@code SELECT * FROM (原SQL) src WHERE biz_key BETWEEN ...}。原 SQL 自带
 * {@code LIMIT 1000} 时，外层区间先作用在<b>已经被截断的 1000 行</b>上 —— 物化越忠实，样本越残缺，
 * 而试算会"通过"。这比物化失败危险得多：<b>失败会降级，残缺不会报错</b>。
 * 生产跑的是全量，试算看的是截断后的一角，两者结论自然对不上，而报告里没有任何一行提示这件事。
 *
 * <h2>它只识别，不拒绝</h2>
 *
 * <p>{@code sql: ... LIMIT 1000} 本身是<b>合法且有意义的写法</b>（例如只对最新一批单据对账）。
 * 所以这里的结果不是 {@code SafetyViolation}，而是交给 {@link TrialVerdictResolver} 的一个降级理由
 * （{@code UPSTREAM_LIMIT} → {@code coverage = SAMPLE_PARTIAL}）：
 * <b>规则照跑，但"验到了什么程度"必须写清楚</b>，让人签字时知道自己签的是什么。
 *
 * <h2>{@code GROUP BY} 不在此列</h2>
 *
 * <p>聚合维度本来就靠 {@code GROUP BY}，把它一并当截断会把所有 {@code measures} 规则打成降级 ——
 * 那不是"多标记一条"，是让这一类规则永远拿不到 {@code FULL}。
 * 真正该拦的是"{@code sql:} 自带聚合、规则又声明 {@code measures}"的<b>双重聚合语义冲突</b>，
 * 那是另一条独立校验，不要塞进这里。
 */
public final class SqlTruncationDetector {

    /**
     * {@code (?<![\w`])} / {@code (?![\w`])} 而不是 {@code \b}：反引号是标识符引用，
     * {@code `limit`} 是一个**叫 limit 的列**，不是截断。{@code \b} 会把反引号当边界，于是一个列名
     * 就能让整条规则被判成"样本残缺"。
     */
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)(?<![\\w`])LIMIT(?![\\w`])");

    /** SQL Server / 标准 SQL 的 {@code OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY}。 */
    private static final Pattern FETCH_CLAUSE = Pattern.compile("(?i)(?<![\\w`])FETCH\\s+(FIRST|NEXT)(?![\\w`])");

    /** {@code TOP 100} / {@code TOP(100)}；要求后面跟数字，避免把名为 top 的列当成截断。 */
    private static final Pattern TOP_CLAUSE = Pattern.compile("(?i)(?<![\\w`])TOP\\s*\\(?\\s*\\d+");

    private SqlTruncationDetector() {
    }

    /**
     * @return 命中的截断语法（如 {@code ["LIMIT"]}），<b>空列表表示没有截断风险</b>
     */
    public static List<String> detect(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        // 先剥注释与字面量：WHERE note = 'LIMIT 100' 与 -- LIMIT 1000 都不是截断
        String stripped = SqlText.stripCommentsAndLiterals(sql);
        Set<String> hits = new LinkedHashSet<>();
        collect(stripped, LIMIT_CLAUSE, "LIMIT", hits);
        collect(stripped, FETCH_CLAUSE, "FETCH", hits);
        collect(stripped, TOP_CLAUSE, "TOP", hits);
        return List.copyOf(hits);
    }

    /**
     * 实测明细，形如 {@code ["left: LIMIT", "right: TOP"]}，直接写进 {@code trial_report}。
     *
     * <p>带侧名是必须的：只有一侧被截断时，差异会表现为"单边缺失"，而审核人第一眼会怀疑数据；
     * 报告里有这一行，才能让他先怀疑样本。
     */
    public static List<String> describe(RuleSpec rule) {
        List<String> out = new ArrayList<>();
        if (rule == null) {
            return out;
        }
        collectSide("left", rule.left(), out);
        collectSide("right", rule.right(), out);
        return out;
    }

    public static boolean truncated(RuleSpec rule) {
        return !describe(rule).isEmpty();
    }

    private static void collectSide(String label, SideSpec side, List<String> out) {
        // 只有 sql: 才可能有自己的 LIMIT；table: 形态的 where 是条件片段，不含分句
        if (side == null || !side.customSql()) {
            return;
        }
        List<String> hits = detect(side.sql());
        if (!hits.isEmpty()) {
            out.add(label + ": " + String.join("+", hits));
        }
    }

    private static void collect(String text, Pattern pattern, String label, Set<String> hits) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            hits.add(label);
        }
    }
}
