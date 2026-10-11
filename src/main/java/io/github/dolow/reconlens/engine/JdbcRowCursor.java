package io.github.dolow.reconlens.engine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 JDBC 的流式游标：一次只把 fetch-size 条记录读进内存，
 * 配合 SQL 的 ORDER BY 业务键，实现"内存占用与数据量无关"的归并式对账。
 * <p>MySQL 需要在连接串上加 useCursorFetch=true 才会按 fetchSize 分批取。
 */
public final class JdbcRowCursor implements RowCursor {

    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final int fetchSize;
    private final java.util.Set<String> columns = new java.util.LinkedHashSet<>();

    private Row pending;
    private boolean pendingReady;
    private long scanned;
    private boolean closed;

    public JdbcRowCursor(DataSource dataSource, String sql, int fetchSize) {
        this.fetchSize = fetchSize;
        Connection openedConnection = null;
        PreparedStatement openedStatement = null;
        ResultSet openedResultSet = null;
        try {
            openedConnection = dataSource.getConnection();
            openedStatement = openedConnection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            openedStatement.setFetchSize(fetchSize);
            openedResultSet = openedStatement.executeQuery();
            ResultSetMetaData meta = openedResultSet.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT));
            }
        } catch (SQLException | RuntimeException e) {
            // 构造函数里失败时对象不会被创建，try-with-resources 也就拿不到它来 close()：
            // 这里必须自己把已经借出来的连接还回连接池，否则每次"取数失败"都白占一条连接。
            closeQuietly(openedResultSet);
            closeQuietly(openedStatement);
            closeQuietly(openedConnection);
            throw new IllegalStateException("取数失败: " + sql, e);
        }
        this.connection = openedConnection;
        this.statement = openedStatement;
        this.resultSet = openedResultSet;
    }

    @Override
    public Row next() {
        if (!pendingReady) {
            pending = readNext();
            pendingReady = true;
        }
        Row row = pending;
        pending = null;
        pendingReady = false;
        return row;
    }

    @Override
    public Row peek() {
        if (!pendingReady) {
            pending = readNext();
            pendingReady = true;
        }
        return pending;
    }

    @Override
    public long scanned() {
        return scanned;
    }

    @Override
    public java.util.Set<String> columns() {
        return java.util.Collections.unmodifiableSet(columns);
    }

    private Row readNext() {
        if (closed) {
            return null;
        }
        try {
            if (!resultSet.next()) {
                return null;
            }
            scanned++;
            ResultSetMetaData meta = resultSet.getMetaData();
            int columnCount = meta.getColumnCount();
            Map<String, Object> values = new LinkedHashMap<>(columnCount * 2);
            for (int i = 1; i <= columnCount; i++) {
                values.put(meta.getColumnLabel(i), resultSet.getObject(i));
            }
            return Row.of(values);
        } catch (SQLException e) {
            throw new IllegalStateException("游标读取失败（fetchSize=" + fetchSize + "）", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(resultSet);
        closeQuietly(statement);
        closeQuietly(connection);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭失败不影响对账结果
        }
    }
}
