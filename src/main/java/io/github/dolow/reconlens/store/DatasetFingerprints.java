package io.github.dolow.reconlens.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 数据的"身份证"：一张表读进去的是<b>哪一份</b>数据。
 *
 * <p>为什么规则快照还不够：规则只说了"读 {@code bill_left}"，可 {@code bill_left} 这张表
 * 今天和昨天是两份数据。没有数据指纹时，两次对账结果不同只能靠人回忆"昨天是不是重灌了"；
 * 有了指纹，任务自己答得出"我读的那份数据，列是这些、行是这些"（ADR-0002 N4）。
 *
 * <p>两级指纹，代价不同：
 * <ol>
 *   <li><b>L1（便宜，注册与建任务时都算）</b>：列名集合的 sha256 + {@code COUNT(*)}。
 *       列变了或行数变了，一眼看出来；内容变了但行列没变，L1 看不出来 —— 这是刻意的取舍，
 *       否则每建一个任务就要全表扫一遍算校验和。</li>
 *   <li><b>L2（按需）</b>：{@code content_checksum}，见 {@link #contentChecksum(String)}。只在
 *       "两次结果不一样但 L1 一致"时才值得算。</li>
 * </ol>
 *
 * <p><b>不作为静态工具类到处 new</b>：它要读 {@code recon_registered_table} 与数据源元数据，
 * 这两样都随部署变；统一成一个 bean，指纹算法只有一处在算。
 */
@Component
public class DatasetFingerprints {

    private static final Logger log = LoggerFactory.getLogger(DatasetFingerprints.class);

    /** 表名只允许 [schema.]标识符 —— 这个类会拼 SQL，边界必须自己守，不能指望上游都校验过。 */
    private static final Pattern SAFE_TABLE = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DatasetFingerprints(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 一份数据的指纹。
     *
     * @param source {@code REGISTERED} 取自注册表快照（注册那一刻的列与行数，最便宜）；
     *               {@code LIVE} 现场从数据源读（没注册过的表）；{@code UNKNOWN} 读不到。
     *               <b>两种来源都算"知道"，但必须标出来</b>：任务说明里写的是注册时的行数还是此刻的行数，
     *               决定了这个数字能不能当证据。
     */
    public record Fingerprint(String table, String schemaHash, Long rowCount, String source) {

        public static Fingerprint unknown(String table) {
            return new Fingerprint(table, null, null, "UNKNOWN");
        }

        public boolean known() {
            return !"UNKNOWN".equals(source);
        }
    }

    /** 注册表优先（零扫描），没有就现场读一次。读不到返回 {@code UNKNOWN}，不抛异常。 */
    public Fingerprint of(String table) {
        if (table == null || table.isBlank()) {
            return Fingerprint.unknown(null);
        }
        String name = table.trim();
        Optional<Fingerprint> registered = registered(name);
        if (registered.isPresent()) {
            return registered.get();
        }
        return live(name);
    }

    private Optional<Fingerprint> registered(String table) {
        if (!SAFE_TABLE.matcher(table).matches()) {
            return Optional.empty();
        }
        try {
            List<Fingerprint> rows = jdbc.query(
                    "SELECT schema_hash, row_count FROM recon_registered_table WHERE table_name = ?",
                    (rs, i) -> new Fingerprint(table, rs.getString(1),
                            rs.getObject(2) == null ? null : rs.getLong(2), "REGISTERED"),
                    table);
            return rows.stream().findFirst();
        } catch (RuntimeException e) {
            // 老库没有这两列（迁移脚本还没跑）时不能让对账挂掉：退化成现场读。
            log.debug("读注册表指纹失败，改现场读：table={} reason={}", table, e.getMessage());
            return Optional.empty();
        }
    }

    private Fingerprint live(String table) {
        if (!SAFE_TABLE.matcher(table).matches()) {
            return Fingerprint.unknown(table);
        }
        List<String> columns = columnsOf(table);
        if (columns.isEmpty()) {
            return Fingerprint.unknown(table);
        }
        return new Fingerprint(table, hash(columns), countRows(table), "LIVE");
    }

    /** 现场读列名。拿不到返回空列表（权限/方言/表不存在都由调用方当成"未知"处理，不抛）。 */
    public List<String> columnsOf(String table) {
        if (table == null || !SAFE_TABLE.matcher(table.trim()).matches()) {
            return List.of();
        }
        try {
            return jdbc.query("SELECT * FROM " + table.trim() + " WHERE 1 = 0", rs -> {
                List<String> names = new ArrayList<>();
                var meta = rs.getMetaData();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    names.add(meta.getColumnLabel(c));
                }
                return names;
            });
        } catch (RuntimeException e) {
            log.debug("读列名失败：table={} reason={}", table, e.getMessage());
            return List.of();
        }
    }

    public Long countRows(String table) {
        if (table == null || !SAFE_TABLE.matcher(table.trim()).matches()) {
            return null;
        }
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table.trim(), Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 列名集合的 sha256。<b>先排序再哈希</b>：同一个列集合换个物理顺序不该算成"结构变了"，
     * 反过来（少一列/多一列/改名）必须变。
     */
    public static String hash(List<String> columns) {
        List<String> sorted = new ArrayList<>();
        for (String c : columns) {
            if (c != null && !c.isBlank()) {
                sorted.add(c.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        sorted.sort(String::compareTo);
        return sha256(String.join(",", sorted));
    }

    /**
     * L2：按内容算校验和。默认<b>不</b>进任务快照 —— 见类注释里的取舍。
     *
     * <p>用「键数 + 三件套」而不是 {@code SUM(CRC32(...))}：后者在 MySQL 与 H2 上对空表、
     * 对溢出、对字符集的处理不一致，跨方言对不上账的校验和等于没有。
     */
    public String contentChecksum(String table, String keyColumn) {
        if (table == null || !SAFE_TABLE.matcher(table.trim()).matches()
                || keyColumn == null || !SAFE_TABLE.matcher(keyColumn.trim()).matches()) {
            return null;
        }
        try {
            String sql = "SELECT COUNT(DISTINCT " + keyColumn.trim() + ") AS k,"
                    + " MIN(" + keyColumn.trim() + ") AS lo, MAX(" + keyColumn.trim() + ") AS hi FROM " + table.trim();
            return jdbc.query(sql, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return sha256(rs.getLong("k") + "|" + rs.getString("lo") + "|" + rs.getString("hi"));
            });
        } catch (RuntimeException e) {
            log.debug("内容校验和失败：table={} key={} reason={}", table, keyColumn, e.getMessage());
            return null;
        }
    }

    /**
     * 把规则两侧写成一段可存进 {@code recon_job.data_snapshot} 的 JSON。
     *
     * <p>自定义 SQL 的那一侧没有表名可查，记 SQL 的哈希：<b>记不住"读了什么数据"，至少要记住
     * "用哪段 SQL 读的"</b> —— 否则 job 之间无法解释差异从哪来。
     */
    public String snapshotJson(SideSpec left, SideSpec right) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("left", describe(left));
        root.put("right", describe(right));
        root.put("at", Instant.now().toString());
        try {
            return json.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("数据快照序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> describe(SideSpec side) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (side == null) {
            return m;
        }
        if (side.table() != null && !side.table().isBlank()) {
            Fingerprint f = of(side.table());
            m.put("table", side.table());
            m.put("schemaHash", f.schemaHash());
            m.put("rowCount", f.rowCount());
            m.put("source", f.source());
            return m;
        }
        m.put("sqlSha256", sha256(side.sql() == null ? "" : side.sql()));
        m.put("source", "SQL");
        return m;
    }

    /**
     * 快照与此刻的差别，人话。空列表 = 没变（或者快照根本没记）。
     *
     * <p>用途：重跑一个老任务时提示"数据已经变了，这次重跑算的是新数据"。这正是 ADR-0002 D4
     * 要防的事：把两次数据缝进一个 job 里，还声称结果可比。
     */
    public List<String> drift(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        List<String> diff = new ArrayList<>();
        try {
            JsonNode root = json.readTree(snapshotJson);
            checkSide(diff, "左侧", root.get("left"));
            checkSide(diff, "右侧", root.get("right"));
        } catch (Exception e) {
            return List.of();
        }
        return diff;
    }

    private void checkSide(List<String> diff, String label, JsonNode side) {
        if (side == null || !side.hasNonNull("table")) {
            return;
        }
        String table = side.get("table").asText();
        String wasHash = side.hasNonNull("schemaHash") ? side.get("schemaHash").asText() : null;
        Long wasRows = side.hasNonNull("rowCount") ? side.get("rowCount").asLong() : null;
        Fingerprint now = of(table);
        if (wasHash != null && now.schemaHash() != null && !wasHash.equals(now.schemaHash())) {
            diff.add(label + "表 " + table + " 的列已经变了（结构指纹不同）");
        }
        if (wasRows != null && now.rowCount() != null && !wasRows.equals(now.rowCount())) {
            diff.add(label + "表 " + table + " 的行数从 " + wasRows + " 变成 " + now.rowCount());
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 没有 SHA-256", e);
        }
    }
}
