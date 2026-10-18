package io.github.dolow.reconlens.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单是 <b>fail-closed</b>：没配 = 一张表也不放行，而不是"全部允许"。
 * 忘了配白名单的后果应该是"Agent 生成不出规则"，不是"Agent 可以读整库"。
 */
class SchemaCatalogTest {

    private static DataSource dataSource;

    @BeforeAll
    static void createTables() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:schema_catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_left (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2), status VARCHAR(16))");
        jdbc.execute("DROP TABLE IF EXISTS bill_secret");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bill_secret (id BIGINT PRIMARY KEY, secret VARCHAR(32))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("DELETE FROM recon_registered_table");
        registry = new io.github.dolow.reconlens.store.RegisteredTableRepository(jdbc);
    }

    private static SchemaCatalog catalog(String... tables) {
        AgentProperties properties = new AgentProperties();
        properties.getSchema().setTables(List.of(tables));
        return new SchemaCatalog(dataSource, properties);
    }

    @Test
    void 白名单内的表能读到列元数据() {
        SchemaCatalog catalog = catalog("bill_left");

        SchemaCatalog.TableSchema table = catalog.find("bill_left").orElseThrow();

        assertThat(table.hasColumn("order_no")).isTrue();
        assertThat(table.hasColumn("ORDER_NO")).as("列名比对不区分大小写").isTrue();
        assertThat(table.hasColumn("amount")).isTrue();
        assertThat(table.hasColumn("not_there")).isFalse();
    }

    @Test
    void 白名单比对忽略schema前缀与大小写() {
        SchemaCatalog catalog = catalog("billing.BILL_LEFT");

        assertThat(catalog.allows("bill_left")).isTrue();
        assertThat(catalog.allows("billing.bill_left")).isTrue();
        assertThat(catalog.allows("bill_right")).isFalse();
    }

    @Test
    void 白名单为空时全部拒绝() {
        SchemaCatalog catalog = catalog();

        assertThat(catalog.allows("bill_left")).isFalse();
        assertThat(catalog.find("bill_left")).isEmpty();
    }

    @Test
    void 白名单里有但库里不存在的表查不到() {
        SchemaCatalog catalog = catalog("bill_missing");

        assertThat(catalog.allows("bill_missing")).isTrue();
        assertThat(catalog.find("bill_missing")).as("配了白名单不等于表真的存在").isEmpty();
    }

    @Test
    void 没进白名单的表查不到() {
        SchemaCatalog catalog = catalog("bill_left");

        assertThat(catalog.find("bill_secret")).isEmpty();
    }

    // ---------------- 动态表注册（白名单的"运行时半边"） ----------------

    private static io.github.dolow.reconlens.store.RegisteredTableRepository registry;

    private static SchemaCatalog catalogWithRegistry(String... configured) {
        AgentProperties properties = new AgentProperties();
        properties.getSchema().setTables(List.of(configured));
        return new SchemaCatalog(dataSource, properties, registry);
    }

    @Test
    void 动态注册的表进入白名单() {
        registry.upsert("bill_secret", "id,secret", "tester");
        SchemaCatalog catalog = catalogWithRegistry();   // 配置白名单为空：只剩动态半边

        assertThat(catalog.allows("bill_secret")).isTrue();
        assertThat(catalog.find("bill_secret")).hasValueSatisfying(
                t -> assertThat(t.hasColumn("secret")).isTrue());
        assertThat(catalog.allAllowedTables()).contains("bill_secret");
    }

    @Test
    void 动态注册不放过配置白名单里的其他表() {
        registry.upsert("bill_secret", "id,secret", "tester");
        SchemaCatalog catalog = catalogWithRegistry("bill_left");

        assertThat(catalog.allows("bill_left")).isTrue();
        assertThat(catalog.allows("bill_secret")).isTrue();
        assertThat(catalog.allows("bill_right")).isFalse();
        assertThat(catalog.allAllowedTables()).containsExactlyInAnyOrder("bill_left", "bill_secret");
    }

    @Test
    void addDynamic让本进程注册立即生效() {
        SchemaCatalog catalog = catalogWithRegistry();   // 库里没注册过

        assertThat(catalog.find("bill_left")).isEmpty();

        catalog.addDynamic("bill_left");

        assertThat(catalog.allows("bill_left")).isTrue();
        assertThat(catalog.find("bill_left")).hasValueSatisfying(
                t -> assertThat(t.hasColumn("order_no")).isTrue());
    }

    @Test
    void probe不看白名单_预览用() {
        SchemaCatalog catalog = catalog();               // 白名单为空

        assertThat(catalog.find("bill_left")).isEmpty();
        assertThat(catalog.probe("bill_left")).hasValueSatisfying(
                t -> assertThat(t.hasColumn("amount")).isTrue());
    }

    @Test
    void 表达式探针_合法表达式通过_缺列报错() {
        SchemaCatalog catalog = catalog("bill_left");

        assertThat(catalog.probeKeyExpression("bill_left", "CONCAT(order_no, '|', status)")).isEmpty();
        assertThat(catalog.probeKeyExpression("bill_left", "CONCAT(order_no, '#', no_such_col)"))
                .hasValueSatisfying(error -> assertThat(error).containsIgnoringCase("no_such_col"));
        assertThat(catalog.probeKeyExpression("bill_left", "amount; DROP TABLE bill_left"))
                .hasValueSatisfying(error -> assertThat(error).contains("分号"));
        assertThat(catalog.probeKeyExpression("bill_left", "CONCAT(customer_id, '|', (SELECT secret FROM bill_secret))"))
                .hasValueSatisfying(error -> assertThat(error).contains("子查询"));
    }
}
