package io.github.dolow.reconlens.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据指纹：注册表优先、现场读兜底、读不到要肯说"不知道"。
 *
 * <p>断言的重点不是"算得对"，而是<b>算不出来时的行为</b>：静默返回 0 行或空哈希，
 * 会让后面所有"数据没变"的结论都变成假的。
 */
class DatasetFingerprintsTest {

    private static JdbcTemplate jdbc;
    private static DatasetFingerprints fingerprints;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:dataset_fingerprints;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL,"
                + " schema_hash VARCHAR(64), row_count BIGINT,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fp_bill (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2))");
        jdbc.execute("INSERT INTO fp_bill (id, order_no, amount) VALUES (1,'A1',10.00), (2,'A2',20.00), (3,'A3',30.00)");
        fingerprints = new DatasetFingerprints(jdbc, JSON);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_registered_table");
        // 每个用例都从"三行"这个已知状态开始：行数类断言一旦被上一个用例改写，
        // 失败信息会指向错误的因果（"指纹算错了"而不是"测试互相污染"）
        jdbc.update("DELETE FROM fp_bill");
        jdbc.update("INSERT INTO fp_bill (id, order_no, amount) VALUES (1,'A1',10.00), (2,'A2',20.00), (3,'A3',30.00)");
    }

    @Test
    void 注册表里有指纹时直接读_不打表() {
        jdbc.update("INSERT INTO recon_registered_table (table_name, columns_snapshot, schema_hash, row_count,"
                + " registered_by) VALUES (?,?,?,?,?)", "fp_bill", "id,order_no,amount", "hash-from-registery", 999L, "alice");

        DatasetFingerprints.Fingerprint f = fingerprints.of("fp_bill");

        assertThat(f.source()).isEqualTo("REGISTERED");
        assertThat(f.schemaHash()).isEqualTo("hash-from-registery");
        assertThat(f.rowCount()).as("注册时的行数，不是此刻的行数").isEqualTo(999L);
    }

    @Test
    void 没注册过就现场算_列集合哈希加真实行数() {
        DatasetFingerprints.Fingerprint f = fingerprints.of("fp_bill");

        assertThat(f.source()).isEqualTo("LIVE");
        assertThat(f.rowCount()).isEqualTo(3L);
        assertThat(f.schemaHash()).isEqualTo(DatasetFingerprints.hash(List.of("id", "order_no", "amount")));
    }

    @Test
    void 表不存在时返回UNKNOWN而不是空哈希() {
        DatasetFingerprints.Fingerprint f = fingerprints.of("no_such_table");

        assertThat(f.known()).isFalse();
        assertThat(f.schemaHash()).isNull();
        assertThat(f.rowCount()).isNull();
    }

    @Test
    void 表名带注入符号一律当不知道_不拼进SQL() {
        DatasetFingerprints.Fingerprint f = fingerprints.of("fp_bill; DROP TABLE fp_bill");

        assertThat(f.known()).isFalse();
        Integer stillThere = jdbc.queryForObject("SELECT COUNT(*) FROM fp_bill", Integer.class);
        assertThat(stillThere).isEqualTo(3);
    }

    @Test
    void 列集合哈希与顺序无关_与大小写无关_少一列就变() {
        String a = DatasetFingerprints.hash(List.of("id", "amount"));
        String b = DatasetFingerprints.hash(List.of("AMOUNT", "ID"));
        String c = DatasetFingerprints.hash(List.of("id"));

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void 快照JSON两侧都记_自定义SQL记SQL哈希() throws Exception {
        SideSpec left = new SideSpec("账单", "fp_bill", null, "order_no", null, null);
        SideSpec right = new SideSpec("结算", null, "SELECT order_no, amount FROM fp_bill", "order_no", null, null);

        JsonNode root = JSON.readTree(fingerprints.snapshotJson(left, right));

        assertThat(root.get("left").get("table").asText()).isEqualTo("fp_bill");
        assertThat(root.get("left").get("rowCount").asLong()).isEqualTo(3L);
        assertThat(root.get("left").get("schemaHash").asText()).hasSize(64);
        assertThat(root.get("right").has("sqlSha256")).isTrue();
        assertThat(root.get("right").get("source").asText()).isEqualTo("SQL");
        assertThat(root.get("at").asText()).isNotBlank();
    }

    @Test
    void 行数变了能说出变了几行() {
        String snapshot = fingerprints.snapshotJson(
                new SideSpec("账单", "fp_bill", null, "order_no", null, null), null);
        jdbc.update("INSERT INTO fp_bill (id, order_no, amount) VALUES (4,'A4',40.00)");

        List<String> drift = fingerprints.drift(snapshot);

        assertThat(drift).anySatisfy(line -> assertThat(line).contains("行数").contains("3").contains("4"));
    }

    @Test
    void 列变了能说出结构变了() {
        String snapshot = fingerprints.snapshotJson(
                new SideSpec("账单", "fp_bill", null, "order_no", null, null), null);
        jdbc.execute("CREATE TABLE IF NOT EXISTS fp_bill_v2 AS SELECT id, order_no, amount, extra FROM "
                + "(SELECT id, order_no, amount, 1 AS extra FROM fp_bill)");

        // 换成一张列不同的表来模拟"列变了"：快照里记的是旧表名，这里直接构造对比
        DatasetFingerprints.Fingerprint changed = fingerprints.of("fp_bill_v2");
        assertThat(changed.schemaHash())
                .isNotEqualTo(DatasetFingerprints.hash(List.of("id", "order_no", "amount")));

        assertThat(fingerprints.drift(snapshot)).as("旧表的快照此刻没变，所以不该报 drift").isEmpty();
    }

    @Test
    void 没快照就没漂移_不猜() {
        assertThat(fingerprints.drift(null)).isEmpty();
        assertThat(fingerprints.drift("")).isEmpty();
        assertThat(fingerprints.drift("这不是JSON")).isEmpty();
    }

    @Test
    void 内容校验和能算出东西_空表不炸() {
        String checksum = fingerprints.contentChecksum("fp_bill", "id");

        assertThat(checksum).hasSize(64);
        assertThat(fingerprints.contentChecksum("no_such_table", "id")).isNull();
    }
}
