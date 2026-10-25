package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人话总结这一层的行为契约。
 *
 * <p>它最关键的性质不是"总结得好不好"，而是<b>不可用时一个字都不能改</b>：
 * 没有 key 的部署必须和 P1 完全一样，否则"模型可拔掉"这句话就是假的。
 */
class ChatNarratorTest {

    private static ChatNarrator narrator(String apiKey, boolean enabled) {
        return new ChatNarrator(new LlmChatClient("https://api.deepseek.com", apiKey,
                "deepseek-chat", 1000, new ObjectMapper()), enabled);
    }

    @Test
    @DisplayName("没配 key：原样返回，不联网、不改写")
    void 没配key原样返回() {
        String raw = "规则库里有 3 条对账规则";
        assertThat(narrator("", true).active()).isFalse();
        assertThat(narrator("", true).decorate(ChatIntent.LIST_RULES, "有哪些规则", raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("显式关掉：即使有 key 也不介入")
    void 关掉就不介入() {
        String raw = "任务 #3｜差异 4 条";
        assertThat(narrator("sk-test", false).active()).isFalse();
        assertThat(narrator("sk-test", false).decorate(ChatIntent.JOB_SUMMARY, "结果怎么样", raw))
                .isEqualTo(raw);
    }

    @Test
    @DisplayName("对话式动作不重写：反问和帮助本来就是人话")
    void 对话式动作不重写() {
        ChatNarrator active = narrator("sk-test", true);
        assertThat(active.active()).isTrue();
        String raw = "要跑哪条规则？现在规则库里有：BILL_VS_SETTLE";
        assertThat(active.decorate(ChatIntent.HELP, "跑一下", raw)).isEqualTo(raw);
        assertThat(active.decorate(ChatIntent.DEFINE_RULE, "起草个规则", raw)).isEqualTo(raw);
        assertThat(active.decorate(ChatIntent.APPROVE, "我要批准", raw)).isEqualTo(raw);
    }
}
