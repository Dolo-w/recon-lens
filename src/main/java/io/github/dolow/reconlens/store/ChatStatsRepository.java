package io.github.dolow.reconlens.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话侧的统计出口：<b>把「用户那句话被认成什么动作、用了哪个工具、有多少轮是空手回的」量出来</b>。
 *
 * <p>为什么要有这个类：P1 的验收指标（意图命中率、工具选择正确率、平均轮数、token 中位数）此前只在
 * 一次性的 golden set 脚本里算过，跑完就散了——线上真实会话用得对不对，没人答得上。
 *
 * <p>为什么用 {@code LIKE} 而不是 JSON 函数：H2 与 MySQL 的 JSON 函数不一致
 * （`JSON_EXTRACT` 在 H2 上要么没有、要么语义不同），而这一列是**我们自己写的固定格式信封**
 * （`{"intent":"RUN","tools":["recon_run"]}`）。前缀匹配在这个格式上是精确的，换成两个库都能跑的一句话，
 * 比"优雅但只在一种库上成立"值钱。代价写在这里：格式一改，这里要跟着改。
 */
@Component
public class ChatStatsRepository {

    private final JdbcTemplate jdbc;

    public ChatStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 会话与轮次的总体量。 */
    public Map<String, Object> totals() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", count("SELECT COUNT(*) FROM recon_chat_session", null));
        out.put("sessionsPrivate", count(
                "SELECT COUNT(*) FROM recon_chat_session WHERE visibility = 'PRIVATE'", null));
        out.put("sessionsShared", count(
                "SELECT COUNT(*) FROM recon_chat_session WHERE visibility = 'SHARED'", null));
        out.put("sessionsHandedOver", count(
                "SELECT COUNT(*) FROM recon_chat_session WHERE visibility = 'HANDOVER'", null));
        out.put("turns", count("SELECT COUNT(*) FROM recon_chat_turn", null));
        out.put("userTurns", count("SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'user'", null));
        out.put("assistantTurns", count(
                "SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'assistant'", null));
        out.put("summarized", count(
                "SELECT COUNT(*) FROM recon_chat_session WHERE summary_upto_seq > 0", null));
        return out;
    }

    /** 每个动作被认出来多少次。{@code intent} 为空 = 信封里没有这个键（老数据或表单轮次）。 */
    public Map<String, Long> byIntent(List<String> intents) {
        Map<String, Long> out = new LinkedHashMap<>();
        long known = 0;
        for (String intent : intents) {
            long n = count(
                    "SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'assistant' AND tool_calls LIKE ?",
                    "%\"intent\":\"" + intent + "\"%");
            out.put(intent, n);
            known += n;
        }
        long assistant = count(
                "SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'assistant'", null);
        out.put("UNKNOWN", Math.max(0, assistant - known));
        return out;
    }

    /** 每个工具被调到多少次（一轮调多个工具时每个都算一次）。 */
    public Map<String, Long> byTool(List<String> tools) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String tool : tools) {
            out.put(tool, count(
                    "SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'assistant' AND tool_calls LIKE ?",
                    "%\"" + tool + "\"%"));
        }
        return out;
    }

    /**
     * 「空手回」的轮次：助手答了话但一个工具都没调。
     *
     * <p>这是**反问率的上界**，不是精确值：有些空手回是合理的（HELP、审批说明），
     * 有些是路由没认出来、改成反问。看板上它只用来发现"某一类问题突然变多"，
     * 别拿它当准确率用。
     */
    public long emptyToolTurns() {
        return count(
                "SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'assistant' AND tool_calls LIKE ?",
                "%\"tools\":[]%");
    }

    /** 近 N 个会话的平均轮次（会话长度分布的一个粗印象）。 */
    public double avgTurnsPerSession() {
        Long sessions = count("SELECT COUNT(*) FROM recon_chat_session", null);
        if (sessions == null || sessions == 0) {
            return 0.0;
        }
        Long turns = count("SELECT COUNT(*) FROM recon_chat_turn WHERE role = 'user'", null);
        return Math.round((turns == null ? 0.0 : (double) turns / sessions) * 100.0) / 100.0;
    }

    private long count(String sql, String likeParam) {
        Long n = likeParam == null
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, likeParam);
        return n == null ? 0L : n;
    }
}
