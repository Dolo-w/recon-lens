package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采样与脱敏的边界：窗口是窗口、比例是估算、样例默认打码。
 *
 * <p>最要紧的一条不是"能不能算出来"，而是<b>默认不读数据行</b>：
 * {@code describe} 只走元数据，只有显式 {@code sample=true} 才读窗口。
 */
class TableProfileTest {

    private TableInspector inspector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:table_profile;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS prof_customer");
        jdbc.execute("""
                CREATE TABLE prof_customer (
                  id BIGINT NOT NULL PRIMARY KEY,
                  name VARCHAR(32),
                  phone VARCHAR(16),
                  city VARCHAR(32)
                )
                """);
        jdbc.update("INSERT INTO prof_customer VALUES (1,'张三','13812345678','北京')");
        jdbc.update("INSERT INTO prof_customer VALUES (2,'李四','13900001111','上海')");
        jdbc.update("INSERT INTO prof_customer VALUES (3,NULL,'13700002222','北京')");
        jdbc.update("INSERT INTO prof_customer VALUES (4,'王五',NULL,NULL)");
        inspector = new TableInspector(jdbc, new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(RegisteredTableRepository.class));
    }

    @Test
    @DisplayName("默认样例打码_名字与手机都不露原文")
    void 默认脱敏() {
        TableInspector.Profile profile = inspector.profile("prof_customer", 100, 3, false);

        TableInspector.ColumnStat name = byName(profile, "name");
        assertThat(name.samples()).contains("张*").doesNotContain("张三");
        assertThat(name.masked()).isTrue();

        TableInspector.ColumnStat phone = byName(profile, "phone");
        assertThat(phone.samples()).contains("138****5678").doesNotContain("13812345678");
    }

    @Test
    @DisplayName("显式开原文才给原文")
    void 原文开关() {
        TableInspector.Profile profile = inspector.profile("prof_customer", 100, 3, true);

        assertThat(byName(profile, "name").samples()).contains("张三");
        assertThat(byName(profile, "name").masked()).isFalse();
    }

    @Test
    @DisplayName("空值率与不同值个数在窗口上算_并标明窗口大小")
    void 空值率与不同值() {
        TableInspector.Profile profile = inspector.profile("prof_customer", 100, 0, false);

        assertThat(profile.window()).isEqualTo(4);
        // phone 有一行 null → 空值率 0.25
        assertThat(byName(profile, "phone").nullRate()).isEqualTo(0.25);
        // city 两个不同值（北京出现两次）；null 不计入不同值
        assertThat(byName(profile, "city").distinct()).isEqualTo(2);
        // name 三种（含 null 的 1 行不算进不同值）
        assertThat(byName(profile, "name").distinct()).isEqualTo(3);
    }

    @Test
    @DisplayName("窗口限流_超出上限按上限算")
    void 窗口上限() {
        TableInspector.Profile profile = inspector.profile("prof_customer", 99_999, 0, false);

        assertThat(profile.window()).isEqualTo(4); // 表只有 4 行；上限是 2000，不会越界
        assertThat(TableInspector.MAX_WINDOW).isEqualTo(2_000);
    }

    @Test
    @DisplayName("数字按数值比大小_不是按字符串")
    void 极值比较() {
        TableInspector.Profile profile = inspector.profile("prof_customer", 100, 0, false);

        assertThat(byName(profile, "id").min()).isEqualTo("1");
        assertThat(byName(profile, "id").max()).isEqualTo("4");
    }

    @Test
    @DisplayName("坏表名进不了采样SQL")
    void 表名校验() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inspector.profile("x; DROP TABLE y", 10, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名只能由字母");
    }

    private static TableInspector.ColumnStat byName(TableInspector.Profile profile, String name) {
        return profile.stats().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
