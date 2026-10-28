package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ContextFacts;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型输出的解析层测试。
 *
 * <p>为什么这一层比"提示词写得好不好"更值得测：提示词是建议，解析是约束。
 * 模型可能返回 markdown 代码块、返回不存在的动作、把 jobId 写成字符串、
 * 或者干脆编一个不存在的规则编码——这些都必须被挡住，而不是带进执行层。
 */
class LlmPlannerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LlmPlanner planner = new LlmPlanner(
            new LlmChatClient("", "", "deepseek-chat", 1000, json), json);

    @Test
    @DisplayName("正常 JSON 映射成动作与参数")
    void 正常json() throws Exception {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"BILL_VS_SETTLE"},"confidence":0.91,
                 "reason":"用户点名了规则","options":[]}
                """).orElseThrow();
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.RUN);
        assertThat(plan.call().ruleCode()).isEqualTo("BILL_VS_SETTLE");
        assertThat(plan.confidence()).isEqualTo(0.91);
        assertThat(plan.source()).isEqualTo(ChatPlan.Source.LLM);
        assertThat(plan.reason()).contains("点名");
    }

    @Test
    @DisplayName("包在 markdown 代码块里也要能认")
    void 代码块包裹() throws Exception {
        ChatPlan plan = planner.parse("""
                ```json
                {"action":"LIST_DIFFS","args":{"jobId":2},"confidence":0.8,"reason":"看差异"}
                ```
                """).orElseThrow();
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.LIST_DIFFS);
        assertThat(plan.call().jobId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("不存在的动作一律不认，交给词表兜底")
    void 未知动作() throws Exception {
        assertThat(planner.parse("{\"action\":\"EXECUTE_SQL\",\"confidence\":0.99}")).isEmpty();
        assertThat(planner.parse("{\"action\":\"\",\"confidence\":0.99}")).isEmpty();
        assertThat(planner.parse("不是 JSON")).isEmpty();
    }

    @Test
    @DisplayName("模型说 done：给一句固定收尾，而不是落回词表菜单")
    void done给收尾句() throws Exception {
        ChatPlan plan = planner.parse("{\"action\":\"done\"}").orElseThrow();
        assertThat(plan.isReply()).isTrue();
        assertThat(plan.reply()).contains("这轮就到这儿");
    }

    @Test
    @DisplayName("done 时模型自己补的总结原样用")
    void done带总结() throws Exception {
        ChatPlan plan = planner.parse("{\"action\":\"done\",\"text\":\"两边差 3 笔，都在挂账侧。\"}")
                .orElseThrow();
        assertThat(plan.isReply()).isTrue();
        assertThat(plan.reply()).isEqualTo("两边差 3 笔，都在挂账侧。");
    }

    // ---------------------------------------------------------------- 上下文裁剪

    @Test
    @DisplayName("上下文超长时只裁摘要，结论板一个字都不能少")
    void 结论板不被截断() {
        String history = "很久以前的对账背景。".repeat(200);
        String claims = io.github.dolow.reconlens.chat.ChatClaims.HEADER
                + "\n· ORDER_AMOUNT_RECON 起草参数：维度 order_no，比 amount（第 9 轮）";
        String fitted = LlmPlanner.fitContext(history + "\n\n" + claims);

        assertThat(fitted).contains(io.github.dolow.reconlens.chat.ChatClaims.HEADER).contains("比 amount");
        assertThat(fitted.length()).isLessThan(history.length());
        assertThat(fitted).endsWith(claims);
    }

    @Test
    @DisplayName("没有结论板时按 token 预算截断")
    void 纯摘要照旧截断() {
        // 口径在批 2 从"1200 字符"换成了"TurnBudget.SUMMARY token"：
        // 英文 4 字符/token，600 token ≈ 2400 字符，所以这里要 3000 个 x 才会被压。
        String fitted = LlmPlanner.fitContext("x".repeat(3000));
        assertThat(fitted).endsWith("…[预算截断]");
        assertThat(io.github.dolow.reconlens.chat.TurnBudget.estimate(fitted))
                .isLessThanOrEqualTo(io.github.dolow.reconlens.chat.TurnBudget.SUMMARY);

        assertThat(LlmPlanner.fitContext(null)).isEmpty();
        assertThat(LlmPlanner.fitContext("短")).isEqualTo("短");
    }

    @Test
    @DisplayName("工具名和中文动作名也能对回枚举")
    void 别名映射() throws Exception {
        assertThat(planner.parse("{\"action\":\"recon_run\",\"confidence\":0.9}").orElseThrow()
                .call().intent()).isEqualTo(ChatIntent.RUN);
        assertThat(planner.parse("{\"action\":\"看差异\",\"confidence\":0.9}").orElseThrow()
                .call().intent()).isEqualTo(ChatIntent.LIST_DIFFS);
    }

    @Test
    @DisplayName("编出来的规则编码要丢掉，不能让引擎去查一个不存在的名字")
    void 非法规则编码丢弃() throws Exception {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"随便编的"},"confidence":0.9,"reason":"x"}
                """).orElseThrow();
        assertThat(plan.call().ruleCode()).isNull();
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.RUN);
    }

    @Test
    @DisplayName("jobId 是字符串也能收，但不是数字就算了")
    void 数字参数() throws Exception {
        assertThat(planner.parse("{\"action\":\"LIST_DIFFS\",\"args\":{\"jobId\":\"2\"},\"confidence\":0.8}")
                .orElseThrow().call().jobId()).isEqualTo(2L);
        assertThat(planner.parse("{\"action\":\"LIST_DIFFS\",\"args\":{\"jobId\":\"两个\"},\"confidence\":0.8}")
                .orElseThrow().call().jobId()).isNull();
    }

    @Test
    @DisplayName("候选最多三个，超长的截断")
    void 候选上限() throws Exception {
        ChatPlan plan = planner.parse("""
                {"action":"HELP","confidence":0.2,"reason":"听不懂",
                 "options":["RUN","LIST_DIFFS","JOB_SUMMARY","EXPLAIN_JOB"]}
                """).orElseThrow();
        assertThat(plan.options()).hasSize(3);
        assertThat(plan.hasOptions()).isTrue();
        assertThat(plan.confident()).isFalse();
    }

    // ---------------------------------------------------------------- 事实校验

    private static final io.github.dolow.reconlens.chat.ContextFacts FACTS =
            new io.github.dolow.reconlens.chat.ContextFacts("facts",
                    java.util.Set.of("BILL_VS_SETTLE"), java.util.Set.of(12L), java.util.Set.of("bill_left"));

    @Test
    @DisplayName("参数与事实对得上：证据充分，可以执行")
    void 参数对得上() {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"BILL_VS_SETTLE"},"confidence":0.8,"reason":"点名跑这条"}
                """, FACTS).orElseThrow();
        assertThat(plan.evidence()).isEqualTo(ChatPlan.Evidence.CONSISTENT);
        assertThat(plan.confident()).isTrue();
    }

    @Test
    @DisplayName("编造一个库里不存在的规则编码：判冲突，不许执行")
    void 编造规则编码() {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"NO_SUCH_RULE"},"confidence":0.95,"reason":"猜的"}
                """, FACTS).orElseThrow();
        assertThat(plan.evidence()).isEqualTo(ChatPlan.Evidence.CONFLICT);
        assertThat(plan.confident()).isFalse();
    }

    @Test
    @DisplayName("编造一个不存在的任务号：同样判冲突")
    void 编造任务号() {
        ChatPlan plan = planner.parse("""
                {"action":"JOB_SUMMARY","args":{"jobId":999},"confidence":0.9,"reason":"说的是 999"}
                """, FACTS).orElseThrow();
        assertThat(plan.evidence()).isEqualTo(ChatPlan.Evidence.CONFLICT);
    }

    @Test
    @DisplayName("没取到事实时不校验：空集合不等于「库里没有」")
    void 没有事实就不否决() {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"ANY_RULE"},"confidence":0.8,"reason":"用户说的"}
                """, io.github.dolow.reconlens.chat.ContextFacts.EMPTY).orElseThrow();
        assertThat(plan.evidence()).isNotEqualTo(ChatPlan.Evidence.CONFLICT);
    }

    @Test
    @DisplayName("模型从事实里补出来的参数要保留：执行前由运行时确认，而不是丢掉理解")
    void 推断出来的参数保留() {
        ChatPlan plan = planner.parse("""
                {"action":"RUN","args":{"ruleCode":"BILL_VS_SETTLE"},"confidence":0.9,"reason":"事实里只有这条对账单"}
                """, FACTS).orElseThrow();

        // 两段式：模型猜、用户点头、系统才动手。这里断言"猜的结果没被丢掉"。
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.RUN);
        assertThat(plan.call().ruleCode()).isEqualTo("BILL_VS_SETTLE");
        assertThat(plan.evidence()).isEqualTo(ChatPlan.Evidence.CONSISTENT);
    }

    /**
     * 兜底分支以前是 {@code default -> true}：只要不是三个人尽皆知的动作，一律判"一致"。
     *
     * <p>于是"帮我看看 999 号任务"这种带实体、但事实里根本没有 999 的话，
     * 会被判成 CONSISTENT 直接执行，再在下游查不到数据。现在带实体必须核实。
     */
    @Test
    @DisplayName("带实体的动作在事实里对不上就是没核实，不再默认放行")
    void 带实体必须核实() {
        ContextFacts facts = new ContextFacts("规则（1 条）", Set.of("BILL_VS_SETTLE"),
                Set.of(1L), Set.of("bill_left"));
        ChatIntentRouter.Call missingJob = new ChatIntentRouter.Call(
                ChatIntent.EXPLAIN_JOB, null, 999L, null, null, null, null);
        ChatIntentRouter.Call knownJob = new ChatIntentRouter.Call(
                ChatIntent.EXPLAIN_JOB, null, 1L, null, null, null, null);
        ChatIntentRouter.Call clearing = new ChatIntentRouter.Call(
                ChatIntent.LIST_JOBS, null, null, null, null, null, null);

        // 事实看得见、但对不上 → 矛盾；事实看不见 → 没核实；两种都不再放行
        assertThat(LlmPlanner.evidenceOf(missingJob, facts)).isEqualTo(ChatPlan.Evidence.CONFLICT);
        assertThat(LlmPlanner.evidenceOf(knownJob, facts)).isEqualTo(ChatPlan.Evidence.CONSISTENT);
        assertThat(LlmPlanner.evidenceOf(missingJob, ContextFacts.EMPTY)).isEqualTo(ChatPlan.Evidence.UNVERIFIED);
        assertThat(LlmPlanner.evidenceOf(clearing, ContextFacts.EMPTY)).isEqualTo(ChatPlan.Evidence.CONSISTENT);
    }

    /**
     * needsClarification 以前只对写动作生效，读动作带着一个不存在的任务号会直接跑下去。
     * 事实读不到（knowsX 全 false）时这条尤其重要：那时什么都不能证实。
     */
    @Test
    @DisplayName("没核实又带实体的读动作也要先问一句")
    void 没核实的读动作要澄清() {
        ChatPlan plan = ChatPlan.model(
                new ChatIntentRouter.Call(ChatIntent.EXPLAIN_JOB, null, 999L, null, null, null, null),
                0.8, "解释 999 号任务", List.of(), ChatPlan.Evidence.UNVERIFIED);
        ChatPlan clearing = ChatPlan.model(
                new ChatIntentRouter.Call(ChatIntent.LIST_JOBS, null, null, null, null, null, null),
                0.8, "看最近任务", List.of(), ChatPlan.Evidence.UNVERIFIED);

        assertThat(plan.hasEntity()).isTrue();
        assertThat(plan.needsClarification()).isTrue();
        assertThat(clearing.hasEntity()).isFalse();
        assertThat(clearing.needsClarification()).isFalse();
    }

    // ---------------------------------------------------------------- 第二步（批 3 M10）

    private final ContextFacts facts = new ContextFacts(
            "规则 1 条：BILL_VS_SETTLE；任务：1、2", Set.of("BILL_VS_SETTLE"), Set.of(1L, 2L),
            Set.of("bill_left", "bill_right"));

    private ChatPlan listDiffs(long jobId) {
        return ChatPlan.model(
                new ChatIntentRouter.Call(ChatIntent.LIST_DIFFS, null, jobId, null, null, null, null),
                0.9, "看差异", List.of(), ChatPlan.Evidence.CONSISTENT);
    }

    @Test
    @DisplayName("第二步：模型说 DONE 就是没有下一步")
    void 第二步done() {
        assertThat(planner.followUpFrom("{\"action\":\"DONE\"}", facts, listDiffs(1L))).isEmpty();
    }

    @Test
    @DisplayName("第二步：写动作一律不续跑——回执必须等用户点头")
    void 第二步拒绝写动作() {
        assertThat(planner.followUpFrom("""
                {"action":"RUN","args":{"ruleCode":"BILL_VS_SETTLE"},"confidence":0.99}
                """, facts, listDiffs(1L))).isEmpty();
        assertThat(planner.followUpFrom("""
                {"action":"RETRY","args":{"jobId":1},"confidence":0.99}
                """, facts, listDiffs(1L))).isEmpty();
    }

    @Test
    @DisplayName("第二步：不许重复上一步，否则一次请求会做两遍同一件事")
    void 第二步拒绝重复() {
        assertThat(planner.followUpFrom("""
                {"action":"LIST_DIFFS","args":{"jobId":1},"confidence":0.9}
                """, facts, listDiffs(1L))).isEmpty();
    }

    @Test
    @DisplayName("第二步：编造的任务号与缺参数都不执行")
    void 第二步拒绝编造与缺参() {
        // 999 号任务库里没有：证据不是 CONSISTENT
        assertThat(planner.followUpFrom("""
                {"action":"EXPLAIN_JOB","args":{"jobId":999},"confidence":0.9}
                """, facts, listDiffs(1L))).isEmpty();
        // 差异归因必须带 diffId，模型没给就不能替它挑一条
        assertThat(planner.followUpFrom("""
                {"action":"EXPLAIN_DIFF","args":{},"confidence":0.9}
                """, facts, listDiffs(1L))).isEmpty();
    }

    @Test
    @DisplayName("第二步：实体对得上、参数齐、不重复——这一个才放行")
    void 第二步放行合格续跑() {
        ChatPlan next = planner.followUpFrom("""
                {"action":"EXPLAIN_JOB","args":{"jobId":2},"confidence":0.9,"reason":"顺手归因"}
                """, facts, listDiffs(1L)).orElseThrow();
        assertThat(next.call().intent()).isEqualTo(ChatIntent.EXPLAIN_JOB);
        assertThat(next.call().jobId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("贴 YAML 起草新规则：编码不在库里也不算冲突（起草是创建，不是引用）")
    void 起草新规则不被存在性判成冲突() {
        ContextFacts facts = new ContextFacts("", Set.of("BILL_VS_SETTLE"), Set.of(1L),
                Set.of("bill_left", "bill_right"));
        ChatIntentRouter.Call call = new ChatIntentRouter.Call(ChatIntent.DEFINE_RULE,
                "PROBE_DIM_RECON", null, null, null, "code: PROBE_DIM_RECON", null);

        assertThat(LlmPlanner.evidenceOf(call, facts)).isEqualTo(ChatPlan.Evidence.CONSISTENT);
    }

    @Test
    @DisplayName("但引用一个不存在的规则去跑批，仍然是冲突")
    void 引用不存在的规则仍判冲突() {
        ContextFacts facts = new ContextFacts("", Set.of("BILL_VS_SETTLE"), Set.of(1L),
                Set.of("bill_left", "bill_right"));
        ChatIntentRouter.Call call = new ChatIntentRouter.Call(ChatIntent.RUN, "GHOST_RECON",
                null, null, null, null, null);

        assertThat(LlmPlanner.evidenceOf(call, facts)).isEqualTo(ChatPlan.Evidence.CONFLICT);
    }
}
