package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 动态表注册（{@code recon_registered_table}）：闸门 1 白名单的"运行时半边"。
 *
 * <p>白名单 = 配置（{@code recon.agent.schema.tables}）∪ 这里登记的表。为什么落库而不是改配置：
 * <ol>
 *   <li>注册是对话里的动作，要求"改配置重启"等于把新用户挡在门外；</li>
 *   <li>Web 实例与 MCP 进程是两个 JVM，只有共享库能让两边的闸门看到同一份白名单；</li>
 *   <li>{@code registered_by}/{@code registered_at} 是审计 —— 谁在什么时候把哪张表放进了闸门。</li>
 * </ol>
 *
 * <p><b>不加 {@code @Repository}</b>：只被 agent 包调用，装配必须跟着
 * {@code recon.agent.enabled} 一起开关（同 {@code AgentRunRepository}）。
 *
 * <p><b>每个表名只有一行（upsert 覆盖）</b>：它存的是"当前状态"，不是注册历史。
 * 重复注册同一张表 = 刷新列快照与登记人 —— 表结构演进后重新确认一次，闸门看的就是新结构。
 */
public class RegisteredTableRepository {

    private final JdbcTemplate jdbc;

    public RegisteredTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Registration(String tableName, String columnsSnapshot, String schemaHash, Long rowCount,
                               String registeredBy, java.time.Instant registeredAt) {

        public java.util.List<String> columns() {
            return columnsSnapshot == null || columnsSnapshot.isBlank()
                    ? java.util.List.of()
                    : java.util.List.of(columnsSnapshot.split(","));
        }
    }

    /**
     * 幂等注册：同一张表重复注册就覆盖快照与登记人。
     *
     * <p>指纹（{@code schema_hash} + {@code row_count}）一起写：<b>注册那一刻</b>的结构与行数，
     * 是之后判断"这份数据换过没有"的基准（ADR-0002 D3）。行数拿不到（权限/方言）时写 null，
     * 不因此挡住注册 —— 但快照里会明确是 null，而不是 0。
     */
    public void upsert(String tableName, String columnsSnapshot, String schemaHash, Long rowCount, String registeredBy) {
        int updated = jdbc.update(
                "UPDATE recon_registered_table SET columns_snapshot = ?, schema_hash = ?, row_count = ?,"
                        + " registered_by = ?, registered_at = CURRENT_TIMESTAMP WHERE table_name = ?",
                columnsSnapshot, schemaHash, rowCount, registeredBy, tableName);
        if (updated == 0) {
            jdbc.update("INSERT INTO recon_registered_table"
                            + " (table_name, columns_snapshot, schema_hash, row_count, registered_by, registered_at)"
                            + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    tableName, columnsSnapshot, schemaHash, rowCount, registeredBy);
        }
    }

    /** 已注册的表名（闸门 1 的动态半边）。 */
    public List<String> tableNames() {
        return jdbc.queryForList("SELECT table_name FROM recon_registered_table ORDER BY table_name", String.class);
    }

    /** 全部已注册的表（含注册那一刻的列快照、指纹、行数与登记人）。 */
    public List<Registration> list() {
        return jdbc.query(
                "SELECT table_name, columns_snapshot, schema_hash, row_count, registered_by, registered_at"
                        + " FROM recon_registered_table ORDER BY table_name",
                (rs, i) -> new Registration(rs.getString("table_name"), rs.getString("columns_snapshot"),
                        rs.getString("schema_hash"),
                        rs.getObject("row_count") == null ? null : rs.getLong("row_count"),
                        rs.getString("registered_by"),
                        rs.getTimestamp("registered_at") == null ? null : rs.getTimestamp("registered_at").toInstant()));
    }

    /**
     * 按<b>列名</b>取，不按位置取。
     *
     * <p>这里踩过一次：上一版用 {@code rs.getString(1..5)}，加一列就整体错位，而且错得"像有数据"。
     * 加列是这类表最常见的变化，按位置取等于给自己埋雷。
     */
    public Optional<Registration> find(String tableName) {
        List<Registration> rows = jdbc.query(
                "SELECT table_name, columns_snapshot, schema_hash, row_count, registered_by, registered_at"
                        + " FROM recon_registered_table WHERE table_name = ?",
                (rs, i) -> new Registration(rs.getString("table_name"), rs.getString("columns_snapshot"),
                        rs.getString("schema_hash"),
                        rs.getObject("row_count") == null ? null : rs.getLong("row_count"),
                        rs.getString("registered_by"),
                        rs.getTimestamp("registered_at") == null ? null : rs.getTimestamp("registered_at").toInstant()),
                tableName);
        return rows.stream().findFirst();
    }
}
