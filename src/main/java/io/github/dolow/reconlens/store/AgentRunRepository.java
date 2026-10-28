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
        RUNNING, SUCCESS, PARTIAL, BLOCKED, FAILED
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
                            Integer costMs, Integer tokensEst, Instant createdAt) {
    }

    public long startRun(String goal, String triggerType, String createdBy) {
        return startRun(goal, null, triggerType, createdBy);
    }

    /**
     * @param goalJson 结构化目标（{@code AgentGoal} 的 JSON）；可为 null（旧入口）。
     *                 恢复长任务时要靠它还原 intent —— 只有 {@code goal} 文本会把
     *                 "只起草"和"真的跑一次对账"混成一件事。
     */
    public long startRun(String goal, String goalJson, String triggerType, String createdBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recon_agent_run (goal, goal_json, trigger_type, status, llm_calls, llm_tokens, created_by, created_at)"
                            + " VALUES (?,?,?,?,0,0,?,CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, goal);
            ps.setString(2, goalJson);
            ps.setString(3, triggerType);
            ps.setString(4, RunStatus.RUNNING.name());
            ps.setString(5, createdBy);
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
     *
     * <p>{@code costMs} 是这一步的墙钟耗时（早就有了，列名 {@code cost_ms}，别被名字骗了——
     * 它量的是时间不是钱）；{@code tokensEst} 是这一步上下文的估算 token（口径见
     * {@link io.github.dolow.reconlens.llm.Tokens}，是估算不是账单上的数）。
     */
    public void recordStep(long runId, int seq, String phase, String action, String inputJson,
                           String payload, String digest, boolean truncated, String status, Integer costMs,
                           Integer tokensEst) {
        jdbc.update("INSERT INTO recon_agent_step"
                        + " (run_id, seq, phase, action, input_json, payload, digest, truncated, status, cost_ms,"
                        + " tokens_est, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                runId, seq, phase, action, inputJson, payload, digest, truncated, status, costMs, tokensEst);
    }

    /**
     * 启动巡检用：进程上次退出时还没收尾的会话。
     *
     * <p>只取 {@code RUNNING}。{@code FAILED} 刻意不取 —— 那是"巡检已经标过、等人决定要不要救"的状态，
     * 自动重试会把「一直失败的会话」变成每次重启都撞一次墙。
     */
    public List<Long> unfinishedRunIds() {
        return jdbc.query("SELECT id FROM recon_agent_run WHERE status = 'RUNNING' ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"));
    }

    /**
     * 启动恢复用：进程启动<b>之前</b>创建、至今仍 {@code RUNNING} 的会话。
     *
     * <p>为什么要时间下限：端口在 {@code ApplicationReadyEvent} 之前就已经在监听，
     * 一条刚由本进程受理的会话完全可能是<b>此刻正在跑</b>的。不加这层过滤，
     * 启动恢复会跟它自己刚发起的运行并行跑同一个会话，任务与试算都会重复。
     *
     * <p>跨进程的残余风险：Web 与 MCP 两个进程共用一个库时，另一个进程"此刻正在跑"的会话，
     * 仍可能被判成遗留。当前部署边界是单实例（见 {@code docs/architecture.md}），
     * 真要上多实例，这里得换成带租约的认领。
     *
     * @param before 只取在该时刻之前创建的会话（通常是本进程启动时刻）
     */
    public List<Long> unfinishedRunIds(Instant before) {
        return jdbc.query("SELECT id FROM recon_agent_run WHERE status = 'RUNNING' AND created_at < ? ORDER BY id",
                (rs, rowNum) -> rs.getLong("id"), Timestamp.from(before));
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
     * <p><b>这里曾经有个"把残留会话标成 FAILED"的方法，已删除</b>：它把已经落库的草稿、
     * 已经跑完的试算一起判成没发生过 —— 而那正是恢复要保住的东西。现在"收尾"由
     * {@code AgentRunRecovery} 接着做（见 {@link #unfinishedRunIds(java.time.Instant)}），
     * 库层面不再提供"强制失败"的入口。
     */

    /**
     * 读结构化目标。单独一个方法而不是塞进 {@link AgentRun}：恢复是低频操作，
     * 为了它给每一条审计查询都多带一列 JSON 不划算。
     */
    public Optional<String> goalJson(long runId) {
        List<String> rows = jdbc.query("SELECT goal_json FROM recon_agent_run WHERE id = ?",
                (rs, rowNum) -> rs.getString("goal_json"), runId);
        // 列可空，旧记录全是 NULL：先滤掉再 findFirst —— 直接把 null 放进 Optional 会 NPE。
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
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

    public List<AgentStep> listSteps(long runId) {        return jdbc.query("SELECT * FROM recon_agent_step WHERE run_id = ? ORDER BY seq",
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
                        (Integer) rs.getObject("tokens_est"),
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
