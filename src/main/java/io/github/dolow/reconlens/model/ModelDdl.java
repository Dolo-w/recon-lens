package io.github.dolow.reconlens.model;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型表的 DDL：方言差异只出现在这里。
 *
 * <p>列形态固定为 {@code biz_key}（业务键）+ 规则声明过的字段；开启分片时再加一个按业务键算出来的
 * 生成列 {@code biz_key_bucket} 与它的索引。读侧因此一行引擎代码都不用改：模型规则写成
 * 「biz_key 升序取数 + biz_key_bucket = {index} AND biz_key_bucket &lt; {shards}」就能走索引定位分片。
 *
 * <p>故意不建主键、也不把 biz_key 声明成 NOT NULL：行级规则本来就可能带重复业务键（重复键要留给引擎
 * 报 DUPLICATE_KEY），聚合维度也可能有 NULL 值 —— 加了主键这些情况会在构建阶段直接失败，
 * 而正确性本来就不依赖模型表的唯一性。用普通索引同时满足合并取数的顺序与分片定位。
 *
 * <p>方言细节都是实测出来的（MySQL 8.0.29 / H2 2.2.224，见 docs/benchmark.md §3.10）：
 * MySQL 一条 {@code CREATE TABLE (定义) AS SELECT} 就能把生成列与索引一起建出来；
 * H2 的 CTAS 不继承列约束、也不支持在 CTAS 里定义生成列，要事后 {@code ALTER TABLE ... ADD COLUMN}，
 * 而且没有 {@code CRC32}，桶号只能用 {@code ORA_HASH(key, n)}。
 */
final class ModelDdl {

    /** 模型表里业务键的列名，同时也是模型规则的 keyField。 */
    static final String KEY_COLUMN = "biz_key";

    /**
     * 分片桶列。列名里带上 biz_key 是有意的：{@code RuleParser} 按业务键关键词判断"分片条件有没有按业务键切"，
     * 否则每次构建都会打一条误报的 WARN。
     */
    static final String BUCKET_COLUMN = "biz_key_bucket";

    private static final String SHADOW_SUFFIX = "__new";
    private static final String BACKUP_SUFFIX = "__old";
    private static final String PREFIX = "recon_model_";
    private static final int MAX_IDENTIFIER = 64;

    /** 每次构建都换一套索引名，见 {@link #indexName}。 */
    private static final AtomicLong BUILD_SERIAL = new AtomicLong();

    enum Dialect {
        MYSQL, H2, GENERIC
    }

    private ModelDdl() {
    }

