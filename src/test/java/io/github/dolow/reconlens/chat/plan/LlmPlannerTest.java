package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.ChatIntent;
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
    @DisplayName("没有结论板时按旧规则截断")
    void 纯摘要照旧截断() {
        String fitted = LlmPlanner.fitContext("x".repeat(2000));
        assertThat(fitted).endsWith("…");
        assertThat(fitted.length()).isLessThanOrEqualTo(1210);

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
}
