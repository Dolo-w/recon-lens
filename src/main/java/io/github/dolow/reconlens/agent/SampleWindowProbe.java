package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 取窗：按业务键区间给两侧各切一段，再取<b>并集</b>。
 *
 * <p>三个刻意的选择，每个都对应一次踩过的坑：
 * <ol>
 *   <li><b>按业务键区间，不按时间、不按 offset。</b>两侧的时间列语义可能不同（A 表按创建时间、B 表按入账时间），
 *       而 offset 分页会踩深分页，且不同分片的 offset 语义根本不一致。</li>
 *   <li><b>一次探测拿全窗信息</b>（{@code MIN/MAX/COUNT} 套在带 {@code LIMIT} 的子查询上），不是 MIN 一遍 + MAX 一遍。
 *       对 {@code sql:} 规则，每多探一次就是把它那条 SQL 再跑一遍 —— 成本翻倍。</li>
 *   <li><b>并集，不取交集。</b>单边缺失最常发生在两侧键范围的<b>尾部</b>（左表有一批单据、右表还没入账）。
 *       取交集等于宣布"两侧都有的区间才验"，而那些"只在左 / 只在右"的键恰恰是 {@code MISSING_*} 的来源 ——
 *       用交集做同窗采样，T3/T4 的输入永远是自证的。</li>
 * </ol>
 */
public class SampleWindowProbe {

    private final JdbcTemplate jdbc;

    public SampleWindowProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一侧的探测结果。
     *
     * @param rows      该侧前 {@code windowRows} 个键的行数
     * @param collation 键列的排序规则；取不到时为 {@code null}（非字符串键 / 派生维度 / 库不支持）
     */
    public record Probe(String lo, String hi, int rows, String collation) {

        public boolean empty() {
            return rows == 0 || lo == null;
        }
    }

    /**
     * 两侧合并后的窗口。{@code lo = min(lo左, lo右)}、{@code hi = max(hi左, hi右)}。
     *
     * @param windowRows 本轮实际用的每侧行数上界（缩窗重试时会变小，必须记下来）
     */
    public record Window(String lo, String hi, int windowRows, Probe left, Probe right) {

        public boolean empty() {
            return left.empty() && right.empty();
        }
    }

    /**
     * @param windowRows 每侧上界
     * @throws SampleBlockedException 探测本身跑不通（SQL 错、权限缺）—— 由调用方翻译成 {@code FIELDS_ONLY}
     */
    public Window probe(SideSpec left, SideSpec right, int windowRows) {
        Probe leftProbe = probeOne(left, windowRows);
        Probe rightProbe = probeOne(right, windowRows);
        if (leftProbe.empty() && rightProbe.empty()) {
            // 两侧都没数据：给出一个空窗口，让 T5-a 去报"样本为空"。
            // 这里不抛异常 —— 空样本是一个需要被记录的结论，不是采样失败。
            return new Window(null, null, windowRows, leftProbe, rightProbe);
        }
        String lo = min(leftProbe.lo(), rightProbe.lo());
        String hi = max(leftProbe.hi(), rightProbe.hi());
        return new Window(lo, hi, windowRows, leftProbe, rightProbe);
    }

    /**
     * 两侧键列的排序规则不一致时必须停手：同一段字面量区间在 {@code general_ci} 与 {@code bin} 下筛出的键集合不同，
     * 于是"同窗"这件事本身不成立，后面三步全白做。
     *
     * <p>M2 的处理是<b>直接降级</b>（{@code FIELDS_ONLY} + {@code COLLATION_MISMATCH}），不做半连接 ——
     * 半连接要跨表 JOIN 且还要在两库间保持方言一致，属于"写了但没被测过"的那类代码。
     * 宁可少验一点并说清楚，也不要给一份看起来验过、实则两侧样本不对称的判定。
     */
    public void requireSameCollation(Window window) {
        String left = window.left().collation();
        String right = window.right().collation();
        if (left != null && right != null && !left.equalsIgnoreCase(right)) {
            throw new SampleBlockedException(
                    io.github.dolow.reconlens.domain.CoverageReason.COLLATION_MISMATCH,
                    "两侧业务键的排序规则不一致（左 " + left + " / 右 " + right
                            + "）：同一段 BETWEEN 会筛出不同的键集合，同窗采样不成立");
        }
    }

    private Probe probeOne(SideSpec side, int windowRows) {
        String key = side.keyExpression();
        String inner = "SELECT " + key + " AS k FROM (" + side.sourceSelect() + ") src";
        if (side.where() != null && !side.where().isBlank()) {
            inner = inner + " WHERE (" + side.where() + ")";
        }
        String sql = "SELECT MIN(k) AS lo, MAX(k) AS hi, COUNT(*) AS n FROM ("
                + inner + " ORDER BY " + key + " LIMIT " + windowRows + ") w";
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        if (rows.isEmpty()) {
            return new Probe(null, null, 0, collationOf(side));
        }
        Map<String, Object> row = rows.get(0);
        Object lo = row.get("lo");
        Object hi = row.get("hi");
        int count = ((Number) row.get("n")).intValue();
        if (lo == null || count == 0) {
            return new Probe(null, null, 0, collationOf(side));
        }
        return new Probe(String.valueOf(lo), String.valueOf(hi), count, collationOf(side));
    }

    /**
     * 键列的排序规则。只有"非派生 + 字符串键"才有意义：派生维度是表达式，没有单一列的 collation；
     * 数值键不受 collation 影响。查不到（H2 的部分版本没有这一列）时返回 {@code null} —— 表示"无法判定"，
     * 而不是"一致"，但也不能因此阻塞整个试算。
     */
    private String collationOf(SideSpec side) {
        if (side.derivedKey() || side.customSql() || side.keyType() != FieldType.STRING) {
            return null;
        }
        try {
            List<String> found = jdbc.queryForList(
                    "SELECT collation_name FROM information_schema.columns"
                            + " WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)",
                    String.class, side.table(), side.keyField());
            return found.isEmpty() ? null : found.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String min(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    static String max(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }
}
