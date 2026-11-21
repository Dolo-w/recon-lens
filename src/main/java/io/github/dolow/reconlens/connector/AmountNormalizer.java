package io.github.dolow.reconlens.connector;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器层第一块基石：canonical 金额归一。
 *
 * <p>真实世界的账单导出里，金额列长什么样都有：{@code ¥1,234.50}、{@code $1,234.50}、
 * 会计负数 {@code (1,234.50)}、欧式 {@code 1.234.567,89}、法式空格 {@code 1 234,56}、
 * 带币种后缀 {@code 12.3 USD}。按字符串导入它们会掉进 VARCHAR，对账引擎根本没法比金额——
 * 这正是"连接器优先"要解决的第一件事：把脏金额归一成 DECIMAL，币种提示单独摘出来，
 * 原始值由调用方留档旁列（{@code __raw_*}），差异复盘永远能回看原文。
 *
 * <p>分隔符判定规则（每条都有测试钉住）：
 * <ul>
 *   <li>逗号和点同时出现：靠右的那个是小数点，另一个必须是严格的 3 位分组 —— 自描述，无歧义；</li>
 *   <li>只有一种分隔符且出现 ≥2 次：是分组符（{@code 1,234,567} / {@code 1.234.567}）；</li>
 *   <li>只有一种分隔符、后跟 1~2 位数字：是小数点（{@code 12,5} = 12.5）—— 两种约定下无歧义；</li>
 *   <li><b>唯一歧义</b>：单分隔符 + 后跟恰好 3 位数字（{@code 1,234} / {@code 1.234}）。
 *       用列级证据裁决：同一列里出现过"欧式铁证"（{@code 1.234,56} 这种点分组+逗号小数），
 *       则点=分组、逗号=小数；否则按美式判（逗号=分组、点=小数）。裁决是确定性的，
 *       列级证据由调用方从采样里算好传入，不靠猜。</li>
 * </ul>
 *
 * <p>fail-loud：解析不了的值返回 {@code null}，调用方（导入管道）带着行号列号抛错，
 * 绝不静默塞 null——金额列的静默 NULL 就是假差异的温床。
 */
public final class AmountNormalizer {

    private AmountNormalizer() {
    }

    /** 归一结果：数值 + 币种提示（{@code null} = 原值没带币种信息）。 */
    public record ParsedAmount(BigDecimal value, String currency) {
    }

    /** 货币符号：出现在金额前后都认（含全角 ¥）。 */
    private static final String SYMBOLS = "¥￥$€£₩";

    /** 常见 ISO 货币码：作为独立的字母 token 出现在金额前后时摘出来。 */
    private static final Set<String> ISO_CODES = Set.of(
            "CNY", "RMB", "USD", "EUR", "GBP", "JPY", "HKD", "KRW", "SGD", "AUD", "CAD", "CHF", "INR", "NZD", "TWD");

    /** 法式/国际导出用的空格分组符：普通空格、不换行空格、窄不换行空格。 */
    private static final Pattern SPACE_GROUP = Pattern.compile("[\\s\\u00A0\\u202F]");

    private static final Pattern ISO_LEAD = Pattern.compile("^([A-Za-z]{3})\\s*");
    private static final Pattern ISO_TAIL = Pattern.compile("\\s*([A-Za-z]{3})$");

    /** DECIMAL(24,6) 的边界：总位超出的说明这不是金额列。 */
    private static final int MAX_TOTAL_DIGITS = 18;
    private static final int MAX_SCALE = 6;

    private static final String NUMBER_PATTERN = "\\d+(?:\\.\\d{1,6})?";

    /**
     * 归一一个金额值。默认美式歧义裁决（单分隔符 + 3 位尾数：逗号=分组、点=小数）。
     *
     * @return 归一结果；原值不是可解析的金额（含空串、纯字母、格式坏值）返回 {@code null}
     */
    public static ParsedAmount parse(String raw) {
        return parse(raw, false);
    }

