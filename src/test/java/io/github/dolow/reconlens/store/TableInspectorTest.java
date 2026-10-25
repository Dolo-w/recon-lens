package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 看表结构这一件事的边界：
 * 列得对、主键认得出来、行数说得清来源、坏表名进不了 SQL、表不存在时不糊弄。
 */
class TableInspectorTest {

    private JdbcTemplate jdbc;
    private TableInspector inspector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:table_inspector;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS insp_orders");
        jdbc.execute("""
                CREATE TABLE insp_orders (
                  id BIGINT NOT NULL PRIMARY KEY,
                  order_no VARCHAR(64) NOT NULL,
                  amount DECIMAL(18,2),
                  paid_at TIMESTAMP,
                  remark VARCHAR(200)
                )
                """);
        jdbc.update("INSERT INTO insp_orders (id, order_no, amount) VALUES (1, 'A', 10.00), (2, 'B', 20.00)");
        // registry 缺席：模拟"注册表没这张表"，此时行数必须现算并标明来源
        inspector = new TableInspector(jdbc, new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(RegisteredTableRepository.class));
    }

    @Test
    @DisplayName("列名_类型_可空都对得上")
    void 列名与类型() {
        TableInspector.Detail detail = inspector.describe("insp_orders", 80);

        assertThat(detail.columns()).hasSize(5);
        TableInspector.Column id = detail.columns().get(0);
        assertThat(id.name()).isEqualTo("id");
        assertThat(id.nullable()).isFalse();
        TableInspector.Column orderNo = detail.columns().get(1);
        assertThat(orderNo.name()).isEqualTo("order_no");
        assertThat(orderNo.type()).containsIgnoringCase("CHAR");
        assertThat(orderNo.nullable()).isFalse();
        // 金额列带精度
        assertThat(detail.columns().get(2).type()).contains("(");
    }

    @Test
    @DisplayName("主键认得出来_并且标在对应的列上")
    void 主键() {
        TableInspector.Detail detail = inspector.describe("insp_orders", 80);

        assertThat(detail.primaryKey()).containsExactly("id");
        assertThat(detail.columns().get(0).primaryKey()).isTrue();
        assertThat(detail.columns().get(1).primaryKey()).isFalse();
    }

    @Test
    @DisplayName("没有注册快照时现算行数_并标明来源是LIVE")
    void 行数来源() {
        TableInspector.Detail detail = inspector.describe("insp_orders", 80);

        assertThat(detail.rowCount()).isEqualTo(2L);
        assertThat(detail.rowSource()).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("列太多时截断并说明_不是静默丢列")
    void 宽表截断() {
        TableInspector.Detail detail = inspector.describe("insp_orders", 2);

        assertThat(detail.columns()).hasSize(2);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("坏表名拼不进SQL")
    void 表名注入被拒() {
        assertThat(TableInspector.safeName("bill_left")).isTrue();
        assertThat(TableInspector.safeName("bill_left; DROP TABLE x")).isFalse();
        assertThat(TableInspector.safeName("bill-left")).isFalse();
        assertThatThrownBy(() -> inspector.describe("x; DELETE FROM y", 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名只能由字母");
    }

    @Test
    @DisplayName("表不存在时说人话_不抛数据库异常")
    void 表不存在() {
        assertThatThrownBy(() -> inspector.describe("no_such_table_here", 80))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
