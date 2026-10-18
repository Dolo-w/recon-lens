package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 动态表注册表：upsert 幂等（覆盖快照与登记人），tableNames 供闸门 1 的动态半边使用。 */
class RegisteredTableRepositoryTest {

    private static JdbcTemplate jdbc;
    private static RegisteredTableRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:registered_table_repo;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        repository = new RegisteredTableRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_registered_table");
    }

    @Test
    void 首次注册插入_重复注册覆盖() {
        repository.upsert("t_a", "id,amount", "alice");

        assertThat(repository.tableNames()).containsExactly("t_a");
        assertThat(repository.find("t_a")).hasValueSatisfying(r -> {
            assertThat(r.columnsSnapshot()).isEqualTo("id,amount");
            assertThat(r.registeredBy()).isEqualTo("alice");
        });

        repository.upsert("t_a", "id,amount,extra", "bob");

        assertThat(repository.tableNames()).as("同一张表只有一行（当前状态，不是历史）").containsExactly("t_a");
        assertThat(repository.find("t_a")).hasValueSatisfying(r -> {
            assertThat(r.columnsSnapshot()).isEqualTo("id,amount,extra");
            assertThat(r.registeredBy()).isEqualTo("bob");
        });
    }

    @Test
    void 多张表按名字排序返回() {
        repository.upsert("t_b", "id", "alice");
        repository.upsert("t_a", "id", "alice");

        assertThat(repository.tableNames()).containsExactly("t_a", "t_b");
        assertThat(repository.find("t_missing")).isEmpty();
    }

    @Test
    void 列快照超过列宽时显式报错而不是静默截断() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            wide.append("very_long_column_name_").append(i).append(',');
        }
        // VARCHAR(2048) 装不下 —— 失败要抛出来，静默截断的快照会把审核人骗过去
        assertThatCode(() -> repository.upsert("t_wide", wide.toString(), "alice"))
                .as("插入应当因列宽超限而失败；若驱动不拦截，说明该环境的前提与本断言不符")
                .isInstanceOf(RuntimeException.class);
    }
}
