package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 会话与逐步轨迹（{@code recon_agent_run} / {@code recon_agent_step}）。
 *
 * <p><b>为什么要落库而不是只打日志</b>：验收 A8 要求
 * {@code recon_agent_run.llm_calls} ↔ {@code recon_agent_step} ↔ {@code recon_rule.draft_reason}
 * ↔ {@code recon_job} 串得起来。串不起来的话，"这条规则是谁生成的、模型第几轮才改对、它当时看到了什么"
 * 就只剩一段日志 —— 而日志会滚动、会被采样、会在容器重启时消失。
 *
 * <p><b>不加 {@code @Repository}</b>：本类只被 agent 包调用，装配必须跟着
 * {@code recon.agent.enabled} 一起开关（同 {@code TrialSampleRepository}）。
 * 加了注解就会绕过 {@code @ConditionalOnProperty}，{@code AgentSwitchTest} 那条
 * "一个 Bean 都没有"立刻失效。
 *
 * <p><b>{@code payload} 与 {@code digest} 分开两列</b>：设计 v0.1 里叫 {@code output_digest} 却存内容，
 * 结果既不能按哈希比对、又不敢截断。现在 {@code payload} 存内容（可截断、有 {@code truncated} 标记），
 * {@code digest} 存真正的 sha256（不截断、可比对）。
 */
public class AgentRunRepository {

    /** 会话状态。BLOCKED 与 FAILED 的区别：前者是"按规则不该继续"（预算/闸门），后者是"出错了"。 */
    public enum RunStatus {
        RUNNING, SUCCESS, BLOCKED, FAILED
    }

    private final JdbcTemplate jdbc;

    public AgentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AgentRun(long id, String goal, String triggerType, String status, String planJson,
                           boolean planTruncated, String ruleCode, Long jobId, Integer draftVersion,
                           int llmCalls, int llmTokens, String errorMsg, String createdBy,
                           Instant createdAt, Instant finishedAt) {
    }

    public record AgentStep(long id, long runId, int seq, String phase, String action, String inputJson,
                            String payload, String digest, boolean truncated, String status,
                            Integer costMs, Instant createdAt) {
    }

