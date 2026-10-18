package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.store.RegisteredTableRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态表注册：preview 不改状态，confirm 落库 + 白名单立即生效，表名从严校验。
 *
 * <p>这条链路是"新用户数据结构不同"的接入路径 —— 注册改变的是安全边界本身，
 * 所以断言的重点是：<b>失败路径一个字节的变更都不能留</b>。
 */
class TableRegistrarTest {

    private static DataSource dataSource;
    private static RegisteredTableRepository registry;
    private static TableRegistrar registrar;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:table_registrar;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_order (id BIGINT PRIMARY KEY, order_no VARCHAR(32),"
                + " amount DECIMAL(18,2))");
        jdbc.execute("INSERT INTO t_order (id, order_no, amount) VALUES (1, 'A1', 10.00), (2, 'A2', 20.00)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        registry = new RegisteredTableRepository(jdbc);
        registrar = new TableRegistrar(catalog(), registry, jdbc);
    }

    private static SchemaCatalog catalog() {
        AgentProperties properties = new AgentProperties();
        properties.getSchema().setTables(List.of());
        return new SchemaCatalog(dataSource, properties, registry);
    }

    @Test
    void preview给出列清单与行数但不改状态() {
        TableRegistrar.Preview p = registrar.preview("t_order");

        assertThat(p.error()).isNull();
        assertThat(p.columns()).containsExactlyInAnyOrder("id", "order_no", "amount");
        assertThat(p.rows()).isEqualTo(2L);
        assertThat(registry.tableNames()).as("preview 不能改任何状态").isEmpty();
    }

    @Test
    void 非法表名在preview就被拒绝() {
        assertThat(registrar.preview("t_order; DROP TABLE t_order").error()).contains("表名不合法");
        assertThat(registrar.preview("`t_order`").error()).contains("表名不合法");
        assertThat(registrar.preview("t order").error()).contains("表名不合法");
        assertThat(registrar.preview("").error()).contains("表名不合法");
    }

    @Test
    void confirm会扩大白名单_但只针对确认过的那张表() {
        int before = registry.tableNames().size();
        assertThat(registrar.confirm("t_order", "tester").error()).isNull();
        assertThat(registry.tableNames()).hasSize(before + 1).contains("t_order");
    }

    @Test
    void 重复confirm覆盖登记人() {
        registrar.confirm("t_order", "tester-1");
        registrar.confirm("t_order", "tester-2");
        assertThat(registry.find("t_order")).hasValueSatisfying(
                r -> assertThat(r.registeredBy()).isEqualTo("tester-2"));
    }

    @Test
    void 不存在的表给出明确错误() {
        assertThat(registrar.preview("t_missing").error()).contains("查不到这张表的元数据");
    }

    @Test
    void confirm落库并让白名单立即生效() {
        TableRegistrar.Preview p = registrar.confirm("t_order", "tester");

        assertThat(p.error()).isNull();
        assertThat(registry.find("t_order")).hasValueSatisfying(r -> {
            assertThat(r.columnsSnapshot()).contains("order_no", "amount");
            assertThat(r.registeredBy()).isEqualTo("tester");
        });
        // 注册前 catalog() 在 setUp 时已建好 —— 注册对"同一个实例"立即生效才是关键断言
        assertThat(registrar.confirm("t_order", "tester-2").error()).isNull();
        assertThat(registry.find("t_order")).hasValueSatisfying(
                r -> assertThat(r.registeredBy()).isEqualTo("tester-2"));
    }

    @Test
    void confirm失败时不落任何东西() {
        assertThat(registrar.confirm("t_missing", "tester").error()).isNotNull();
        assertThat(registry.tableNames()).doesNotContain("t_missing");
    }

    @org.junit.jupiter.api.BeforeEach
    void cleanRegistrations() {
        new JdbcTemplate(dataSource).update("DELETE FROM recon_registered_table");
    }
}
