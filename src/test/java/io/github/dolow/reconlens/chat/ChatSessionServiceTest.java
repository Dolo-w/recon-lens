package io.github.dolow.reconlens.chat;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话隔离与交接（ADR-0001 D8 / N8）。
 *
 * <p>这里直接跑真实的 {@code schema.sql}，不手写建表：会话表是这次新加的，
 * 手写 DDL 只会证明"我写的 DDL 和我写的代码一致"，证明不了"仓库里的 schema 跑得起来"。
 */
class ChatSessionServiceTest {

    private static JdbcTemplate jdbc;
    private static ChatSessionService service;

    @BeforeAll
    static void setUpSchema() throws Exception {
        String url = "jdbc:h2:mem:chat_session_service;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
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
        jdbc.update("DELETE FROM recon_agent_step");
        jdbc.update("DELETE FROM recon_agent_run");
    }

    @Test
    void 新建会话默认私有且归创建人() {
        ChatSession session = service.open(SessionScope.of("alice"), "8 月账单对不平");

        assertThat(session.operator()).isEqualTo("alice");
        assertThat(session.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(session.turnCount()).isZero();
        assertThat(session.title()).isEqualTo("8 月账单对不平");
    }

    @Test
    void 别人的私有会话_读不到且报不存在() {
        ChatSession alice = service.open(SessionScope.of("alice"), "alice 的会话");

        assertThatThrownBy(() -> service.require(SessionScope.of("bob"), alice.id()))
                .isInstanceOf(SessionNotFoundException.class);
        assertThat(service.recent(SessionScope.of("bob"), 20)).isEmpty();
    }

    @Test
    void 共享之后看得见但写不进去() {
        ChatSession alice = service.open(SessionScope.of("alice"), "要交接的会话");
        service.share(SessionScope.of("alice"), alice.id(), Visibility.SHARED);

        ChatSession seenByBob = service.require(SessionScope.of("bob"), alice.id());
        assertThat(seenByBob.id()).isEqualTo(alice.id());
        assertThat(service.recent(SessionScope.of("bob"), 20)).hasSize(1);

        assertThatThrownBy(() -> service.append(SessionScope.of("bob"), alice.id(), "user", "我接着问一句"))
                .isInstanceOf(SessionNotWritableException.class);
        assertThat(service.turns(SessionScope.of("alice"), alice.id())).isEmpty();
    }

    @Test
    void 交接之后_原owner只能看不能写_新owner能继续问() {
        ChatSession alice = service.open(SessionScope.of("alice"), "9 月差异复盘");
        service.append(SessionScope.of("alice"), alice.id(), "user", "先看 MISSING_LEFT");

        ChatSession handedOver = service.handover(SessionScope.of("alice"), alice.id(), "bob");

        assertThat(handedOver.operator()).isEqualTo("bob");
        assertThat(handedOver.visibility()).isEqualTo(Visibility.HANDOVER);
        // 交接后会话变成 HANDOVER（全员可见），所以原 owner 仍然读得到历史 ——
        // 但写入权跟着 owner 走了，她再发消息就是往别人的会话里写
        assertThat(service.require(SessionScope.of("alice"), alice.id()).operator()).isEqualTo("bob");
        assertThatThrownBy(() -> service.append(SessionScope.of("alice"), alice.id(), "user", "我再补一句"))
                .isInstanceOf(SessionNotWritableException.class);

        ChatTurn turn = service.append(SessionScope.of("bob"), alice.id(), "user", "我看了一下，是分片切错了");
        assertThat(turn.seq()).isEqualTo(2);
    }

    @Test
    void 交接会留一条轨迹_复用agent_step不新开表() {
        ChatSession session = service.open(SessionScope.of("alice"), "交接审计");

        service.handover(SessionScope.of("alice"), session.id(), "bob");

        List<String> actions = jdbc.queryForList(
                "SELECT s.action FROM recon_agent_step s JOIN recon_agent_run r ON r.id = s.run_id"
                        + " WHERE r.trigger_type = 'SESSION_HANDOVER' ORDER BY s.seq", String.class);
        assertThat(actions).containsExactly("handover-session");

        String goal = jdbc.queryForObject(
                "SELECT goal FROM recon_agent_run WHERE trigger_type = 'SESSION_HANDOVER'", String.class);
        assertThat(goal).contains("alice").contains("bob");

        String status = jdbc.queryForObject(
                "SELECT status FROM recon_agent_run WHERE trigger_type = 'SESSION_HANDOVER'", String.class);
        assertThat(status).isEqualTo(AgentRunRepository.RunStatus.SUCCESS.name());
    }

    @Test
    void 轮次序号从1开始且逐轮自增() {
        ChatSession session = service.open(SessionScope.of("alice"), "序号");

        assertThat(service.append(SessionScope.of("alice"), session.id(), "user", "第一句").seq()).isEqualTo(1);
        assertThat(service.append(SessionScope.of("alice"), session.id(), "assistant", "第二句").seq()).isEqualTo(2);

        assertThat(service.require(SessionScope.of("alice"), session.id()).turnCount()).isEqualTo(2);
        assertThat(service.turns(SessionScope.of("alice"), session.id()))
                .extracting(ChatTurn::content)
                .containsExactly("第一句", "第二句");
    }

    @Test
    void 缺少操作人直接拒绝_不允许落一条无主会话() {
        assertThatThrownBy(() -> SessionScope.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少操作人");
    }

    @Test
    void 交接到自己会被拒绝() {
        ChatSession session = service.open(SessionScope.of("alice"), "自己给自己");

        assertThatThrownBy(() -> service.handover(SessionScope.of("alice"), session.id(), "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相同");
    }

    @Test
    void 槽位用版本号做乐观锁_旧版本写不进去() {
        SessionScope scope = SessionScope.of("alice");
        ChatSession session = service.open(scope, "并发写");
        int rev = session.stateRev();

        assertThat(service.saveSlots(scope, session.id(), rev, "{\"jobId\":1}")).isTrue();
        ChatSession after = service.require(scope, session.id());
        assertThat(after.stateJson()).isEqualTo("{\"jobId\":1}");
        assertThat(after.stateRev()).isEqualTo(rev + 1);

        // 拿着已经过期的版本号再写：必须失败，而不是把别人刚写的覆盖掉
        assertThat(service.saveSlots(scope, session.id(), rev, "{\"jobId\":2}")).isFalse();
        assertThat(service.require(scope, session.id()).stateJson()).isEqualTo("{\"jobId\":1}");
    }

    @Test
    void 别人写不进我的槽位_而且报的是不存在() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = service.open(alice, "别人的槽位");

        // 对外一律 404：既不给他写，也不告诉他"这条会话存在"（N8）
        assertThatThrownBy(() -> service.saveSlots(SessionScope.of("bob"), session.id(), session.stateRev(), "{}"))
                .isInstanceOf(SessionNotFoundException.class);
        assertThat(service.require(alice, session.id()).stateJson()).isNull();
    }
}
