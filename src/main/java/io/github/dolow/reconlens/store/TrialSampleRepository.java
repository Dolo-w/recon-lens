package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 试算样本表的租约登记：{@code recon_trial_sample} 一行代表"一对样本表正在被谁使用"。
 *
 * <h2>它要解决的不是"清理"，是"清理别删错"</h2>
 * 原先的回收只认表名前缀（{@code trial!_%}），在单实例下成立 —— 那时库里的样本表要么是本次进程刚建的，
 * 要么确实是谁崩溃后留下的。多实例下这个推论断了：A 建好的表对 B 来说长得和残留一模一样，
 * 而 B 启动时扫一遍就会把它 DROP，接着 A 在"表不存在"上失败。
 *
 * <h2>为什么租约按 run_id 记，而不是挂在 recon_job 上</h2>
 * 样本表在 job 之前就建好了（CTAS 先于 {@code runTraced}），建表后崩溃的话没有任何 job 指向它；
 * 而一次试算有 {@code TRIAL}×2 + {@code CANARY} 三个 job 共用同一对表，按 job 判归属连
 * "这对表属于谁"都说不清。<b>表属于"这次试算"</b>，所以租约也按 {@code run_id} 记。
 *
 * <h2>心跳为什么由工作节点推进，而不是守护线程</h2>
 * 守护线程活着只证明<b>进程</b>活着；我们要的是"<b>这次试算</b>还在推进"。
 * 进程活着但卡在 GC 或网络 await 上时，恰恰是最不该被当成"还有人在用"的那段时间。
 */
public class TrialSampleRepository {

    private final JdbcTemplate jdbc;
    private final String owner;

    /**
     * @param owner 实例标识，只用于排障时回答"这摊残留是谁的"；判活用的是 {@code heartbeat_at}
     */
    public TrialSampleRepository(JdbcTemplate jdbc, String owner) {
        this.jdbc = jdbc;
        this.owner = owner;
    }

    /** 一行租约。{@code heartbeatAt} 是最后一次"我还在用"的证据。 */
    public record Lease(String runId, String tableLeft, String tableRight, String owner, Instant heartbeatAt) {
    }

    /**
     * 登记这次试算。<b>必须在建表之前调用</b> —— 顺序反了就会留下"表存在但无登记"的窗口，
     * 而那正是孤儿回收要删的东西。
     */
    public void register(String runId, String tableLeft, String tableRight, Instant now) {
        jdbc.update("INSERT INTO recon_trial_sample (run_id, table_left, table_right, owner, heartbeat_at)"
                        + " VALUES (?,?,?,?,?)",
                runId, tableLeft, tableRight, owner, Timestamp.from(now));
    }

    /**
     * 续租。
     *
     * @throws IllegalStateException 登记行已经不在了 —— 说明这次试算的表随时可能被另一实例当成残留删掉，
     *                               此时继续跑得到的结果不可信，所以<b>立刻中止</b>而不是记一条 warn 往下走
     */
    public void heartbeat(String runId, Instant now) {
        int updated = jdbc.update("UPDATE recon_trial_sample SET heartbeat_at = ? WHERE run_id = ?",
                Timestamp.from(now), runId);
        if (updated == 0) {
            throw new IllegalStateException("试算样本表的租约已丢失（run_id=" + runId
                    + "）：它已被另一个实例的清理任务回收，本次试算的表可能正在被删除，结论不可信");
        }
    }

    /** 注销。<b>必须在 DROP 表之后调用</b>：登记行的含义是"表在用"，表没了登记就该没。 */
    public void unregister(String runId) {
        jdbc.update("DELETE FROM recon_trial_sample WHERE run_id = ?", runId);
    }

    /** 心跳早于 {@code cutoff} 的租约：拥有者已经不再推进，可以回收。 */
    public List<Lease> expired(Instant cutoff) {
        return jdbc.query("SELECT run_id, table_left, table_right, owner, heartbeat_at"
                        + " FROM recon_trial_sample WHERE heartbeat_at < ?",
                (rs, rowNum) -> new Lease(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                Timestamp.from(cutoff));
    }

    /**
     * 这张表是否还被某条租约覆盖。
     *
     * <p>用来区分"孤儿"与"正在用"：新代码一定是<b>先登记再建表</b>（单条 INSERT 自动提交，先于 CTAS 完成），
     * 所以"表存在、登记不存在"只可能是租约机制上线之前留下的老残留 —— 对它而言，
     * "没有登记"就是"没有活人在用"，删掉是安全的。
     */
    public boolean isRegistered(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_trial_sample WHERE LOWER(table_left) = LOWER(?)"
                        + " OR LOWER(table_right) = LOWER(?)",
                Integer.class, table, table);
        return count != null && count > 0;
    }

    public String owner() {
        return owner;
    }

    /** 实例标识：主机名 + 随机后缀（重启后后缀会变，这是有意的 —— 旧标识的租约应当自然过期）。 */
    public static String defaultOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        if (host.length() > 60) {
            host = host.substring(0, 60);
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
