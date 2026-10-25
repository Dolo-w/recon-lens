package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.chat.ChatSession;
import io.github.dolow.reconlens.chat.SessionHit;
import io.github.dolow.reconlens.chat.ChatTurn;
import io.github.dolow.reconlens.chat.SessionScope;
import io.github.dolow.reconlens.chat.Visibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话与轮次的落库（{@code recon_chat_session} / {@code recon_chat_turn}）。
 *
 * <p><b>这个类里没有任何一个不需要 {@link SessionScope} 的读方法</b>，包括内部用的
 * {@link #find}。少一个 {@code find(long)} 就少一次"临时查一下"的机会，这条收敛也就守住了
 * （ADR-0001 N8）。
 *
 * <p>没有 {@code @Repository} 注解：与 {@code store} 包其它仓库一致，由配置类手工 {@code @Bean} 装配。
 */
public class ChatSessionRepository {

    /**
     * 显式列名，不用 {@code SELECT *}：加列时 {@code SELECT *} 会让「列顺序」变成隐式契约，
     * 而这个项目已经在 {@code RegisteredTableRepository} 上踩过一次按位置取列、加列后错位的坑。
     */
    private static final String COLUMNS =
            "id, operator, previous_operator, visibility, title, state_json, state_rev, summary, summary_upto_seq,"
                    + " turn_count, status, created_at, last_at";

    private final JdbcTemplate jdbc;

    public ChatSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String operator, Visibility visibility, String title) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recon_chat_session (operator, visibility, title, turn_count, status, created_at, last_at)"
                            + " VALUES (?, ?, ?, 0, 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, operator);
            ps.setString(2, visibility.name());
            ps.setString(3, title);
            return ps;
        }, keys);
        return generatedId(keys);
    }

    public Optional<ChatSession> find(SessionScope scope, long sessionId) {
        List<ChatSession> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM recon_chat_session WHERE id = ? AND " + scope.readFilterSql(),
                (rs, rowNum) -> map(rs),
                concat(new Object[]{sessionId}, scope.readFilterParams()));
        return rows.stream().findFirst();
    }

    public List<ChatSession> recent(SessionScope scope, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM recon_chat_session WHERE " + scope.readFilterSql()
                        + " ORDER BY last_at DESC, id DESC LIMIT ?",
                (rs, rowNum) -> map(rs),
                concat(scope.readFilterParams(), new Object[]{limit}));
    }

    /**
     * 追加一轮。返回 {@code false} 表示"不是 owner"，一行都没写。
     *
     * <p>用条件 UPDATE 而不是先查后写：{@code WHERE id = ? AND operator = ?} 命中 0 行就是没权限，
     * 这是数据库替我们做的一次判定，中间没有"查完之后权限变了"的窗口。
     * {@code turn_count} 顺带自增，省掉一次额外的 count 查询。
     */
    public boolean appendTurn(SessionScope scope, long sessionId, int seq, String role,
                              String content, String toolCalls, Integer tokens) {
        int touched = jdbc.update(
                "UPDATE recon_chat_session SET turn_count = turn_count + 1, last_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND operator = ?",
                sessionId, scope.operator());
        if (touched == 0) {
            return false;
        }
        jdbc.update("INSERT INTO recon_chat_turn (session_id, seq, role, content, tool_calls, tokens, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                sessionId, seq, role, content, toolCalls, tokens);
        return true;
    }

    public List<ChatTurn> turns(long sessionId) {
        return turns(sessionId, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * 取一段轮次：{@code seq < beforeSeq} 里最靠后的 {@code limit} 条，返回时按 {@code seq} 升序。
     *
     * <p>为什么"取最靠后的一段"而不是从头翻：会话是越聊越长的，人要看的是最近说了什么；
     * 从头翻页在 200 轮之后要连点十几次才到今天。所以 {@code beforeSeq} 是"往前翻"的游标，
     * 缺省（{@code Integer.MAX_VALUE}）就是最后一页。
     *
     * <p>用 {@code ORDER BY seq DESC LIMIT ?} 再反转，而不是 {@code ASC LIMIT}：后者在长会话里
     * 要先把前面几百行读出来再丢掉，{@code DESC + LIMIT} 才是真的只读一页。
     */
    public List<ChatTurn> turns(long sessionId, int beforeSeq, int limit) {
        List<ChatTurn> page = jdbc.query(
                "SELECT id, session_id, seq, role, content, tool_calls, tokens, created_at"
                        + " FROM recon_chat_turn WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                (rs, rowNum) -> new ChatTurn(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        rs.getInt("seq"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("tool_calls"),
                        (Integer) rs.getObject("tokens"),
                        instant(rs.getTimestamp("created_at"))),
                sessionId, beforeSeq, limit);
        List<ChatTurn> ascending = new ArrayList<>(page.size());
        for (int i = page.size() - 1; i >= 0; i--) {
            ascending.add(page.get(i));
        }
        return ascending;
    }

    /**
     * 取一段轮次：{@code seq >= fromSeq} 里最靠前的 {@code limit} 条，按 {@code seq} 升序。
     *
     * <p>这是"从头看"的方向。只做 {@code before} 一种游标的话，长会话里想确认"第一条到底说了什么"
     * 得连点十几次；"往前翻"和"从头翻"是两种意图，硬用一个参数表达只会让人算游标算错。
     */
    public List<ChatTurn> turnsFrom(long sessionId, int fromSeq, int limit) {
        return jdbc.query(
                "SELECT id, session_id, seq, role, content, tool_calls, tokens, created_at"
                        + " FROM recon_chat_turn WHERE session_id = ? AND seq >= ? ORDER BY seq ASC LIMIT ?",
                (rs, rowNum) -> new ChatTurn(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        rs.getInt("seq"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("tool_calls"),
                        (Integer) rs.getObject("tokens"),
                        instant(rs.getTimestamp("created_at"))),
                sessionId, fromSeq, limit);
    }

    /** 某个位置之后还有没有轮次。翻到中间时前端要据此显示"回到最新"，猜是猜不准的。 */
    public boolean existsAfter(long sessionId, int seq) {
        Integer hit = jdbc.query(
                "SELECT 1 FROM recon_chat_turn WHERE session_id = ? AND seq > ? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                sessionId, seq);
        return hit != null;
    }

    /** 某个位置之前还有没有轮次（从头翻时据此显示"更早"）。 */
    public boolean existsBefore(long sessionId, int seq) {
        Integer hit = jdbc.query(
                "SELECT 1 FROM recon_chat_turn WHERE session_id = ? AND seq < ? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                sessionId, seq);
        return hit != null;
    }

    /**
     * 跨会话检索（ADR-0001 N9）。
     *
     * <p>SQL 里那个 {@code OR} 不是"顺手放宽"：
     * <ul>
     *   <li>{@code s.operator = ?} —— 自己的会话，恒可见；</li>
     *   <li>{@code s.previous_operator = ?} —— 自己交接出去的会话，还能看历史（写权已经跟着走了）；</li>
     *   <li>{@code s.visibility = 'SHARED'} —— 别人明确共享出来的。</li>
     * </ul>
     * 别人的 {@code PRIVATE} 与 {@code HANDOVER} 都进不来：{@code HANDOVER} 只对交接的两方可见，
     * 搜索不能当后门。
     *
     * <p>{@code team=false} 时只保留第一项——默认只搜自己，这是 N9 的硬要求。
     */
    public List<SessionHit> recall(SessionScope scope, List<String> terms, boolean team, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.session_id, t.seq, t.role, t.content, t.created_at,"
                        + " s.operator, s.visibility, s.title"
                        + " FROM recon_chat_turn t JOIN recon_chat_session s ON s.id = t.session_id"
                        + " WHERE ");
        List<Object> args = new ArrayList<>();
        if (team) {
            sql.append("(s.operator = ? OR s.previous_operator = ? OR s.visibility = 'SHARED')");
            args.add(scope.operator());
            args.add(scope.operator());
        } else {
            sql.append("s.operator = ?");
            args.add(scope.operator());
        }
        sql.append(" AND (");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("LOWER(t.content) LIKE ?");
            args.add("%" + terms.get(i).toLowerCase() + "%");
        }
        sql.append(") ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(limit);

        List<SessionHit> raw = jdbc.query(sql.toString(), (rs, rowNum) -> {
            String content = rs.getString("content");
            String lower = content == null ? "" : content.toLowerCase();
            List<String> matched = new ArrayList<>();
            for (String term : terms) {
                if (lower.contains(term.toLowerCase())) {
                    matched.add(term);
                }
            }
            return new SessionHit(rs.getLong("session_id"), rs.getString("operator"),
                    rs.getString("visibility"), rs.getString("title"), rs.getInt("seq"),
                    rs.getString("role"), content, instant(rs.getTimestamp("created_at")),
                    matched.size(), matched);
        }, args.toArray());

        // 命中词多的排前面；同分时保持 SQL 给的时间倒序（List.sort 是稳定排序）
        raw.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return raw;
    }

    /**
     * 写摘要与水位线。
     *
     * <p><b>不动 {@code last_at}</b>：这是后台压缩，不是"用户刚说过话"。若顺手更新，
     * 会话列表会因为它被重排到最上面，人看到的是"这条我没动过却跳上来了"。
     *
     * <p>条件里带 {@code operator = ?}：摘要也是会话内容的一部分，别人压不了我的会话语义。
     */
    public boolean saveSummary(SessionScope scope, long sessionId, String summary, int uptoSeq) {
        return jdbc.update("UPDATE recon_chat_session SET summary = ?, summary_upto_seq = ?"
                        + " WHERE id = ? AND operator = ?",
                summary, uptoSeq, sessionId, scope.operator()) > 0;
    }

    /**
     * 覆盖写槽位（{@code state_json}），带版本比较交换。
     *
     * <p><b>为什么要 CAS</b>：槽位记的是"现在在说哪条规则、哪个任务"。同一会话里两个人各问一句
     * （或者一个人手快连发两条），后写的那次会把先写的 {@code jobId} 盖掉——用户看到的现象是
     * "我刚跑完，接着问为什么，它却让我给任务号"。版本号让第二次写失败，由调用方决定重读还是放弃。
     *
     * <p>与 {@code appendTurn} 的区别是<b>可以覆盖</b>：槽位是焦点不是历史；轮次表才是只增不改的那份
     * （ADR-0001 D2 / D7）。
     */
    public boolean updateState(SessionScope scope, long sessionId, int expectedRev, String stateJson) {
        return jdbc.update("UPDATE recon_chat_session SET state_json = ?, state_rev = state_rev + 1,"
                        + " last_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND operator = ? AND state_rev = ?",
                stateJson, sessionId, scope.operator(), expectedRev) > 0;
    }

    public boolean setVisibility(SessionScope scope, long sessionId, Visibility visibility) {
        return jdbc.update("UPDATE recon_chat_session SET visibility = ?, last_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND operator = ?", visibility.name(), sessionId, scope.operator()) > 0;
    }

    /**
     * 交接：把 owner 改成 {@code toOperator}，原 owner 落到 {@code previous_operator}。
     *
     * <p>为什么保留原 owner：交接之后的会话仍然是一份审计材料，原 owner 应该还能回看自己说过什么；
     * 但<b>只有这两个人</b>能看（不是"谁都能看"）。
     *
     * <p>写完之后的读取必须换新 owner 的 scope 再来一遍——<b>不提供绕过 scope 的"查一次"</b>，
     * 否则这个类就成了隔离的缺口。
     */
    public boolean transfer(SessionScope scope, long sessionId, String toOperator) {
        return jdbc.update("UPDATE recon_chat_session SET previous_operator = operator, operator = ?,"
                        + " visibility = ?, last_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND operator = ?",
                toOperator, Visibility.HANDOVER.name(), sessionId, scope.operator()) > 0;
    }

    private static ChatSession map(ResultSet rs) throws SQLException {
        return new ChatSession(
                rs.getLong("id"),
                rs.getString("operator"),
                rs.getString("previous_operator"),
                Visibility.of(rs.getString("visibility")),
                rs.getString("title"),
                rs.getString("state_json"),
                rs.getInt("state_rev"),
                rs.getString("summary"),
                rs.getInt("summary_upto_seq"),
                rs.getInt("turn_count"),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_at")));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Object[] concat(Object[] first, Object[] second) {
        List<Object> all = new ArrayList<>(first.length + second.length);
        for (Object o : first) {
            all.add(o);
        }
        for (Object o : second) {
            all.add(o);
        }
        return all.toArray();
    }

    /**
     * 取自增主键。
     *
     * <p>不能用 {@code KeyHolder.getKey()}：表里有 {@code created_at DEFAULT CURRENT_TIMESTAMP}，
     * H2 会把这一列也当"生成列"返回，于是 {@code getKey()} 抛
     * {@code contains multiple keys} —— 所以先按列名 {@code ID} 逐个找（大小写不敏感，
     * 因为 H2 侧 {@code DATABASE_TO_LOWER=TRUE}），取不到再退回"只有一个键"的情况。
     * 与 {@code AgentRunRepository} 同一个坑，所以同一个解法。
     */
    private static long generatedId(KeyHolder keys) {
        Map<String, Object> map = keys.getKeys();
        if (map == null || map.isEmpty()) {
            throw new IllegalStateException("插入会话后没有拿到主键");
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if ("ID".equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        if (map.size() == 1) {
            Object only = map.values().iterator().next();
            if (only instanceof Number number) {
                return number.longValue();
            }
        }
        throw new IllegalStateException("插入会话后主键不唯一，且取不到 ID 列：" + map.keySet());
    }
}
