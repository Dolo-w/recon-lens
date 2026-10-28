package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.chat.plan.ChatPlan;
import io.github.dolow.reconlens.chat.plan.ChatPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具直选这条路的端到端用例：规划器不再给"意图"，只给"哪个工具 + 什么参数"。
 *
 * <p>用可编程的桩规划器，是因为这里要证明的不是"模型会选对"，而是
 * <b>运行时拿到一个工具名之后有没有照做、写动作有没有拦住、拦下之后参数还在不在</b>。
 * 那三件事都不该依赖真实模型。
 */
@SpringBootTest(properties = {
        "recon.agent.enabled=true",
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:chat_tool;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ChatRuntimeToolTest {

    @Autowired
    private ChatRuntime runtime;

    @Autowired
    private ChatSessionService sessions;

    @Autowired
    private StubPlanner planner;

    @TestConfiguration
    static class Stub {

        @Bean
        @Primary
        StubPlanner stubPlanner() {
            return new StubPlanner();
        }
    }

    /** 由用例指定"这一轮模型选了什么"；没指定时退化成 HELP，绝不返回空。 */
    static class StubPlanner implements ChatPlanner {

        private final AtomicReference<ChatPlan> next = new AtomicReference<>();

        void willDo(ChatPlan plan) {
            next.set(plan);
        }

        @Override
        public Optional<ChatPlan> plan(String message, ChatSlots slots, ContextFacts facts, List<String> history) {
            ChatPlan plan = next.getAndSet(null);
            if (plan != null) {
                return Optional.of(plan);
            }
            return Optional.of(ChatPlan.keyword(
                    new ChatIntentRouter.Call(ChatIntent.HELP, null, null, null, null, null, null),
                    0.5, "桩规划器没有安排动作"));
        }

        @Override
        public String describe() {
            return "测试桩";
        }
    }

    @Test
    void 模型点名的只读工具会被真的执行_回执里能看到工具名() {
        SessionScope scope = SessionScope.of("tool_a");
        long sessionId = sessions.open(scope, "工具直选").id();
        planner.willDo(ChatPlan.tool("recon_list_rules", Map.of(), 0.9));

        List<ChatEvent> events = collect(scope, sessionId, "看下规则");

        assertThat(data(events, "plan")).containsEntry("tool", "recon_list_rules");
        assertThat(eventTypes(events)).contains("tool");
        // 结果就是工具的原话：这条路没有"意图 → 固定实现"的二次加工
        assertThat(text(events)).contains("规则库里有 3 条对账规则");
    }

    /**
     * 写工具必须停在回执上，而且确认之后要按<b>原来那组参数</b>执行。
     *
     * <p>参数是上一轮模型给的，用户确认的是那一组；确认时若重新从槽位拼参数，
     * 等于把"确认"变成了"再猜一次"。
     */
    @Test
    void 写工具停在回执_确认后按暂存参数执行() {
        SessionScope scope = SessionScope.of("tool_b");
        long sessionId = sessions.open(scope, "工具写动作").id();
        planner.willDo(ChatPlan.tool("recon_run", Map.of("ruleCode", "BILL_VS_SETTLE"), 0.9));

        List<ChatEvent> first = collect(scope, sessionId, "跑一下这个");

        assertThat(text(first)).contains("确认执行吗");
        assertThat(eventTypes(first)).doesNotContain("tool");
        String state = sessions.require(scope, sessionId).stateJson();
        assertThat(state).contains("recon_run").contains("BILL_VS_SETTLE");

        List<ChatEvent> second = collect(scope, sessionId, "确认");

        assertThat(eventTypes(second)).contains("tool");
        assertThat(text(second)).contains("BILL_VS_SETTLE");
        // 确认之后不能再问一次"确认执行吗"——那会把两步确认变成死循环（实测踩过）
        assertThat(text(second)).doesNotContain("确认执行吗");
    }

    /**
     * 守卫话术必须逐字到达用户。
     *
     * <p>上一轮实测里这段话被总结模型改写成了"好的，我来帮你跑一下"——一句话就把
     * "我没有动手"变成了"我要动手"，比不解释更糟。
     */
    @Test
    void 反问守卫的回复逐字送达_不被总结层改写() {
        SessionScope scope = SessionScope.of("tool_c");
        long sessionId = sessions.open(scope, "守卫逐字").id();
        String message = "这不是看差异吗？";
        String expected = io.github.dolow.reconlens.chat.plan.IntentReview.question(message, ChatIntent.LIST_DIFFS);
        assertThat(expected).as("这句话应该被守卫判定为反问").isNotNull();
        planner.willDo(ChatPlan.keyword(
                new ChatIntentRouter.Call(ChatIntent.LIST_DIFFS, null, null, null, null, null, null),
                0.9, "测试桩指定"));

        // 末尾那一个换行是 SSE 的分行包装（每行 delta 自带 "\n"），不是内容的一部分
        assertThat(text(collect(scope, sessionId, message)).stripTrailing()).isEqualTo(expected);
    }

    /**
     * 起草不能被当成"点一下就跑"的写动作。
     *
     * <p>用户说的是"按订单、资源对账"——没有表、没有键、没有列名。真实模型会直接点
     * {@code recon_draft_rule}，若照着执行，后台拿一份猜的骨架去试算，用户全程没机会说
     * "「资源」不是列名"。所以这条工具在聊天里接的是收集路径：先问表，翻不出的字段点名问。
     */
    @Test
    void 起草工具走参数收集_先问表和字段_不先回执() {
        SessionScope scope = SessionScope.of("tool_d");
        long sessionId = sessions.open(scope, "起草收集").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule", Map.of("businessDescription", "按订单、资源对账"), 0.9));

        String answer = text(collect(scope, sessionId, "起草个规则：按订单、资源对账"));

        assertThat(answer).contains("哪张表");
        // 回执话术里不能出现内部工具名——实测用户对着 "执行 recon_draft_rule" 问"这是什么"
        assertThat(answer).doesNotContain("recon_");
        assertThat(answer).doesNotContain("确认执行吗");
    }

    /** 写工具的回执说人话：不报注册名，报"这一步要做什么"。 */
    @Test
    void 写工具的回执不出现内部工具名() {
        SessionScope scope = SessionScope.of("tool_e");
        long sessionId = sessions.open(scope, "回执话术").id();
        planner.willDo(ChatPlan.tool("recon_import_file", Map.of("path", "work/a.csv"), 0.9));

        String answer = text(collect(scope, sessionId, "把这个文件导进来"));

        assertThat(answer).contains("导入一个数据文件");
        assertThat(answer).contains("确认执行吗");
        assertThat(answer).doesNotContain("recon_import_file");
    }

    /**
     * 追问"这个字段不存在"之后，已经收到的表不能丢。
     *
     * <p>实测踩到的样子：问了「资源」在不在列里，用户答「bill_right」，
     * 系统把 bill_right 当成左侧表、又把「资源」问了一遍——前面那句 bill_left 白说了。
     */
    @Test
    void 追问不存在的字段之后_已收集的表还在() {
        SessionScope scope = SessionScope.of("tool_f");
        long sessionId = sessions.open(scope, "起草追问").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule", Map.of("businessDescription", "按订单、资源对账"), 0.9));

        assertThat(text(collect(scope, sessionId, "起草个规则：按订单、资源对账"))).contains("哪张表");
        String afterLeft = text(collect(scope, sessionId, "bill_left"));
        assertThat(afterLeft).contains("「资源」").contains("没找到");

        String afterRight = text(collect(scope, sessionId, "bill_right"));
        // 左侧已经收下了：不该再问一遍左侧表，应该往下走问业务键
        assertThat(afterRight).doesNotContain("左侧用哪张表");
        assertThat(afterRight).contains("业务键");
    }

    /**
     * 问了"「日期」对应哪一列"，用户答了列名，就得算数。
     *
     * <p>实测：答「就用 updated_at 吧」之后系统又按原样出骨架，updated_at 被丢掉——
     * 问是问了，答案没人接。
     */
    @Test
    void 回上一轮问的列名_会补进维度() {
        SessionScope scope = SessionScope.of("tool_h");
        long sessionId = sessions.open(scope, "回列名").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule",
                Map.of("businessDescription", "按订单、客户、日期对账，比金额"), 0.9));

        assertThat(text(collect(scope, sessionId, "起草个规则：按订单、客户、日期对账，比金额"))).contains("哪张表");
        assertThat(text(collect(scope, sessionId, "左 bill_left 右 bill_right"))).contains("「日期」");

        String answer = text(collect(scope, sessionId, "就用 updated_at 吧"));
        assertThat(answer).contains("updated_at");
        assertThat(answer).contains("确认执行吗");
    }

    /**
     * 收集途中模型插一句话，不能把状态机顶掉。
     *
     * <p>实测：问完左侧表，用户回「bill_left」，模型自己答了一段"左侧记下了…"，
     * 于是这句既没存进槽位、也没往下问，下一句又从"左侧用哪张表"开始。
     */
    @Test
    void 收集途中模型插话_参数收集照旧往下走() {
        SessionScope scope = SessionScope.of("tool_g");
        long sessionId = sessions.open(scope, "模型插话").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule", Map.of("businessDescription", "按订单、资源对账"), 0.9));
        assertThat(text(collect(scope, sessionId, "起草个规则：按订单、资源对账"))).contains("哪张表");

        planner.willDo(ChatPlan.reply("好的，我记下了，我们接着来。", 0.8));
        String answer = text(collect(scope, sessionId, "bill_left"));

        assertThat(answer).doesNotContain("我们接着来");
        // 收下了左侧，并且把翻不出来的「资源」点出来问，而不是从头再问一遍表
        assertThat(answer).contains("「资源」").contains("没找到");
    }

    /**
     * 收集途中模型挑了个只读工具，但这句话其实是在补参数。
     *
     * <p>实测：问「「资源」在不在列里」，用户回「bill_right」——模型把它读成
     * "看这张表的字段"，于是右侧表没记下，下一轮又从"右侧用哪张表"开始。
     */
    @Test
    void 收集途中只读工具_遇到补参数的句子要让路() {
        SessionScope scope = SessionScope.of("tool_h");
        long sessionId = sessions.open(scope, "只读抢话题").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule", Map.of("businessDescription", "按订单、资源对账"), 0.9));
        assertThat(collect(scope, sessionId, "起草个规则：按订单、资源对账")).isNotEmpty();
        assertThat(text(collect(scope, sessionId, "bill_left"))).contains("「资源」");

        planner.willDo(ChatPlan.tool("recon_describe_table", Map.of("table", "bill_right"), 0.9));
        String answer = text(collect(scope, sessionId, "bill_right"));

        assertThat(answer).doesNotContain("明细");
        assertThat(answer).contains("业务键");
    }

    /**
     * 只差右侧表时，单说的那张表要归到右边。
     *
     * <p>解析器不知道上一问问的是哪一边，一律"第一张表当左"：实测收出
     * 「左 bill_right，右 bill_right」，两边同表 → 永远 0 差异的假成功。
     */
    @Test
    void 只差右侧表时_单说的表归到右边() {
        SessionScope scope = SessionScope.of("tool_i");
        long sessionId = sessions.open(scope, "右侧表单说").id();
        planner.willDo(ChatPlan.tool("recon_draft_rule", Map.of("businessDescription", "按订单、资源对账"), 0.9));
        assertThat(text(collect(scope, sessionId, "起草个规则：按订单、资源对账"))).contains("左侧用哪张表");
        assertThat(text(collect(scope, sessionId, "bill_left"))).contains("「资源」");

        String answer = text(collect(scope, sessionId, "bill_right"));

        assertThat(answer).contains("bill_left").contains("bill_right");
        assertThat(answer).doesNotContain("左 bill_right");
    }

    private List<ChatEvent> collect(SessionScope scope, long sessionId, String message) {
        List<ChatEvent> events = new ArrayList<>();
        runtime.handle(scope, sessionId, message, events::add);
        return events;
    }

    private static List<String> eventTypes(List<ChatEvent> events) {
        return events.stream().map(ChatEvent::type).toList();
    }

    private static Map<String, Object> data(List<ChatEvent> events, String type) {
        Optional<ChatEvent> first = events.stream().filter(e -> type.equals(e.type())).findFirst();
        assertThat(first).as("缺少事件：" + type).isPresent();
        return first.get().data();
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
