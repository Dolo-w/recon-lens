package io.github.dolow.reconlens.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预算护栏的三个维度各自负责什么。
 *
 * <p>这些用例的重点是<b>哪个检查点在拦哪件事</b>：
 * 轮数与墙钟在"开始新一轮之前"拦，token 在"这一轮花完之后"拦 ——
 * 合并成一个检查点会导致两个错误方向（要么第 4 轮已经开始才报错，要么这一轮的巨额输入永远不被计价）。
 */
class AgentBudgetTest {

    @Test
    void refusesToStartMoreRoundsThanConfigured() {
        AgentBudget budget = new AgentBudget(2, 1_000_000, 60);

        budget.requireAnotherRound();
        budget.spendLlm("a", "b");
        budget.requireAnotherRound();
        budget.spendLlm("a", "b");

        assertThatThrownBy(budget::requireAnotherRound)
                .isInstanceOf(AgentBudget.ExceededException.class)
                .hasMessageContaining("轮数");
    }

    @Test
    void tokensAreCountedAsAnUpperBoundOverPromptAndReply() {
        AgentBudget budget = new AgentBudget(5, 10, 60);

        // 提示 6 字符 + 回复 5 字符 = 11 > 10
        assertThatThrownBy(() -> budget.spendLlm("abcdef", "ghijk"))
                .isInstanceOf(AgentBudget.ExceededException.class)
                .hasMessageContaining("token");
    }

    @Test
    void tokenBudgetIsCheckedAfterSpendingNotBefore() {
        AgentBudget budget = new AgentBudget(5, 10, 60);

        // 第一轮必须成功：如果 token 在"开始前"就查，这里会因为 0 > 10 为 false 而通过，
        // 但真实语义是"这一轮花掉 11 才超支" —— 检查点放错位置，用例就分不清这两种行为
        budget.spendLlm("abc", "de");
        assertThat(budget.rounds()).isEqualTo(1);
        assertThat(budget.tokens()).isEqualTo(5);

        assertThatThrownBy(() -> budget.spendLlm("abcdef", "ghijk"))
                .isInstanceOf(AgentBudget.ExceededException.class);
    }

    @Test
    void wallClockIsCheckedOnBothEntryPoints() {
        // 注入一个"开始于 61 秒前"的时钟：不 sleep，也能精确构造"已超 1 分钟的预算"
        AgentBudget expired = new AgentBudget(5, 1_000_000, 60_000L,
                System.nanoTime() - 61_000_000_000L);

        assertThatThrownBy(expired::requireTime)
                .isInstanceOf(AgentBudget.ExceededException.class)
                .hasMessageContaining("墙钟");
        assertThatThrownBy(expired::requireAnotherRound)
                .as("开始新一轮之前也要查墙钟：否则第 2 轮会在已超时的会话里继续发起请求")
                .isInstanceOf(AgentBudget.ExceededException.class)
                .hasMessageContaining("墙钟");
    }

    @Test
    void constructorRejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new AgentBudget(0, 100, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("都必须 ≥ 1");
        assertThatThrownBy(() -> new AgentBudget(3, 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudget(3, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesAreTheSingleSourceOfTheNumbers() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxLlmRounds(2);
        properties.setMaxLlmTokensPerRun(1234);
        properties.setMaxWallClockSeconds(7);

        AgentBudget budget = AgentBudget.of(properties);

        assertThat(budget.maxRounds()).isEqualTo(2);
        assertThat(AgentBudget.estimateTokens(null)).isZero();
        assertThat(AgentBudget.estimateTokens("四个字符")).isEqualTo(4);
    }

    /** 配置项自己也要拦：0 轮等于"禁用了起草却还声称开着 Agent"，不该静默成立。 */
    @Test
    void propertiesRejectZeroRounds() {
        assertThatThrownBy(() -> new AgentProperties().setMaxLlmRounds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-llm-rounds");
    }
}
