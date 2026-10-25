package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 词表不仅要给"意图"，还要给"有多确定"。
 *
 * <p>这组用例守的是一个具体的错：{@code 规则} 是名词，出现它不代表用户要看规则目录。
 * 如果词表对这种命中一律打 0.78，{@code CompositePlanner} 就会认为"已经确定了"，
 * 模型连复核的机会都没有 —— 线上表现是「起草个规则」被答成「当前库里有哪几条规则」。
 * 所以这里的断言不是"分类对不对"，而是**置信度有没有低于 0.7 这条线**。
 */
class KeywordPlannerTest {

    private final KeywordPlanner planner = new KeywordPlanner(new ChatIntentRouter());

    @Test
    @DisplayName("明确问清单时保持高置信度，不走模型")
    void 明确问清单保持高置信度() {
        assertThat(planner.plan("有哪些规则", ChatSlots.EMPTY).orElseThrow().confidence())
                .isEqualTo(KeywordPlanner.HIT);
        assertThat(planner.plan("规则列表", ChatSlots.EMPTY).orElseThrow().confidence())
                .isEqualTo(KeywordPlanner.HIT);
    }

    @Test
    @DisplayName("只提到\"规则\"两个字就降权，把判断权交给模型")
    void 只提到规则就降权() {
        ChatPlan plan = planner.plan("规则这块我还想再调调", ChatSlots.EMPTY).orElseThrow();
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(plan.confidence()).isEqualTo(KeywordPlanner.NOUN_ONLY).isLessThan(0.7);
        assertThat(plan.reason()).contains("话题词");
    }

    @Test
    @DisplayName("起草是动词：命中即高置信度，不需要模型复核")
    void 起草命中即高置信度() {
        ChatPlan plan = planner.plan("起草个规则：订单号和状态对账", ChatSlots.EMPTY).orElseThrow();
        assertThat(plan.call().intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(plan.confidence()).isEqualTo(KeywordPlanner.HIT);
    }

    @Test
    @DisplayName("完全没命中时仍是低置信度，别硬凑一个动作")
    void 没命中是低置信度() {
        assertThat(planner.plan("嗯嗯好的", ChatSlots.EMPTY).orElseThrow().confidence())
                .isEqualTo(KeywordPlanner.MISS);
    }
}
