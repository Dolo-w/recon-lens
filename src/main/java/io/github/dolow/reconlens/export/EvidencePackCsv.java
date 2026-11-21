package io.github.dolow.reconlens.export;

/**
 * CSV 行编码器：全字段加引号 + 引号翻倍 + Excel 公式注入防御 + UTF-8 BOM。
 *
 * <p>为什么全字段加引号：财务用 Excel 打开，中文、逗号、换行（凭证 JSON 里都有）
 * 不加引号必乱。引号翻倍是 RFC 4180 的标准转义。
 *
 * <p>公式注入：CSV 里以 {@code = + @} 开头的值会被 Excel 当公式执行（DSV 注入，
 * CVE 级别的老问题）。防御：这三个前缀加 {@code '} 钳掉。负数（{@code -}开头）
 * 不防 —— 金额影响列本来就是负数，防了会毁掉整个导出的意义；这是已知取舍，
 * 收发双方都是内部人员时风险可控。
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
        String v = value;
        char first = v.isEmpty() ? '\0' : v.charAt(0);
        if (first == '=' || first == '+' || first == '@') {
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    /** 完整 CSV 字节（含 BOM），直接给 HTTP 响应体。 */
    public byte[] bytes() {
        return (BOM + sb).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
