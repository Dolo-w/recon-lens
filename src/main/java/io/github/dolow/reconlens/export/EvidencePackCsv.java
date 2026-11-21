package io.github.dolow.reconlens.export;

/**
 * CSV 行编码器：全字段加引号 + 引号翻倍 + Excel 公式注入防御 + UTF-8 BOM。
 *
 * <p>为什么全字段加引号：财务用 Excel 打开，中文、逗号、换行（凭证 JSON 里都有）
 * 不加引号必乱。引号翻倍是 RFC 4180 的标准转义。
 *
 * <p>公式注入：CSV 里以 {@code = + @} 开头的值会被 Excel 当公式执行（DSV 注入，
 * CVE 级别的老问题）。防御：这三个前缀加 {@code '} 钳掉。
 *
 * <p>{@code -} 开头的精细化处理（OWASP 的危险前缀清单里有它，但金额影响列本来就
 * 是负数，一刀切会把整个导出毁掉）：<b>数字形态</b>（{@code -} 后跟数字，如 {@code -2.00}）
 * 原样保留——Excel 只对文本单元格做 DDE/公式解析，真数值安全；<b>非数字形态</b>
 * （{@code -} 后跟其它字符，如 {@code -cmd|' /C calc'!D2}）照 {@code '} 钳掉。
 * 收发双方都是内部人员时风险本就可控，这层是纵深防御而非边界。
 *
 * <p>BOM：不带 BOM 的 UTF-8 CSV，Excel（中文环境）按 GBK 解必乱码。
 */
public final class EvidencePackCsv {

    /** UTF-8 BOM，Excel 中文环境识别编码用的。 */
    public static final String BOM = "\uFEFF";

    private final StringBuilder sb = new StringBuilder();

    /** 追加一行。null / 空值输出空字段。 */
    public void row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(encode(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        sb.append("\r\n");
    }

    private static String encode(String value) {
        return encodeCell(value);
    }

    /** 单格编码（public 供注入钳制断言直接测试；注释即契约）。 */
    public static String encodeCell(String value) {
        String v = value == null ? "" : value;
        char first = v.isEmpty() ? '\0' : v.charAt(0);
        if (first == '=' || first == '+' || first == '@') {
            v = "'" + v;
        } else if (first == '-' && v.length() > 1 && !Character.isDigit(v.charAt(1))) {
            // 数字形态的负数原样保留（金额语义优先），非数字形态按公式注入钳掉
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    /** 完整 CSV 字节（含 BOM），直接给 HTTP 响应体。 */
    public byte[] bytes() {
        return (BOM + sb).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
