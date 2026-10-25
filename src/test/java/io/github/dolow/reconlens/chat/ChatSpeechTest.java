package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.tool.ToolOutcome;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人话回执：事实一个不少，内部路由信息一个不留。
 *
 * <p>守的是「同一个工具、两类读者」：宿主大模型要的是「下一步调哪个工具」，
 * 人只需要知道「建了什么、按什么对、还差哪一步」。把前者的话术直接倒给后者，
 * 用户看到的就是一串函数名与接口路径——明明是成功，读起来像系统报错。
 */
class ChatSpeechTest {

    @Test
    @DisplayName("落草稿回执：编码/版本/维度/比对字段都在，工具名与接口路径都不在")
    void 落草稿回执() {
        ToolOutcome outcome = ToolOutcome.of(
                "规则 X 已落库…下一步 recon_trial_status(...)；POST /api/rule-reviews/X/1/approve",
                Map.of("code", "ORDER_STATUS_RECON", "version", 1, "operator", "alice",
                        "dimension", "order_no+status", "compares", "amount",
                        "trialState", "queued", "runId", 7));

        String text = ChatSpeech.defineRule(outcome);

        assertThat(text).contains("ORDER_STATUS_RECON").contains("草稿 v1")
                .contains("alice").contains("order_no+status").contains("amount")
                .contains("已排队");
        assertThat(text).doesNotContain("recon_").doesNotContain("POST /api").doesNotContain("runId");
    }

    @Test
    @DisplayName("试算被闸门拒绝：把原因说清楚，并说明规则仍是草稿")
    void 试算被拒() {
        String text = ChatSpeech.defineRule(ToolOutcome.of("agent 文本", Map.of(
                "code", "X_RECON", "version", 2, "operator", "bob",
                "dimension", "order_no", "compares", "amount",
                "trialState", "rejected", "trialDetail", "左表缺少 status 列")));

        assertThat(text).contains("没跑成").contains("左表缺少 status 列").contains("草稿");
    }

    @Test
    @DisplayName("没有结构化事实时退回工具文本，但抹掉内部名")
    void 兜底抹内部名() {
        String text = ChatSpeech.defineRule(
                ToolOutcome.text("调 recon_trial_status 或 POST /api/rule-reviews/X/1/approve"));

        assertThat(text).doesNotContain("recon_").doesNotContain("POST /api");
    }

    @Test
    @DisplayName("工具给宿主的「下一步」收尾整行删掉，正文一个字不动")
    void 去掉给宿主的下一步() {
        String raw = """
                任务 #5｜状态 SUCCESS｜扫描 左=10 右=10｜匹配 9｜差异 5
                耗时 3 ms

                下一步：recon_list_diffs 看差异明细，recon_explain_job 生成归因。""";

        String text = ChatSpeech.stripAgentTail(raw);

        assertThat(text).contains("任务 #5").contains("匹配 9").contains("耗时 3 ms")
                .doesNotContain("下一步").doesNotContain("recon_");
    }

    @Test
    @DisplayName("问草稿但一条都没有：直说没有，不把生效规则端上来充数")
    void 问草稿_没有就说没有() {
        String text = ChatSpeech.ruleList(java.util.List.of(
                new io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary(
                        "BILL_VS_SETTLE", 1, io.github.dolow.reconlens.domain.RuleLifecycle.ACTIVE,
                        "账单 vs 结算", "按订单号比金额"),
                new io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary(
                        "CUSTOMER_STATUS_RECON", 1, io.github.dolow.reconlens.domain.RuleLifecycle.ACTIVE,
                        null, null)), true);

        assertThat(text).contains("没有草稿").contains("生效版").contains("BILL_VS_SETTLE");
        assertThat(text).doesNotContain("recon_").doesNotContain("调 ");
    }

    @Test
    @DisplayName("问草稿且确有草稿：只列未生效的那几条，并注明状态")
    void 问草稿_只列草稿() {
        String text = ChatSpeech.ruleList(java.util.List.of(
                new io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary(
                        "BILL_VS_SETTLE", 1, io.github.dolow.reconlens.domain.RuleLifecycle.ACTIVE, null, null),
                new io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary(
                        "ORDER_STATUS_RECON", 2, io.github.dolow.reconlens.domain.RuleLifecycle.DRAFT,
                        "订单状态核对", null)), true);

        assertThat(text).contains("还没生效").contains("ORDER_STATUS_RECON v2").contains("草稿")
                .contains("其余 1 条是生效版");
        assertThat(text).doesNotContain("BILL_VS_SETTLE v1");
    }
}
