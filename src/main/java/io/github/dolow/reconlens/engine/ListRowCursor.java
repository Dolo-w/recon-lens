package io.github.dolow.reconlens.engine;

import java.util.ArrayList;
import java.util.List;

/** 内存游标：单元测试与自定义数据源用，生产路径走 JdbcRowCursor。 */
public final class ListRowCursor implements RowCursor {

    private final List<Row> rows;
    private int index = -1;
    private boolean peeked;

    public ListRowCursor(List<Row> rows) {
        this.rows = new ArrayList<>(rows);
    }

    @Override
    public Row next() {
        if (!peeked) {
            index++;
        }
        peeked = false;
        return index < rows.size() ? rows.get(index) : null;
    }

    @Override
    public Row peek() {
        if (!peeked) {
            index++;
            peeked = true;
        }
        return index < rows.size() ? rows.get(index) : null;
    }

    @Override
    public long scanned() {
        return Math.max(index, 0);
    }

    @Override
    public java.util.Set<String> columns() {
        java.util.Set<String> columns = new java.util.LinkedHashSet<>();
        rows.forEach(row -> columns.addAll(row.values().keySet()));
        return columns;
    }

    @Override
    public void close() {
        // 无资源可释放
    }
}
