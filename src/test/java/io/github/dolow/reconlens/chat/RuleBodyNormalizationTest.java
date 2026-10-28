package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 「贴了 YAML 却被反问业务键」的回归测试。
 *
 * <p>背景：模型路由（{@code LlmPlanner}）只产出意图和槽位，不带规则正文；
 * 用户消息里的 YAML 会在 {@code defineRule} 入口被 {@link ChatRuntime#withLocalRuleBody} 补回来。
 * 这四个用例把"谁负责正文"钉死：正文永远来自本地提取器，不来自模型。
 */
class RuleBodyNormalizationTest {

    private static final String YAML = """
            贴一份 YAML 规则
            code: PROBE_DIM_RECON
            name: 维度探针
            left:
              label: 账单
              table: bill_left
              dimension: [customer_id, order_no]
            right:
              label: 结算
              table: bill_right
              dimension: [customer_id, order_no]
            compare:
              - field: amount
                type: DECIMAL
                diffType: AMOUNT_MISMATCH
            """;

    @Test
    void 模型路由丢了正文时由本地提取器补回() {
        ChatIntentRouter.Call planned = new ChatIntentRouter.Call(ChatIntent.DEFINE_RULE,
                null, null, null, null, null, null);

        ChatIntentRouter.Call effective = ChatRuntime.withLocalRuleBody(planned, YAML);

        assertThat(effective.ruleBody()).isNotNull();
        // 正文必须从 code: 开始——前面那句"贴一份 YAML 规则"要剥掉，否则 YAML 解析器会报
        // "mapping values are not allowed here"
        assertThat(effective.ruleBody()).startsWith("code: PROBE_DIM_RECON");
        assertThat(effective.ruleBody()).doesNotContain("贴一份");
        // 维度必须原样在正文里——丢了它就等于把业务键换掉了
        assertThat(effective.ruleBody()).contains("dimension: [customer_id, order_no]");
        assertThat(effective.intent()).isEqualTo(ChatIntent.DEFINE_RULE);
    }

    @Test
    void 已带正文的调用不被覆盖() {
        ChatIntentRouter.Call withBody = new ChatIntentRouter.Call(ChatIntent.DEFINE_RULE,
                "MY_RULE", null, null, null, "code: MY_RULE\nleft: {}\nright: {}", null);

        ChatIntentRouter.Call effective = ChatRuntime.withLocalRuleBody(withBody, YAML);

        assertThat(effective).isSameAs(withBody);
        assertThat(effective.ruleBody()).doesNotContain("PROBE_DIM_RECON");
    }

    @Test
    void 不是起草规则时一律不动() {
        ChatIntentRouter.Call listRules = new ChatIntentRouter.Call(ChatIntent.LIST_RULES,
                null, null, null, null, null, null);

        assertThat(ChatRuntime.withLocalRuleBody(listRules, YAML)).isSameAs(listRules);
    }

    @Test
    void 普通对话没有YAML就保持为空走槽位追问() {
        ChatIntentRouter.Call planned = new ChatIntentRouter.Call(ChatIntent.DEFINE_RULE,
                null, null, null, null, null, null);

        ChatIntentRouter.Call effective = ChatRuntime.withLocalRuleBody(planned, "帮我建一条按客户号对金额的规则");

        assertThat(effective.ruleBody()).isNull();
    }
}
