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
 * 滚动摘要与轮次分页（ADR-0001 D2 / P1）。
 *
 * <p>跑真实 {@code schema.sql}：这一轮加了 {@code summary_upto_seq} 列，手写 DDL 只能证明
 * "代码和我自己写的 DDL 一致"，证明不了仓库里的 schema 能起来。
 *
 * <p>{@code windowTokens} 故意传 1000：默认 8000 要聊很久才触发，测试等不起；
 * 阈值是配置项，降到 1000 之后同样的轮次就能压，覆盖的是同一段判断逻辑。
 */
class ChatSummaryServiceTest {

    private static JdbcTemplate jdbc;
    private static ChatSessionService sessions;
    private static ChatSummaryService summaries;

    @BeforeAll
    static void setUpSchema() throws Exception {
        String url = "jdbc:h2:mem:chat_summary;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        sessions = new ChatSessionService(new ChatSessionRepository(jdbc), new AgentRunRepository(jdbc));
        summaries = new ChatSummaryService(new ChatSessionRepository(jdbc), 1_000);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_chat_turn");
        jdbc.update("DELETE FROM recon_chat_session");
        jdbc.update("DELETE FROM recon_agent_step");
        jdbc.update("DELETE FROM recon_agent_run");
    }

    private void chat(SessionScope scope, long id, String question, String answer) {
        sessions.append(scope, id, "user", question);
        sessions.append(scope, id, "assistant", answer);
    }

    @Test
    void 窗口还宽的时候不压_压了反而丢掉最近说的话() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "小会话");
        chat(alice, session.id(), "跑一下 BILL_VS_SETTLE", "任务 #1 SUCCESS，4 条差异");
        chat(alice, session.id(), "为什么差这么多", "4 条归因");

        assertThat(summaries.maybeSummarize(alice, session.id())).isNull();
        assertThat(sessions.require(alice, session.id()).summary()).isNull();
    }

    @Test
    void 超过窗口预算就压_并把水位线推到最后一轮() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "长会话");
        for (int i = 1; i <= 6; i++) {
            chat(alice, session.id(), "第 " + i + " 个问题：" + "背景说明".repeat(200),
                    "第 " + i + " 个回答：" + "结论明细".repeat(200));
        }

        String summary = summaries.maybeSummarize(alice, session.id());

        assertThat(summary).isNotNull().contains("〔第 1–12 轮〕");
        ChatSession after = sessions.require(alice, session.id());
        assertThat(after.summary()).isEqualTo(summary);
        assertThat(after.summaryUptoSeq()).isEqualTo(12);
    }

    @Test
    void 二次压缩只处理水位线之后的轮次_不会把老摘要再压一遍() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "长会话");
        for (int i = 1; i <= 6; i++) {
            chat(alice, session.id(), "第 " + i + " 问：" + "背景".repeat(220), "第 " + i + " 答：" + "结论".repeat(220));
        }
        summaries.maybeSummarize(alice, session.id());
        int watermark = sessions.require(alice, session.id()).summaryUptoSeq();

        // 再攒够 4 轮才压：水位线之后的空档太短时不值得压（MIN_TURNS），
        // 压了也会因为"刚压完又满"立刻再压一次
        for (int i = 1; i <= 3; i++) {
            chat(alice, session.id(), "又来了 " + i + "：" + "补充".repeat(240), "又说 " + i + "：" + "明细".repeat(240));
        }
        String second = summaries.maybeSummarize(alice, session.id());

        ChatSession after = sessions.require(alice, session.id());
        assertThat(after.summaryUptoSeq()).isGreaterThan(watermark);
        assertThat(second).contains("〔第 " + (watermark + 1) + "–");
    }

    @Test
    void 手动压一次能当场验证_不需要聊到阈值() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "小会话");
        chat(alice, session.id(), "有哪些规则", "3 条规则");

        String summary = summaries.summarizeNow(alice, session.id());

        assertThat(summary).contains("问（1）：有哪些规则");
        assertThat(sessions.require(alice, session.id()).summaryUptoSeq()).isEqualTo(2);
    }

    @Test
    void 别人压不了我的会话() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "alice 的会话");
        chat(alice, session.id(), "问", "答");

        assertThatThrownBy(() -> summaries.summarizeNow(SessionScope.of("bob"), session.id()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void 分页默认拿最后一页_并且告诉调用方还有没有更早的() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "分页");
        for (int i = 1; i <= 10; i++) {
            sessions.append(alice, session.id(), "user", "第 " + i + " 句");
        }

        ChatSessionService.TurnPage first = sessions.page(alice, session.id(), null, 4);
        assertThat(first.turns()).extracting(ChatTurn::seq).containsExactly(7, 8, 9, 10);
        assertThat(first.hasMoreBefore()).isTrue();
        assertThat(first.nextBefore()).isEqualTo(7);

        ChatSessionService.TurnPage second = sessions.page(alice, session.id(), first.nextBefore(), 4);
        assertThat(second.turns()).extracting(ChatTurn::seq).containsExactly(3, 4, 5, 6);
        assertThat(second.hasMoreBefore()).isTrue();

        ChatSessionService.TurnPage last = sessions.page(alice, session.id(), second.nextBefore(), 4);
        assertThat(last.turns()).extracting(ChatTurn::seq).containsExactly(1, 2);
        assertThat(last.hasMoreBefore()).isFalse();
        assertThat(last.nextBefore()).isNull();
    }

    @Test
    void 分页取不到不存在的会话_仍然是404语义() {
        assertThatThrownBy(() -> sessions.page(SessionScope.of("bob"), 999_999, null, 10))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void 摘要写库不动lastAt_不然会话会莫名其妙跳到列表最上面() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "顺序");
        chat(alice, session.id(), "问", "答");
        var before = sessions.require(alice, session.id()).lastAt();

        summaries.summarizeNow(alice, session.id());

        assertThat(sessions.require(alice, session.id()).lastAt()).isEqualTo(before);
    }

    @Test
    void 老会话没有摘要时_详情里就是null_不是空字符串() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "老会话");

        List<ChatTurn> page = sessions.page(alice, session.id(), null, 10).turns();

        assertThat(page).isEmpty();
        assertThat(sessions.require(alice, session.id()).summary()).isNull();
    }

    // --------------------------------------------------- ADR-0001 N6：压缩不改审计

    @Test
    void 压缩只改session的摘要_原始轮次一行不少一字不改() {
        SessionScope alice = SessionScope.of("alice");
        ChatSession session = sessions.open(alice, "长会话");
        for (int i = 1; i <= 6; i++) {
            chat(alice, session.id(), "第 " + i + " 问：" + "背景".repeat(200),
                    "第 " + i + " 答：" + "结论".repeat(200));
        }
        List<ChatTurn> before = sessions.turns(alice, session.id());
        String beforeText = before.stream().map(ChatTurn::content).toList().toString();

        assertThat(summaries.maybeSummarize(alice, session.id())).isNotNull();

        List<ChatTurn> after = sessions.turns(alice, session.id());
        assertThat(after).hasSameSizeAs(before);
        assertThat(after.stream().map(ChatTurn::content).toList().toString()).isEqualTo(beforeText);
        assertThat(after.get(0).seq()).isEqualTo(1);
        // 摘要只在 session 上，轮次表是审计底稿，不能被"压掉"
        assertThat(sessions.require(alice, session.id()).summary()).isNotNull();
    }
}
