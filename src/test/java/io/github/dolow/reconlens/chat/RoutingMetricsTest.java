package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.chat.plan.ChatPlan;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由指标的纯单元测试：不起库（jdbc=null），只验证计数与语料窗口。
 */
class RoutingMetricsTest {

    @Test
    @DisplayName("按来源归档：词表/模型/工具/说话各记一笔")
    void 按来源归档() {
        RoutingMetrics metrics = new RoutingMetrics(null);
        metrics.plan(ChatPlan.keyword(new ChatIntentRouter.Call(ChatIntent.LIST_RULES,
                null, null, null, null, null, null), 0.9, "词表"));
        metrics.plan(ChatPlan.tool("recon_list_diffs", Map.of(), 0.9));
        metrics.plan(ChatPlan.reply("好的", 0.8));
        metrics.plan(ChatPlan.model(new ChatIntentRouter.Call(ChatIntent.HELP,
                null, null, null, null, null, null), 0.4, "", null, ChatPlan.Evidence.UNVERIFIED));

        Map<String, Object> snapshot = metrics.snapshot();
        assertThat(snapshot.get("keywordPlans")).isEqualTo(1L);
        assertThat(snapshot.get("toolPlans")).isEqualTo(1L);
        assertThat(snapshot.get("replyPlans")).isEqualTo(1L);
        assertThat(snapshot.get("modelPlans")).isEqualTo(1L);
    }

    @Test
    @DisplayName("fallback 与澄清计数、按意图计数、语料窗口")
    void fallback语料与计数() {
        RoutingMetrics metrics = new RoutingMetrics(null);
        metrics.fallback(7L, "帮我看看那个东西为啥不对");
        metrics.fallback(7L, "给我算一下上季度的总账顺便把明细也拉出来看看");
        metrics.clarify();
        metrics.intent(ChatIntent.HELP);
        metrics.intent(ChatIntent.LIST_RULES);
        metrics.breakerTrip();
        metrics.cacheHit();

        Map<String, Object> snapshot = metrics.snapshot();
        assertThat(snapshot.get("fallbacks")).isEqualTo(2L);
        assertThat(snapshot.get("clarifies")).isEqualTo(1L);
        assertThat(snapshot.get("breakerTrips")).isEqualTo(1L);
        assertThat(snapshot.get("cacheHits")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byIntent = (Map<String, Long>) snapshot.get("byIntent");
        assertThat(byIntent).containsEntry("HELP", 1L).containsEntry("LIST_RULES", 1L);
        @SuppressWarnings("unchecked")
        java.util.List<String> samples = (java.util.List<String>) snapshot.get("fallbackSamples");
        assertThat(samples).hasSize(2);
        assertThat(samples.get(0)).contains("为啥不对");
    }

    @Test
    @DisplayName("语料窗口封顶：超过 50 条丢最老的；空话不进样本")
    void 语料窗口封顶() {
        RoutingMetrics metrics = new RoutingMetrics(null);
        for (int i = 1; i <= 55; i++) {
            metrics.fallback(null, "问题 " + i);
        }
        @SuppressWarnings("unchecked")
        java.util.List<String> samples = (java.util.List<String>) metrics.snapshot().get("fallbackSamples");
        assertThat(samples).hasSize(50);
        assertThat(samples.get(0)).contains("问题 6");
        assertThat(samples.get(49)).contains("问题 55");

        RoutingMetrics noBlank = new RoutingMetrics(null);
        noBlank.fallback(1L, "   ");
        assertThat(noBlank.snapshot().get("fallbacks")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        java.util.List<String> empty = (java.util.List<String>) noBlank.snapshot().get("fallbackSamples");
        assertThat(empty).isEmpty();
    }
}