    static Dialect detect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
            if (name.contains("mysql") || name.contains("mariadb")) {
                return Dialect.MYSQL;
            }
            if (name.contains("h2")) {
                return Dialect.H2;
            }
            return Dialect.GENERIC;
        } catch (SQLException e) {
            throw new ModelException("无法识别数据库方言: " + e.getMessage(), e);
        }
    }

    /**
     * 模型表名：只保留 [a-z0-9_]（防注入，也防超长标识符），再拼上 code 的哈希，
     * 避免两个只差大小写/标点的规则 code 撞到同一张表。
     */
    static String modelTable(String code, String suffix) {
        String cleaned = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (cleaned.length() > 32) {
            cleaned = cleaned.substring(0, 32);
        }
        String table = PREFIX + cleaned + "_" + Integer.toHexString(code.hashCode()) + "_" + suffix;
        if (table.length() > MAX_IDENTIFIER) {
            throw new ModelException("生成的模型表名过长: " + table);
        }
        return table;
    }

    static String shadowOf(String table) {
        return table + SHADOW_SUFFIX;
    }

    static String drop(String table) {
        return "DROP TABLE IF EXISTS " + table;
    }

    /** 建影子表（不动线上那张），建完再改名切换。 */
    static List<String> create(Dialect dialect, String shadow, String select, int buckets) {
        List<String> statements = new ArrayList<>();
        long serial = BUILD_SERIAL.incrementAndGet();
        statements.add(drop(shadow));
        if (dialect == Dialect.MYSQL) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(shadow)
                    .append(" (KEY ").append(keyIndexName(shadow, serial)).append(" (").append(KEY_COLUMN).append(')');
            if (buckets > 1) {
                ddl.append(", ").append(BUCKET_COLUMN).append(" SMALLINT GENERATED ALWAYS AS (")
                        .append(bucketExpression(dialect, buckets)).append(") STORED, KEY ")
                        .append(bucketIndexName(shadow, serial)).append(" (").append(BUCKET_COLUMN).append(')');
            }
            ddl.append(") AS ").append(select);
            statements.add(ddl.toString());
            return statements;
        }
        statements.add("CREATE TABLE " + shadow + " AS " + select);
        statements.add("CREATE INDEX " + keyIndexName(shadow, serial) + " ON " + shadow + " (" + KEY_COLUMN + ")");
        if (buckets > 1) {
            statements.add("ALTER TABLE " + shadow + " ADD COLUMN " + BUCKET_COLUMN
                    + " INT GENERATED ALWAYS AS (" + bucketExpression(dialect, buckets) + ")");
            statements.add("CREATE INDEX " + bucketIndexName(shadow, serial) + " ON " + shadow + " (" + BUCKET_COLUMN + ")");
        }
        return statements;
    }

    /** 改名切换：两侧用同一个桶函数，所以同一个业务键在两侧必然落到同一片，直接按索引取数即可。 */
    static List<String> switchTo(Dialect dialect, String model, String shadow, boolean replaceExisting) {
        List<String> statements = new ArrayList<>();
        String backup = model + BACKUP_SUFFIX;
        if (dialect == Dialect.MYSQL) {
            if (replaceExisting) {
                // 一次 RENAME 换两张表，中间没有"模型表不存在"的窗口
                statements.add("RENAME TABLE " + model + " TO " + backup + ", " + shadow + " TO " + model);
                statements.add(drop(backup));
            } else {
                statements.add("RENAME TABLE " + shadow + " TO " + model);
            }
            return statements;
        }
        if (replaceExisting) {
            statements.add("ALTER TABLE " + model + " RENAME TO " + backup);
            statements.add("ALTER TABLE " + shadow + " RENAME TO " + model);
            statements.add(drop(backup));
        } else {
            statements.add("ALTER TABLE " + shadow + " RENAME TO " + model);
        }
        return statements;
    }

    /**
     * 桶号 = 哈希后取模。<b>H2 的 {@code ORA_HASH(expr, n)} 返回 0..n（含 n，共 n+1 个桶）</b>——2.2.224 实测，
     * 直接拿它当桶号，落在第 n 个桶的行会被所有分片漏掉：数据凭空消失，还一条差异都不会报。所以再取一次模。
     * MySQL 的 {@code CRC32(key) % n} 本身就在 0..n-1。
     *
     * <p>键统一转字符串再哈希，两侧同一个业务键（哪怕列类型不同）才会落到同一个桶；{@code COALESCE} 让
     * NULL 键也有桶 —— 业务键为 NULL 的行仍然要参与对账，不能因为算不出桶就被丢掉。
     */
    private static String bucketExpression(Dialect dialect, int buckets) {
        return switch (dialect) {
            case MYSQL -> "CRC32(COALESCE(CAST(" + KEY_COLUMN + " AS CHAR), '')) % " + buckets;
            case H2 -> "MOD(ORA_HASH(COALESCE(CAST(" + KEY_COLUMN + " AS VARCHAR), ''), " + buckets + "), " + buckets + ")";
            case GENERIC -> throw new ModelException(
                    "当前数据库不支持生成桶列（只支持 MySQL / H2），请把 shards 设为 1 再构建");
        };
    }

    /** 业务键索引：合并取数按 biz_key 升序，走索引就不必再排序。 */
    private static String keyIndexName(String table, long serial) {
        return indexName(table, "key", serial);
    }

    /** 桶索引：分片取数 {@code biz_key_bucket = {index}} 靠它定位。 */
    private static String bucketIndexName(String table, long serial) {
        return indexName(table, "bucket", serial);
    }

    /**
     * 索引名 = 表名哈希 + 构建序号 + 用途。表名哈希是为了左右两侧不撞名；构建序号是因为
     * <b>H2 的索引名是库级唯一的</b>：影子表名每次都一样，改名切换后旧的索引名还挂在线上模型表上
     * （H2 不会跟着改名），第二次构建就会报 {@code Index "..." already exists}。
     * MySQL 的索引名只在本表内唯一，多这个后缀也无害。
     */
    private static String indexName(String table, String kind, long serial) {
        return "idx_" + Integer.toHexString(table.hashCode()) + "_" + Long.toHexString(serial) + "_" + kind;
    }
}