    public long startRun(String goal, String triggerType, String createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recon_agent_run (goal, trigger_type, status, llm_calls, llm_tokens, created_by, created_at)"
                            + " VALUES (?,?,?,0,0,?,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, goal);
            ps.setString(2, triggerType);
            ps.setString(3, RunStatus.RUNNING.name());
            ps.setString(4, createdBy);
            return ps;
        }, keys);
        Number key = generatedId(keys);
        if (key == null) {
            throw new IllegalStateException("recon_agent_run 插入后拿不到主键，无法记录后续轨迹");
        }
        return key.longValue();
    }

    /**
     * 取自增主键。
     *
     * <p>不能用 {@code KeyHolder.getKey()}：表里有 {@code created_at DEFAULT CURRENT_TIMESTAMP}，
     * H2 会把这一列也当"生成列"返回，于是 {@code getKey()} 抛
     * {@code InvalidDataAccessApiUsageException: contains multiple keys} —— 单测里一次抓个正着。
     * 所以按列名取 {ID}，取不到再退回"只有一个键"的情况（MySQL 返回的是 GENERATED_KEY）。
     */
    private static Number generatedId(KeyHolder keys) {
        Map<String, Object> map = keys.getKeys();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if ("ID".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                return number;
            }
        }
        if (map.size() == 1) {
            Object only = map.values().iterator().next();
            if (only instanceof Number number) {
                return number;
            }
        }
        return null;
    }

    /**
     * 记一步。{@code seq} 由调用方按会话内递增给出，{@code uk_agent_step (run_id, seq)} 兜底：
     * 顺序错乱在复盘时等于"轨迹不可读"，而唯一索引是唯一能拦住重复 seq 的地方。
     */
    public void recordStep(long runId, int seq, String phase, String action, String inputJson,
                           String payload, String digest, boolean truncated, String status, Integer costMs) {
        jdbc.update("INSERT INTO recon_agent_step"
                        + " (run_id, seq, phase, action, input_json, payload, digest, truncated, status, cost_ms, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                runId, seq, phase, action, inputJson, payload, digest, truncated, status, costMs);
    }

    public int nextSeq(long runId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM recon_agent_step WHERE run_id = ?", Integer.class, runId);
        return (max == null ? 0 : max) + 1;
    }

    public void updatePlan(long runId, String planJson, boolean truncated) {
        jdbc.update("UPDATE recon_agent_run SET plan_json = ?, plan_truncated = ? WHERE id = ?",
                planJson, truncated, runId);
    }

    /**
     * 草稿一落库就把 (code, version) 挂上会话。
     *
     * <p>不能等到 {@code finishRun} 再写：试算可能跑 20 秒以上，这期间
     * {@code recon_trial_status} 会按 (code, version) 来问 —— 而库里查不到对应关系，
     * 调用方只能拿到"没有这个任务"。
     */
    public void updateDraft(long runId, String ruleCode, Integer draftVersion) {
        jdbc.update("UPDATE recon_agent_run SET rule_code = ?, draft_version = ? WHERE id = ?",
                ruleCode, draftVersion, runId);
    }

    public void bumpLlm(long runId, int calls, long tokens) {
        jdbc.update("UPDATE recon_agent_run SET llm_calls = llm_calls + ?, llm_tokens = llm_tokens + ? WHERE id = ?",
                calls, (int) tokens, runId);
    }

    public void finishRun(long runId, RunStatus status, String ruleCode, Long jobId, Integer draftVersion,
                          String errorMsg) {
        jdbc.update("UPDATE recon_agent_run SET status = ?, rule_code = ?, job_id = ?, draft_version = ?,"
                        + " error_msg = ?, finished_at = CURRENT_TIMESTAMP WHERE id = ?",
                status.name(), ruleCode, jobId, draftVersion, truncate(errorMsg, 1000), runId);
    }

    /**
     * 把库里残留的"排队中/进行中"会话标成 FAILED（进程刚启动时调用）。
     *
     * <p>异步任务没有心跳：重启后这些行既不会前进也不会有人收尾，留在 RUNNING 上
     * 会让运维以为"还在跑"。改成 FAILED 并把原因写清楚，至少状态是诚实的。
     *
     * @return 被标记的行数
     */
    /**
     * 把"很久以前开始、至今仍 RUNNING"的会话标成失败。
     *
     * <p>必须带上时间下限：Web 与 MCP 两个进程共用一个库时，MCP 进程此刻正在跑的试算，
     * 会被另一个进程启动时的清理误判成"上一次进程的遗留"。实测过：定义规则触发的试算明明成功、
     * 结论已经写进 {@code recon_rule.trial_*}，run 行却被另一个进程标成了 FAILED。
     *
     * @param olderThan 只清理在 {@code now - olderThan} 之前创建的行
     */
    public int failUnfinished(String reason, Duration olderThan) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(olderThan));
        return jdbc.update("UPDATE recon_agent_run SET status = ?, error_msg = ?, finished_at = CURRENT_TIMESTAMP"
                        + " WHERE status = ? AND created_at < ?",
                RunStatus.FAILED.name(), truncate(reason, 1000), RunStatus.RUNNING.name(), cutoff);
    }

    public Optional<AgentRun> findRun(long id) {
        List<AgentRun> rows = jdbc.query("SELECT * FROM recon_agent_run WHERE id = ?",
                (rs, rowNum) -> new AgentRun(
                        rs.getLong("id"),
                        rs.getString("goal"),
                        rs.getString("trigger_type"),
                        rs.getString("status"),
                        rs.getString("plan_json"),
                        rs.getBoolean("plan_truncated"),
                        rs.getString("rule_code"),
                        (Long) rs.getObject("job_id"),
                        (Integer) rs.getObject("draft_version"),
                        rs.getInt("llm_calls"),
                        rs.getInt("llm_tokens"),
                        rs.getString("error_msg"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("finished_at"))),
                id);
        return rows.stream().findFirst();
    }

    public List<AgentStep> listSteps(long runId) {
        return jdbc.query("SELECT * FROM recon_agent_step WHERE run_id = ? ORDER BY seq",
                (rs, rowNum) -> new AgentStep(
                        rs.getLong("id"),
                        rs.getLong("run_id"),
                        rs.getInt("seq"),
                        rs.getString("phase"),
                        rs.getString("action"),
                        rs.getString("input_json"),
                        rs.getString("payload"),
                        rs.getString("digest"),
                        rs.getBoolean("truncated"),
                        rs.getString("status"),
                        (Integer) rs.getObject("cost_ms"),
                        toInstant(rs.getTimestamp("created_at"))),
                runId);
    }

    /**
     * 审计用：按状态与时间窗筛运行记录。
     *
     * <p>三个条件都可空——审计是"把账翻出来看"，不该逼人先知道要筛什么。
     * 时间窗是左闭右开，避免两个相邻窗口把同一条记录算两次。
     */
    public List<AgentRun> runs(String status, java.time.Instant from, java.time.Instant to, int limit) {
        int size = limit <= 0 ? 200 : Math.min(limit, 2000);
        StringBuilder sql = new StringBuilder("SELECT * FROM recon_agent_run WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(size);
        return jdbc.query(sql.toString(), this::mapRun, args.toArray());
    }

    private AgentRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Timestamp finished = rs.getTimestamp("finished_at");
        return new AgentRun(
                rs.getLong("id"),
                rs.getString("goal"),
                rs.getString("trigger_type"),
                rs.getString("status"),
                rs.getString("plan_json"),
                rs.getBoolean("plan_truncated"),
                rs.getString("rule_code"),
                (Long) rs.getObject("job_id", Long.class),
                (Integer) rs.getObject("draft_version", Integer.class),
                rs.getInt("llm_calls"),
                rs.getInt("llm_tokens"),
                rs.getString("error_msg"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }

    public List<AgentRun> recentRuns(int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, 200);
        return jdbc.query("SELECT * FROM recon_agent_run ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> new AgentRun(
                        rs.getLong("id"),
                        rs.getString("goal"),
                        rs.getString("trigger_type"),
                        rs.getString("status"),
                        null,
                        rs.getBoolean("plan_truncated"),
                        rs.getString("rule_code"),
                        (Long) rs.getObject("job_id"),
                        (Integer) rs.getObject("draft_version"),
                        rs.getInt("llm_calls"),
                        rs.getInt("llm_tokens"),
                        rs.getString("error_msg"),
                        rs.getString("created_by"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("finished_at"))),
                size);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
