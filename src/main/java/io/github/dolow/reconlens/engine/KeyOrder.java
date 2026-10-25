package io.github.dolow.reconlens.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 业务键排序的"方言闸门"。
 *
 * <p><b>它解决的问题</b>：归并式对账的正确性依赖一条隐含契约 ——
 * <i>SQL 里 {@code ORDER BY} 的顺序必须和 Java 里 {@code String#compareTo} 的顺序一致</i>。
 * 在 H2 上这条契约天然成立（VARCHAR 按字符码比较）；在 MySQL 上不成立：
 * 生产库常用 {@code utf8mb4_general_ci}，它<b>大小写不敏感、且不按码点排序</b>。
 *
 * <p>实测过的反例（MySQL 8.0.29，utf8mb4_general_ci）：
 * 左侧只有 {@code C777|Zebra}，右侧有 {@code C777|apple} 与 {@code C777|Zebra}。
 * MySQL 把 {@code apple} 排在 {@code Zebra} 前面（ci 排序），而 Java 认为 {@code Zebra < apple}
 * （{@code 'Z'=90 < 'a'=97}）。于是双指针在第一轮就把 {@code Zebra} 判成"左侧缺"并推进左侧，
 * 随后右侧那两条又各自变成"右侧缺" —— 同一笔业务被报成 <b>三条</b>差异，
 * 而正确答案是一条（{@code apple} 只在右侧有）。
 *
 * <p><b>做法</b>：MySQL 上把排序键显式转成 {@code utf16} 二进制比较。
 * 选 {@code utf16_bin} 而不是 {@code utf8mb4_bin} 是有原因的：Java 的 {@code compareTo}
 * 比的是 <b>UTF-16 码元</b>，而 {@code utf8mb4_bin} 比的是 UTF-8 字节序 —— 两者只在
 * 出现 4 字节字符（emoji 等 U+10000 以上）时才分叉，但那时归并同样会错位。
 * {@code utf16_bin} 与 Java 逐码元比较完全等价。
 *
 * <p>拿不到数据库类型时退回原样（H2 行为），也就是保持改动前的老路子，不引入新失败点。
 */
@Component
public class KeyOrder {

    private static final Logger log = LoggerFactory.getLogger(KeyOrder.class);

    /** MySQL/MariaDB 上等价于 Java 码元序的排序表达式模板。 */
    private static final String MYSQL_ORDER = "CONVERT(%s USING utf16) COLLATE utf16_bin";

    private final DataSource dataSource;
    private volatile String product;

    public KeyOrder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 生成"与 {@code String#compareTo} 同序"的排序表达式。
     *
     * @param column 业务键列（或列表达式）
     */
    public String orderKey(String column) {
        return binary() ? String.format(MYSQL_ORDER, column) : column;
    }

    /** 当前数据库是否需要显式二进制排序。 */
    public boolean binary() {
        String p = product();
        if (p == null) {
            return false;
        }
        String lower = p.toLowerCase(Locale.ROOT);
        return lower.contains("mysql") || lower.contains("mariadb");
    }

    /** 给人看的说明，用于日志与报告。 */
    public String describe() {
        String p = product();
        if (p == null) {
            return "未知数据库（按码点序排序）";
        }
        return binary()
                ? p + "：业务键按 utf16_bin 排序（与 Java 码元序一致，避免 ci 排序造成的假差异）"
                : p + "：业务键按码点序排序（H2 默认即与 Java 一致）";
    }

    private String product() {
        String cached = product;
        if (cached != null) {
            return cached;
        }
        try (Connection connection = dataSource.getConnection()) {
            cached = connection.getMetaData().getDatabaseProductName();
            product = cached;
            log.info("业务键排序口径：{}", describe());
            return cached;
        } catch (SQLException e) {
            // 拿不到连接不该让对账起不来：退回老路子（不额外加 COLLATE）
            log.warn("探测数据库类型失败，业务键按默认序排序（可能与 Java 比较器不一致）：{}", e.getMessage());
            return null;
        }
    }
}
