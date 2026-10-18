package io.github.dolow.reconlens.agent;

/**
 * SQL 文本的<b>形态预处理</b>：把注释与字符串字面量换成空格，只留下"会被数据库当语法看"的那部分。
 *
 * <p>它存在的唯一理由是<b>单一出处</b>。这个动作有两个调用方：闸门 1 找写操作关键字
 * （{@code status <> 'DELETED'} 不该被当成 {@code DELETE}），以及 {@link SqlTruncationDetector}
 * 找 {@code LIMIT}（{@code WHERE note = 'LIMIT 100'} 不该被当成截断）。
 * 两份剥离逻辑一定会走偏 —— 其中一份改了转义规则，另一份没改，于是同一段 SQL 在两个校验里
 * 被理解成两种样子，而错的那一侧不会报错，只会静默放过。
 *
 * <p><b>反引号故意不剥</b>：它是标识符引用（{@code `bill_left`}），剥掉会让表名抽取失效。
 * 代价是 {@code `limit`} 这种"叫 limit 的列"要靠正则里的 lookaround 排除，见 {@link SqlTruncationDetector}。
 */
final class SqlText {

    private SqlText() {
    }

    /** 注释（{@code --} / {@code /* *}{@code /}）与字符串字面量（{@code '...'} / {@code "..."}）替换成空格。 */
    static String stripCommentsAndLiterals(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, sql.length());
                out.append(' ');
            } else if (c == '\'' || c == '"') {
                // 反引号不剥：它是标识符引用（`bill_left`），剥掉会让表名抽取失效
                char quote = c;
                i++;
                while (i < sql.length()) {
                    char current = sql.charAt(i);
                    if (current == quote) {
                        // 连续两个引号是转义，不是结束
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(" '' ");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