    /**
     * @param euGroups 列级欧式证据：{@code true} 时"单点 + 3 位尾数"按分组判（{@code 1.234} = 1234）、
     *                 "单逗号 + 3 位尾数"按小数判（{@code 1,234} = 1.234）
     */
    public static ParsedAmount parse(String raw, boolean euGroups) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean negative = false;
        // 会计负数：括号包起来（账单导出里比 "-123.45" 更常见）
        if (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
            negative = true;
            s = s.substring(1, s.length() - 1).trim();
        }
        // 摘币种：前后各来一轮，符号和 ISO 码都可能出现
        String[] currency = {null};
        s = stripCurrency(s, currency);
        if (s.isEmpty()) {
            return null;
        }
        // 去掉分组空格（法式 1 234,56）；剥完必须还是干净的数字/分隔符骨架（逗号交给后面的分隔符判定），
        // 否则不是我们认识的写法
        if (SPACE_GROUP.matcher(s).find()) {
            String stripped = SPACE_GROUP.matcher(s).replaceAll("");
            if (!Pattern.matches("[\\d.,]+", stripped)) {
                return null;
            }
            s = stripped;
        }
        // 显式负号（只允许在最前面）
        if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1).trim();
        } else if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            return null;
        }

        long commas = s.chars().filter(c -> c == ',').count();
        long dots = s.chars().filter(c -> c == '.').count();
        String number = normalizeSeparators(s, commas, dots, euGroups);
        if (number == null || !Pattern.matches(NUMBER_PATTERN, number)) {
            return null;
        }
        BigDecimal value = new BigDecimal(number);
        if (negative) {
            value = value.negate();
        }
        if (value.precision() > MAX_TOTAL_DIGITS) {
            return null;
        }
        return new ParsedAmount(value, currency[0]);
    }

    /** 该值是否是"脏金额"：能归一，但又不是 BigDecimal 直接认的纯数字（归一才有意义）。 */
    public static boolean isDirtyAmount(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (isPlainNumeric(s)) {
            return false;
        }
        return parse(s) != null;
    }

    /** BigDecimal 直接能认的纯数字（导入管道原本就会转 DECIMAL，无需归一）。 */
    public static boolean isPlainNumeric(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 列级欧式证据：采样里有没有"点分组 + 逗号小数"的铁证（如 {@code 1.234,56}、{@code 1.234.567,89}）。
     * 有它，单分隔符 + 3 位尾数的歧义就按欧式裁决。
     */
    public static boolean hasEuEvidence(List<String> sampledValues) {
        Pattern eu = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+,\\d{1,6}");
        for (String v : sampledValues) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String s = v.trim();
            if (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
            if (eu.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private static String normalizeSeparators(String s, long commas, long dots, boolean euGroups) {
        if (commas > 0 && dots > 0) {
            // 混用：靠右的是小数点，另一个必须是严格 3 位分组（自描述）
            char decChar = s.charAt(Math.max(s.lastIndexOf(','), s.lastIndexOf('.')));
            return resolveMixed(s, decChar == ',' ? '.' : ',');
        }
        if (commas == 0 && dots == 0) {
            return s;
        }
        char sep = commas > 0 ? ',' : '.';
        if (commas + dots >= 2) {
            // 同一种分隔符出现多次：只可能是分组符
            return resolveGroupOnly(s, sep);
        }
        // 单分隔符
        int sepPos = s.indexOf(sep);
        int digitsAfter = s.length() - sepPos - 1;
        if (digitsAfter <= 2) {
            // 1~2 位小数：两种约定下都是小数点，无歧义
            return s.replace(sep, '.');
        }
        if (digitsAfter > 3) {
            // 长尾数只能是长小数（4~6 位，汇率/单价常见），不可能再是分组
            boolean isDot = sep == '.';
            if (isDot) {
                return euGroups ? null : s; // 美：长小数合法；欧：点只能是分组符，尾数不是 3 位 → 不认
            }
            return euGroups ? s.replace(sep, '.') : null; // 欧：长逗号 = 小数；美：不认
        }
        if (digitsAfter == 3) {
            // 唯一歧义，列级证据裁决
            if (!Pattern.matches("\\d{1,3}", s.substring(0, sepPos))) {
                return null; // 分隔符前的头必须是 1~3 位（分组写法的样子），否则不认
            }
            boolean isDot = sep == '.';
            if (euGroups) {
                return isDot ? stripChar(s, sep) /* 1.234 → 1234 */ : s.replace(sep, '.') /* 1,234 → 1.234 */;
            }
            return isDot ? s /* 1.234 保持小数 */ : stripChar(s, sep) /* 1,234 → 1234 */;
        }
        return null; // 尾数超过 3 位又不是小数写法，不是我们认识的任何约定
    }

    /** 单一种分隔符出现 ≥2 次：必须是严格的 3 位分组（1,234,567），否则拒绝。 */
    private static String resolveGroupOnly(String s, char sep) {
        if (!Pattern.matches("\\d{1,3}(?:" + Pattern.quote(String.valueOf(sep)) + "\\d{3})+", s)) {
            return null;
        }
        return stripChar(s, sep);
    }

    private static String resolveMixed(String s, char groupSep) {
        int decPos = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
        String head = s.substring(0, decPos);
        String tail = s.substring(decPos + 1);
        if (!Pattern.matches("\\d{1,3}(?:" + Pattern.quote(String.valueOf(groupSep)) + "\\d{3})*", head)
                || !Pattern.matches("\\d{1,6}", tail)) {
            return null;
        }
        return stripChar(head, groupSep) + "." + tail;
    }

    private static String stripChar(String s, char c) {
        return s.replace(String.valueOf(c), "");
    }

    private static String stripCurrency(String s, String[] out) {
        s = stripEndSymbols(s);
        Matcher lead = ISO_LEAD.matcher(s);
        if (lead.find() && ISO_CODES.contains(lead.group(1).toUpperCase(Locale.ROOT))) {
            out[0] = lead.group(1).toUpperCase(Locale.ROOT);
            s = s.substring(lead.end());
        }
        Matcher tail = ISO_TAIL.matcher(s);
        if (tail.find() && ISO_CODES.contains(tail.group(1).toUpperCase(Locale.ROOT))) {
            out[0] = tail.group(1).toUpperCase(Locale.ROOT);
            s = s.substring(0, tail.start());
        }
        return s.trim();
    }

    /** 反复剥掉首尾的货币符号。 */
    private static String stripEndSymbols(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && SYMBOLS.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && SYMBOLS.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }
}
