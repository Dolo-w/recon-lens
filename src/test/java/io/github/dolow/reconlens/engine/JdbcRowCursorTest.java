package io.github.dolow.reconlens.engine;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 游标借出来的连接必须还回去 —— 尤其是"构造函数里就失败"这条路径：
 * 那时对象还没创建成功，try-with-resources 拿不到它，close() 永远不会被调用。
 * <p>池子里只放 1 条连接：如果失败时没归还，后面的 getConnection() 只能等到超时报错。
 */
class JdbcRowCursorTest {

    private HikariDataSource pool(int size, long timeoutMillis) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:cursor-leak;MODE=MySQL;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(size);
        config.setConnectionTimeout(timeoutMillis);
        return new HikariDataSource(config);
    }

    @Test
    void failedOpenReleasesTheBorrowedConnection() throws Exception {
        try (HikariDataSource dataSource = pool(1, 2000)) {
            assertThatThrownBy(() -> new JdbcRowCursor(dataSource, "SELECT nope FROM nowhere", 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("取数失败");

            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.isValid(1)).isTrue();
            }
        }
    }

    @Test
    void cursorReadsAndClosesTheConnection() throws Exception {
        try (HikariDataSource dataSource = pool(1, 2000)) {
            RowCursor cursor = new JdbcRowCursor(dataSource, "SELECT 1 AS id", 100);
            assertThat(cursor.columns()).contains("id");
            assertThat(cursor.next()).isNotNull();
            assertThat(cursor.next()).isNull();
            cursor.close();

            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.isValid(1)).isTrue();
            }
        }
    }
}
