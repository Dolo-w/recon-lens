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

    public record Registration(String tableName, String columnsSnapshot, String registeredBy, java.time.Instant registeredAt) {
    }

    /** 幂等注册：同一张表重复注册就覆盖快照与登记人。 */
    public void upsert(String tableName, String columnsSnapshot, String registeredBy) {
        int updated = jdbc.update(
                "UPDATE recon_registered_table SET columns_snapshot = ?, registered_by = ?,"
                        + " registered_at = CURRENT_TIMESTAMP WHERE table_name = ?",
                columnsSnapshot, registeredBy, tableName);
        if (updated == 0) {
            jdbc.update("INSERT INTO recon_registered_table"
                            + " (table_name, columns_snapshot, registered_by, registered_at)"
                            + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    tableName, columnsSnapshot, registeredBy);
        }
    }

    /** 已注册的表名（闸门 1 的动态半边）。 */
    public List<String> tableNames() {
        return jdbc.queryForList("SELECT table_name FROM recon_registered_table ORDER BY table_name", String.class);
    }

    public Optional<Registration> find(String tableName) {
        List<Registration> rows = jdbc.query(
                "SELECT table_name, columns_snapshot, registered_by, registered_at"
                        + " FROM recon_registered_table WHERE table_name = ?",
                (rs, i) -> new Registration(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()),
                tableName);
        return rows.stream().findFirst();
    }
}
