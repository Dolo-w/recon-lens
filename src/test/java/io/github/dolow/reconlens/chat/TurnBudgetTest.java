package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.llm.LlmCircuitBreaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预算账本与熔断账本的守卫测试。
 *
 * <p>这两个类本身没有"业务正确性"可言，它们守的是一件事：<b>口径只有一个</b>。
 * 之前全仓有三种尺子（PromptGuard 按字符、摘要按 window×0.55、step payload 按字符），
 * 于是"压过了"和"压对了"是两回事。这组测试钉的就是这个分歧不许回来。
 */
class TurnBudgetTest {

    @Test
    @DisplayName("单段超自己那桶：只截它自己，别的段原样保留")
    void truncatesOnlyTheOffendingSection() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("system", "系统规则很短");
        sections.put("history", "历".repeat(4000));   // 中文按 1 token/字 → 4000 token，超过 1500

        TurnBudget.Result r = TurnBudget.assemble(sections);

        assertThat(r.cuts()).containsExactly("history");
        assertThat(r.text()).contains("系统规则很短");
        assertThat(TurnBudget.estimate(r.text())).isLessThan(TurnBudget.TOTAL);
    }

    @Test
    @DisplayName("总量超了：先砍 tool（最不重要），再砍 summary")
    void cutsInDeclaredOrder() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("system", "系".repeat(700));
        sections.put("facts", "事".repeat(1100));
        sections.put("history", "史".repeat(1400));
        sections.put("tool", "具".repeat(1900));   // 合计 5100 < TOTAL，先确认没超

        TurnBudget.Result ok = TurnBudget.assemble(sections);
        assertThat(ok.truncated()).isFalse();

        sections.put("summary", "摘".repeat(600));  // 合计 5700 < TOTAL
        sections.put("tool", "具".repeat(2400));    // 单段超 2000 桶 → 先被截
        TurnBudget.Result r = TurnBudget.assemble(sections);
        assertThat(r.cuts()).contains("tool");
    }

    @Test
    @DisplayName("空段不占位置、不产生截断记录")
    void blankSectionsAreSkipped() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("system", "规则");
        sections.put("summary", "");
        sections.put("tool", null);

        TurnBudget.Result r = TurnBudget.assemble(sections);

        assertThat(r.truncated()).isFalse();
        assertThat(r.text()).isEqualTo("规则");
        assertThat(r.used()).containsOnlyKeys("system");
    }

    @Test
    @DisplayName("中文按 1 token/字、英文按 4 字符/token —— 全仓只认这一个估算")
    void oneEstimatorForEverything() {
        assertThat(TurnBudget.estimate("12345678")).isEqualTo(2);
        assertThat(TurnBudget.estimate("八个中文字符啊")).isEqualTo(7);
        assertThat(ChatSummarizer.estimateTokens("八个中文字符啊")).isEqualTo(TurnBudget.estimate("八个中文字符啊"));
    }

    @Test
    @DisplayName("fit 超限时留明确的截断标记，不静默丢内容")
    void fitLeavesAMarker() {
        String text = "字".repeat(100);
        String fitted = TurnBudget.fit(text, 20);
        assertThat(fitted).hasSizeLessThan(text.length());
        assertThat(fitted).contains("…");
    }

    @Test
    @DisplayName("理解档：事实与历史各有一份更大的额度，同一段内容在理解档不截、在执行档才截")
    void understandingPhaseGivesMoreRoom() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("facts", "事".repeat(1800));    // 执行档 facts 桶 1200 装不下
        sections.put("history", "史".repeat(3900));  // 执行档 history 桶 1500 装不下

        TurnBudget.Result acting = TurnBudget.assemble(sections, TurnBudget.Phase.ACTING);
        assertThat(acting.cuts()).contains("facts", "history");

        TurnBudget.Result understanding = TurnBudget.assemble(sections, TurnBudget.Phase.UNDERSTANDING);
        assertThat(understanding.cuts()).isEmpty();
        assertThat(understanding.text()).contains("事".repeat(1800)).contains("史".repeat(3900));
    }

    @Test
    @DisplayName("理解档的历史：超限时整轮丢弃，绝不留下半句")
    void roundsDropWholeTurnsInsteadOfHalving() {
        java.util.List<String> turns = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            turns.add("第" + i + "轮：" + "话".repeat(1200));
        }

        TurnBudget.History kept = TurnBudget.rounds(turns, TurnBudget.Phase.UNDERSTANDING.history());

        assertThat(kept.kept()).isEqualTo(3);        // 3×1201 ≈ 3603，第 4 轮就超 4000
        assertThat(kept.dropped()).isEqualTo(7);
        assertThat(kept.truncated()).isTrue();
        assertThat(kept.text()).doesNotContain(TurnBudget.CUT_MARK);
        // 留下来的每一轮都是完整的：从头到尾都在，不是被砍到一半
        assertThat(kept.text()).contains("第7轮：" + "话".repeat(1200));
        assertThat(kept.text()).contains("第9轮：" + "话".repeat(1200));
        assertThat(kept.text()).doesNotContain("第6轮");
    }

    @Test
    @DisplayName("会话账本：累加到线自动收紧档位，忘掉会话后从头算")
    void ledgerSwitchesPhaseWhenItGetsTight() {
        TurnLedger ledger = new TurnLedger();
        long session = 7L;

        assertThat(ledger.phaseOf(session)).isEqualTo(TurnBudget.Phase.UNDERSTANDING);
        assertThat(ledger.add(session, TurnLedger.TIGHT_AT - 1)).isEqualTo(TurnLedger.TIGHT_AT - 1);
        assertThat(ledger.phaseOf(session)).isEqualTo(TurnBudget.Phase.UNDERSTANDING);
        assertThat(ledger.add(session, 1)).isEqualTo(TurnLedger.TIGHT_AT);
        assertThat(ledger.phaseOf(session)).isEqualTo(TurnBudget.Phase.ACTING);
        // 负数/零不进账，也不把累计值打回去
        assertThat(ledger.add(session, 0)).isEqualTo(TurnLedger.TIGHT_AT);

        ledger.forget(session);
        assertThat(ledger.used(session)).isZero();
        assertThat(ledger.phaseOf(session)).isEqualTo(TurnBudget.Phase.UNDERSTANDING);
    }

    @Test
    @DisplayName("熔断器：3 次失败后关闸，成功一次立刻恢复；路由/归因/起草共用同一套语义")
    void breakerOpensAndRecovers() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker("测试", 3, 60_000L);
        assertThat(breaker.allow()).isTrue();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.allow()).isTrue();
        breaker.onFailure();
        assertThat(breaker.allow()).isFalse();
        assertThat(breaker.state()).startsWith("熔断中");
        breaker.onSuccess();
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.state()).isEqualTo("正常");
    }
}
