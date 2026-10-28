package io.github.dolow.reconlens.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ChatSessionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 槽位写入的并发语义（M13）。
 *
 * <p>槽位是"这一层唯一带状态的东西"：模型靠它理解"再看一下它""还是那条"。
 * 修复前的写法是 {@code saveSlotsWithRetry} 失败后**只重试一次**，而且是拿旧 rev 再碰一次运气；
 * 两次都撞车就 {@code WARN} 一句放弃——用户看到的是"我刚说的规则号它好像没记住"，
 * 而日志里只是一行警告。修复后最多试 3 次，每次冲突都回读最新状态、
 * 用 {@link ChatSlots#mergeWith} 把"我这一轮写的字段"和"别人已经写进去的字段"合起来。
 *
 * <p>这个测试不启 Spring，直接打真 {@code schema.sql}：会话表是后加的，
 * 手写 DDL 只能证明"我写的 DDL 和我的代码一致"。
 */
class ConcurrentSlotWriteTest {

    private static JdbcTemplate jdbc;
    private static ChatSessionService service;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void setUpSchema() throws Exception {
        String url = "jdbc:h2:mem:concurrent_slot_write;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        service = new ChatSessionService(new ChatSessionRepository(jdbc), new AgentRunRepository(jdbc));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_chat_turn");
        jdbc.update("DELETE FROM recon_chat_session");
    }

    @Test
    void 同一个rev的两次写入只能成功一次() {
        SessionScope scope = SessionScope.of("alice");
        ChatSession session = service.open(scope, "并发写入");

        assertThat(service.saveSlots(scope, session.id(), session.stateRev(), json(ChatSlots.EMPTY.withJobId(7L))))
                .isTrue();
        // 第二个请求拿着同一个 rev（它读到的就是那个 rev）来写，必须被拒
        assertThat(service.saveSlots(scope, session.id(), session.stateRev(), json(ChatSlots.EMPTY.withRuleCode("R1"))))
                .isFalse();
        assertThat(service.require(scope, session.id()).stateRev()).isEqualTo(session.stateRev() + 1);
    }

    @Test
    void 冲突之后合并_对方刚写的焦点不丢_我这一轮写的字段也生效() {
        SessionScope scope = SessionScope.of("alice");
        ChatSession session = service.open(scope, "并发合并");

        // A：第一个请求先落地，记住了"刚才看的是任务 7"
        assertThat(service.saveSlots(scope, session.id(), session.stateRev(), json(ChatSlots.EMPTY.withJobId(7L))))
                .isTrue();

        // B：它开始得比 A 早，手里还是空槽位；这一轮用户说的是"换成 R1 那条规则"
        ChatSlots bStart = ChatSlots.EMPTY;
        ChatSlots bMine = bStart.withRuleCode("R1");

        // 修复前的做法：拿旧 rev 再试一次 → 再失败 → 放弃，B 的 R1 丢了
        // 修复后的做法：回读最新状态，再按"本轮写过的字段优先"合并
        ChatSession fresh = service.require(scope, session.id());
        ChatSlots merged = bMine.mergeWith(jsonToSlots(fresh.stateJson()));
        assertThat(merged.jobId()).isEqualTo(7L);
        assertThat(merged.ruleCode()).isEqualTo("R1");

        assertThat(service.saveSlots(scope, session.id(), fresh.stateRev(), json(merged))).isTrue();
        ChatSlots stored = jsonToSlots(service.require(scope, session.id()).stateJson());
        assertThat(stored.jobId()).isEqualTo(7L);
        assertThat(stored.ruleCode()).isEqualTo("R1");
    }

    private static String json(ChatSlots slots) {
        try {
            return JSON.writeValueAsString(slots);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatSlots jsonToSlots(String payload) {
        try {
            return payload == null || payload.isBlank() ? ChatSlots.EMPTY : JSON.readValue(payload, ChatSlots.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
