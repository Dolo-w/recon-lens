package io.github.dolow.reconlens.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话链路的端到端用例：真实 Spring 上下文、真实 H2、真实引擎。
 *
 * <p>不 mock 工具：这个类要证明的恰恰是"会话调的是同一份工具"，
 * 一旦把 {@code ReconMcpTools} 换成 test double，这个断言就失效了。
 */
@SpringBootTest(properties = {
        "recon.agent.enabled=true",
        "recon.engine.run-demo-on-startup=false",
        "recon.chat.shared-claims.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:chat_runtime;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class ChatRuntimeTest {

    @Autowired
    private ChatRuntime runtime;

    @Autowired
    private ChatSessionService sessions;

    @Autowired
    private ChatClaims claims;

    @Test
    void 一句话跑完对账_槽位被记住_追问不必重复任务号() {
        SessionScope scope = SessionScope.of("zhang");
        long sessionId = sessions.open(scope, "端到端").id();

        List<ChatEvent> run = collect(scope, sessionId, "跑一下 BILL_VS_SETTLE");
        assertThat(eventTypes(run)).contains("intent", "tool", "delta", "slot", "done");
        assertThat(data(run, "intent")).containsEntry("intent", "RUN");

        ChatSession afterRun = sessions.require(scope, sessionId);
        assertThat(afterRun.stateJson()).contains("BILL_VS_SETTLE");
        assertThat(afterRun.turnCount()).isEqualTo(2);

        // 第二句不提交任务号，靠槽位
        List<ChatEvent> summary = collect(scope, sessionId, "为什么差这么多");
        assertThat(data(summary, "intent")).containsEntry("intent", "EXPLAIN_JOB");
        assertThat(summary).anySatisfy(e -> {
            if ("tool".equals(e.type())) {
                assertThat(String.valueOf(e.data().get("summary"))).contains("任务");
            }
        });
    }

    @Test
    void 值不等会被翻译成规则里真实存在的差异类型() {
        SessionScope scope = SessionScope.of("lisi");
        long sessionId = sessions.open(scope, "差异翻译").id();
        collect(scope, sessionId, "跑一下 BILL_VS_SETTLE");

        List<ChatEvent> diffs = collect(scope, sessionId, "值不等的差异有哪些");
        String text = text(diffs);
        // 规则里声明的是 AMOUNT_MISMATCH，不是语义类别 VALUE_MISMATCH；
        // 直接拿后者过滤会"差 0 条"，看起来像对上了。
        assertThat(text).contains("只看 AMOUNT_MISMATCH");
        assertThat(text).doesNotContain("差异共 0 条");
    }

    /**
     * 契约变更（2026-10-24）：缺任务号时只读动作不再反问，而是按最近一次生产任务回答。
     *
     * <p>原来的契约是"没有对象就反问"——安全但啰嗦：库里只有一两次对账时，用户被迫去翻任务号。
     * 和"规则库只有一条时能不能省规则名"是同一个判断。代价是"最近一次"是猜测，
     * 所以回答里必须写明猜的是几号，让人一眼能看出猜错。
     */
    @Test
    void 没给任务号时_只读动作按最近一次生产任务回答_并且说明是猜的() {
        SessionScope scope = SessionScope.of("wangwu");
        long ran = sessions.open(scope, "先跑一单").id();
        collect(scope, ran, "跑一下 BILL_VS_SETTLE");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"jobId\":(\\d+)")
                .matcher(sessions.require(scope, ran).stateJson());
        assertThat(m.find()).as("跑完一单后槽位里应该有 jobId").isTrue();
        String jobId = m.group(1);

        // 新会话的槽位是空的：此时才走兜底
        long fresh = sessions.open(scope, "空槽位").id();
        List<ChatEvent> diffs = collect(scope, fresh, "看差异");
        assertThat(text(diffs)).contains("按最近一次生产任务 #" + jobId);
        assertThat(sessions.require(scope, fresh).stateJson()).contains("\"jobId\":" + jobId);
    }

    @Test
    void 重跑缺任务号仍然反问_写动作不替用户挑任务() {
        SessionScope scope = SessionScope.of("wangwu");
        long sessionId = sessions.open(scope, "重跑反问").id();
        List<ChatEvent> retry = collect(scope, sessionId, "重跑一下");
        assertThat(text(retry)).contains("要重跑哪个任务");
        assertThat(text(retry)).doesNotContain("最近一次生产任务");
    }

    @Test
    void 回答上一问的裸列名能接住_不再复读同一个问题() {
        SessionScope scope = SessionScope.of("sunqi");
        long sessionId = sessions.open(scope, "裸列名").id();

        List<ChatEvent> first = collect(scope, sessionId, "起草个规则");
        assertThat(text(first)).contains("表");

        List<ChatEvent> second = collect(scope, sessionId, "左 bill_left 右 bill_right");
        assertThat(text(second)).contains("业务键");

        // 线上错法：用户回「order_no」，解析不出任何槽位，于是把「业务键…？」原样再问一遍
        List<ChatEvent> third = collect(scope, sessionId, "order_no");
        assertThat(text(third)).contains("比哪一个字段");
    }

    @Test
    void 确认时改参数_不从头再问_只改这一项() {
        SessionScope scope = SessionScope.of("zhouba");
        long sessionId = sessions.open(scope, "确认时改参数").id();

        collect(scope, sessionId, "起草个规则");
        collect(scope, sessionId, "左 bill_left 右 bill_right");
        collect(scope, sessionId, "order_no");
        List<ChatEvent> ready = collect(scope, sessionId, "比金额");
        assertThat(text(ready)).contains("确认执行");

        // 线上错法：在"确认执行吗"这一步回「比状态」，被当成新指令，待确认的草稿被丢，
        // 又问回「左侧用哪张表」。这里要求它把这句当"改参数"：左右键都还在，只换比对字段。
        List<ChatEvent> fix = collect(scope, sessionId, "比状态");
        String fixed = text(fix);
        assertThat(fixed).contains("确认执行");
        assertThat(fixed).contains("field: status");
        assertThat(fixed).doesNotContain("左侧用哪张表");
    }

    @Test
    void 起草确认后_结论板记口径_再起草同编码只留最新一条() {
        SessionScope scope = SessionScope.of("qianqi");
        long sessionId = sessions.open(scope, "结论板").id();

        collect(scope, sessionId, "起草个规则");
        collect(scope, sessionId, "左 bill_left 右 bill_right");
        collect(scope, sessionId, "order_no");
        collect(scope, sessionId, "比金额");
        collect(scope, sessionId, "确认");

        String first = claims.render(sessionId);
        assertThat(first).contains("〔当前有效〕").contains("比 amount");

        // 同一对表再起草一次、只改比对字段：主题相同（draft:编码），必须覆盖而不是并排
        collect(scope, sessionId, "起草个规则");
        collect(scope, sessionId, "左 bill_left 右 bill_right");
        collect(scope, sessionId, "order_no");
        collect(scope, sessionId, "比状态");
        collect(scope, sessionId, "确认");

        String latest = claims.render(sessionId);
        assertThat(latest).contains("比 status");
        assertThat(latest).doesNotContain("比 amount");
    }

    @Test
    void 翻一下顺序_左右对调_并且说清楚已对调() {
        SessionScope scope = SessionScope.of("wushi");
        long sessionId = sessions.open(scope, "翻顺序").id();

        collect(scope, sessionId, "起草个规则");
        collect(scope, sessionId, "左 bill_left 右 bill_right");

        // 线上错法：说"翻一下顺序"被当成听不懂，回一句"已记下：左 bill_left，右 bill_right"再问业务键
        List<ChatEvent> swap = collect(scope, sessionId, "翻一下顺序");
        String text = text(swap);
        assertThat(text).contains("已对调左右");
        assertThat(text).contains("左 bill_right").contains("右 bill_left");
        assertThat(text).contains("业务键");
    }

    @Test
    void 审批只解释不执行_库里不会多出版本() {
        SessionScope scope = SessionScope.of("zhaoliu");
        long sessionId = sessions.open(scope, "审批").id();
        List<ChatEvent> events = collect(scope, sessionId, "我要批准这条规则");
        assertThat(data(events, "intent")).containsEntry("intent", "APPROVE");
        assertThat(text(events)).contains("确认码");
        // 没有调用任何工具
        assertThat(eventTypes(events)).doesNotContain("tool");
    }

    @Test
    void 落草稿的回执只讲事实_不出现工具名和接口路径() {
        SessionScope scope = SessionScope.of("qianjiu");
        long sessionId = sessions.open(scope, "人话回执").id();

        collect(scope, sessionId, "起草个规则");
        collect(scope, sessionId, "左 bill_left 右 bill_right");
        collect(scope, sessionId, "order_no");
        collect(scope, sessionId, "比金额");
        List<ChatEvent> done = collect(scope, sessionId, "确认");

        String text = text(done);
        assertThat(text).contains("草稿 v1").contains("已落库");
        assertThat(text).doesNotContain("recon_").doesNotContain("POST /api");
    }

    @Test
    void 问草稿规则_只答草稿_生效规则只报数() {
        SessionScope scope = SessionScope.of("sunshi");
        long sessionId = sessions.open(scope, "挑草稿").id();

        String text = text(collect(scope, sessionId, "现在有啥草稿规则"));

        // 同上下文里的前一个用例已经落了一条 AUTO_BILL_LEFT_BILL_RIGHT 草稿：问草稿就只答它，
        // 生效规则只报数量，不逐条端上来充数。
        assertThat(text).contains("还没生效").contains("AUTO_BILL_LEFT_BILL_RIGHT")
                .contains("草稿").contains("其余 3 条是生效版");
        assertThat(text).doesNotContain("recon_").doesNotContain("调 recon").doesNotContain("/api/");
    }

    @Test
    void 规则清单只说规则_不带工具话术() {
        SessionScope scope = SessionScope.of("sunshi");
        long sessionId = sessions.open(scope, "列规则").id();

        String text = text(collect(scope, sessionId, "有哪些规则"));

        assertThat(text).contains("规则库现有").contains("生效");
        assertThat(text).doesNotContain("recon_").doesNotContain("ruleCode").doesNotContain("/api/");
    }

    /**
     * 同一操作人的两个会话对同一条规则口径说法不同：执行前必须先问，不能猜。
     *
     * <p>如果直接挑一条执行，差出来的清单会被错误口径带偏——多问一句比跑错一次便宜。
     */
    @Test
    void 跨会话口径冲突_执行前先反问_选完记成已确认() {
        SessionScope scope = SessionScope.of("zhao");
        long old = sessions.open(scope, "上周").id();
        claims.record(old, "zhao", "draft:BILL_VS_SETTLE", "维度 order_no，比 amount", 4,
                io.github.dolow.reconlens.store.ChatClaimRepository.AUTHORITY_STATED);
        long other = sessions.open(scope, "昨天").id();
        claims.record(other, "zhao", "draft:BILL_VS_SETTLE", "维度 status，比 amount", 6,
                io.github.dolow.reconlens.store.ChatClaimRepository.AUTHORITY_STATED);

        long now = sessions.open(scope, "今天").id();
        String ask = text(collect(scope, now, "跑一下 BILL_VS_SETTLE"));
        assertThat(ask).contains("两种说法").contains("以哪个为准");
        assertThat(sessions.require(scope, now).stateJson()).contains("PICK_CLAIM");

        String picked = text(collect(scope, now, "第二个"));
        assertThat(picked).contains("记下了").contains("其他会话");
    }

    private List<ChatEvent> collect(SessionScope scope, long sessionId, String message) {
        List<ChatEvent> events = new ArrayList<>();
        runtime.handle(scope, sessionId, message, events::add);
        return events;
    }

    private static List<String> eventTypes(List<ChatEvent> events) {
        return events.stream().map(ChatEvent::type).toList();
    }

    private static java.util.Map<String, Object> data(List<ChatEvent> events, String type) {
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
