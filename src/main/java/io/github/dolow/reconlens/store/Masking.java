package io.github.dolow.reconlens.store;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 样例值脱敏。
 *
 * <p>为什么默认脱敏而不是默认原样：给人看结构不需要原文，但"顺手截个图发群里"是常态。
 * 默认给原文，泄露是默认结果；默认打码，要看原文得改配置（{@code recon.schema.sample-raw=true}）
 * —— 这是"默认安全、例外显式"的取舍。
 *
 * <p>两条防线，缺一不可：
 * <ol>
 *   <li><b>按列名</b>：{@code phone}、{@code id_card}、{@code email}、{@code address} 这些一看就知道是敏感字段；</li>
 *   <li><b>按值形态</b>：列名可能叫 {@code ext_01}，值却是 11 位手机号或邮箱。形态命中也要打码。</li>
 * </ol>
 * 只看列名会漏掉 {@code ext_01}，只看值会漏掉"短身份证/带分隔符的电话"。两条件取或。
 */
public final class Masking {

    private Masking() {
    }

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");
    private static final Pattern ID_CARD = Pattern.compile("^\\d{15}$|^\\d{17}[\\dXx]$");
    private static final Pattern BANK_CARD = Pattern.compile("^\\d{12,19}$");

    /** 手机：保留前 3 后 4。 */
    private static String maskPhone(String v) {
        return v.length() >= 8 ? v.substring(0, 3) + "****" + v.substring(v.length() - 4) : "****";
    }

    /** 邮箱：保留首字母与域名，本地部分其余打码。 */
    private static String maskEmail(String v) {
        int at = v.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String head = v.substring(0, 1);
        return head + "***" + v.substring(at);
    }

    /** 证件/卡号：只留末 4 位。 */
    private static String maskTail4(String v) {
        return v.length() > 4 ? "****" + v.substring(v.length() - 4) : "****";
    }

    /** 姓名：只留姓。 */
    private static String maskName(String v) {
        return v.length() <= 1 ? "*" : v.substring(0, 1) + "*".repeat(Math.min(2, v.length() - 1));
    }

    /** 地址：只留前 6 个字，够看出"是哪个城市"但不给门牌。 */
    private static String maskAddress(String v) {
        return v.length() <= 6 ? v.substring(0, Math.min(1, v.length())) + "***" : v.substring(0, 6) + "……";
    }

    /** 这一列是不是"按名字就该打码"的敏感列。 */
    static boolean sensitiveByName(String column) {
        if (column == null) {
            return false;
        }
        String name = column.toLowerCase(Locale.ROOT);
        for (String key : new String[]{"phone", "mobile", "tel", "手机", "电话", "联系方式"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        for (String key : new String[]{"id_card", "idcard", "idno", "id_no", "identity", "身份证", "证件"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        for (String key : new String[]{"email", "mail", "邮箱", "邮件"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        for (String key : new String[]{"name", "姓名", "联系人"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        for (String key : new String[]{"address", "addr", "地址", "住址"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        for (String key : new String[]{"card", "account", "acct", "卡号", "账号"}) {
            if (name.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 打一个值。
     *
     * @param column 列名，用于"按名字命中"
     * @param value  原值，null 原样返回
     */
    public static String mask(String column, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        // 先按值形态：形态比列名更可靠，名字可以叫 ext_01
        if (PHONE.matcher(trimmed).matches()) {
            return maskPhone(trimmed);
        }
        if (EMAIL.matcher(trimmed).matches()) {
            return maskEmail(trimmed);
        }
        if (ID_CARD.matcher(trimmed).matches()) {
            return maskTail4(trimmed);
        }
        if (BANK_CARD.matcher(trimmed).matches()) {
            return maskTail4(trimmed);
        }
        // 再按列名：值本身看不出敏感（姓名、地址这种）
        String name = column == null ? "" : column.toLowerCase(Locale.ROOT);
        if (name.contains("地址") || name.contains("address") || name.contains("addr")) {
            return maskAddress(trimmed);
        }
        if (name.contains("姓名") || name.contains("name") || name.contains("联系人")) {
            return maskName(trimmed);
        }
        if (sensitiveByName(column)) {
            // 敏感列但形态不认识：一律只留末 4 位，宁可少给信息
            return maskTail4(trimmed);
        }
        return value;
    }
}
