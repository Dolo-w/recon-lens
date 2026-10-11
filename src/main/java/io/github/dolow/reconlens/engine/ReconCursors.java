package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 取数游标工厂：把"规则 + 分片条件"变成一条按业务键升序的 SELECT，
 * 并用 JDBC fetchSize 做流式读取（MySQL 需要 useCursorFetch=true）。
 */
@Component
public class ReconCursors {

    private final DataSource dataSource;
    private final ReconProperties properties;

    public ReconCursors(DataSource dataSource, ReconProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    public RowCursor open(SideSpec side, String where) {
        String sql = ShardPlanner.selectSql(side, where);
        return new JdbcRowCursor(dataSource, sql, fetchSize());
    }

    public int fetchSize() {
        return Math.max(1, properties.getEngine().getFetchSize());
    }

    public String sqlOf(SideSpec side, String where) {
        return ShardPlanner.selectSql(side, where);
    }
}
