package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.chat.plan.ChatPlan;
import io.github.dolow.reconlens.chat.plan.ChatPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自由循环（{@code recon.chat.mode=loop}）的端到端用例。
 *
 * <p>要证明的不是"模型会不会查列"，而是运行时的三件事：
 * ① 多跳真的发生（拿到的工具结果会回灌给下一跳）；
 * ② 写动作仍然停（循环不能把回执绕过去）；
 * ③ 跳数上限真的刹车。
 *
 * <p>用桩规划器：这三条都是编排的性质，跟模型聪不聪明无关，不该靠真模型来验。
 */
@SpringBootTest(properties = {
        "recon.agent.enabled=true",
        "recon.engine.run-demo-on-startup=false",
        "recon.chat.mode=loop",
        "recon.chat.loop.max-hops=3",
        "spring.datasource.url=jdbc:h2:mem:chat_loop;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ChatRuntimeLoopTest {

    @Autowired
    private ChatRuntime runtime;

    @Autowired
    private ChatSessionService sessions;

    @Autowired
    private LoopStub planner;

    @org.junit.jupiter.api.BeforeEach
    void resetPlanner() {
        planner.reset();
    }

    @TestConfiguration
    static class Stub {

        @Bean
        @Primary
        LoopStub loopStub() {
            return new LoopStub();
        }
    }

    /** 第一次给什么、之后每次给什么，由用例排队；排空了就等于模型说"到此为止"。 */
    static class LoopStub implements ChatPlanner {

        private final AtomicReference<ChatPlan> first = new AtomicReference<>();
        private final Queue<ChatPlan> following = new ConcurrentLinkedQueue<>();
        private final AtomicInteger continued = new AtomicInteger();
        private final List<String> transcripts = new CopyOnWriteArrayList<>();

        void willDo(ChatPlan plan) {
            first.set(plan);
        }

        void thenDo(ChatPlan... plans) {
            following.addAll(List.of(plans));
        }

        int continued() {
            return continued.get();
        }

        /** 同一个 Spring 上下文里桩是单例：用例之间必须把排队和计数清干净。 */
        void reset() {
            first.set(null);
            following.clear();
            continued.set(0);
            transcripts.clear();
        }

        @Override
        public Optional<ChatPlan> plan(String message, ChatSlots slots, ContextFacts facts, List<String> history) {
            ChatPlan plan = first.getAndSet(null);
            return Optional.of(plan != null ? plan : ChatPlan.keyword(
                    new ChatIntentRouter.Call(ChatIntent.HELP, null, null, null, null, null, null),
                    0.5, "桩规划器没有安排动作"));
        }

        @Override
        public Optional<ChatPlan> continueLoop(String message, ChatSlots slots, ContextFacts facts,
                                               List<String> history, String summary,
                                               String transcript, int hop) {
            continued.incrementAndGet();
            transcripts.add(transcript == null ? "" : transcript);
            return Optional.ofNullable(following.poll());
        }

        @Override
        public String describe() {
            return "循环测试桩";
        }
    }

    @Test
    void 多跳_上一跳的真实结果会回灌给下一跳() {
        SessionScope scope = SessionScope.of("loop_a");
        long sessionId = sessions.open(scope, "循环多跳").id();
        planner.willDo(ChatPlan.tool("recon_list_rules", Map.of(), 0.9));
        planner.thenDo(ChatPlan.reply("规则一共 3 条，最近跑的是 BILL_VS_SETTLE。", 0.9));

        List<ChatEvent> events = collect(scope, sessionId, "有哪些规则");

        // 用户只看到模型最后的判断；中间那步的真结果进 transcript，让下一跳的模型看得见。
        // （实测教训：全拼起来给用户，第一屏会是一整张表结构。）
        assertThat(text(events)).contains("规则一共 3 条")
                .doesNotContain("规则库里有 3 条对账规则");
        assertThat(planEvents(events)).hasSize(2);
        assertThat(planner.continued()).isEqualTo(1);
        // 回灌的是"真结果"，不是"我调了个工具"这种复述
        assertThat(planner.transcripts.get(0)).contains("规则库里有 3 条对账规则");
    }

    @Test
    void 写工具在循环里也要停下_等用户回执() {
        SessionScope scope = SessionScope.of("loop_b");
        long sessionId = sessions.open(scope, "循环写动作").id();
        planner.willDo(ChatPlan.tool("recon_run", Map.of("ruleCode", "BILL_VS_SETTLE"), 0.9));
        planner.thenDo(ChatPlan.reply("（不该被问到）", 0.9));

        List<ChatEvent> events = collect(scope, sessionId, "跑一下 BILL_VS_SETTLE");

        assertThat(text(events)).contains("确认执行吗");
        assertThat(eventTypes(events)).doesNotContain("tool");
        // 一撞上写动作就停：不再问下一跳，也不把"没做完"当成结论
        assertThat(planner.continued()).isZero();
        assertThat(text(events)).doesNotContain("不该被问到");
    }

    @Test
    void 跳数用尽就刹车_不会无限往下走() {
        SessionScope scope = SessionScope.of("loop_c");
        long sessionId = sessions.open(scope, "循环刹车").id();
        planner.willDo(ChatPlan.tool("recon_list_rules", Map.of(), 0.9));
        planner.thenDo(
                ChatPlan.tool("recon_list_tables", Map.of(), 0.9),
                ChatPlan.tool("recon_list_jobs", Map.of(), 0.9),
                ChatPlan.tool("recon_list_diffs", Map.of("jobId", "1"), 0.9));

        collect(scope, sessionId, "把所有东西都列一遍");

        // max-hops=3：执行三步就停，第 4 个候选根本没被问出来
        assertThat(planner.continued()).isEqualTo(2);
        assertThat(planner.transcripts).hasSize(2);
    }

    @Test
    void 起草描述太薄时_先把话退给模型而不是给用户回执() {
        SessionScope scope = SessionScope.of("loop_d");
        long sessionId = sessions.open(scope, "薄起草").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule",
                Map.of("businessDescription", "按订单、资源对账"), 0.9));
        planner.thenDo(ChatPlan.reply("你说的「资源」是客户编号还是状态？两张表里没有叫「资源」的列。", 0.9));

        List<ChatEvent> events = collect(scope, sessionId, "起草个规则：按订单、资源对账");

        // 用户看到的是模型自己的追问，不是一句"确认执行吗"
        assertThat(text(events)).contains("「资源」").doesNotContain("确认执行吗");
        assertThat(planner.continued()).isEqualTo(1);
        // 那句"被打回"只给模型看：它得知道这一拍没执行
        assertThat(planner.transcripts.get(0)).contains("没有执行");
    }

    private List<ChatEvent> collect(SessionScope scope, long sessionId, String message) {
        List<ChatEvent> events = new ArrayList<>();
        runtime.handle(scope, sessionId, message, events::add);
        return events;
    }

    private static List<String> eventTypes(List<ChatEvent> events) {
        return events.stream().map(ChatEvent::type).toList();
    }

    private static List<Map<String, Object>> planEvents(List<ChatEvent> events) {
        return events.stream().filter(e -> "plan".equals(e.type())).map(ChatEvent::data).toList();
    }

    private static String text(List<ChatEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (ChatEvent e : events) {
            if ("delta".equals(e.type())) {
                sb.append(e.data().get("text"));
            }
        }
        return sb.toString();
    }
}
