package io.github.dolow.reconlens.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 工具层测试：Agent 会看到的就是这些文本，所以断言直接盯着人话。
 * 演示数据（data.sql）里埋了 6 条差异：2 金额 + 1 状态 + 1 右表缺失 + 1 左表缺失 + 1 重复。
 */
@SpringBootTest(properties = "recon.engine.run-demo-on-startup=false")
class ReconMcpToolsTest {

    private static final Pattern JOB_ID = Pattern.compile("任务 #(\\d+)");
    private static final Pattern DIFF_ID = Pattern.compile("^#(\\d+)", Pattern.MULTILINE);

    @Autowired
    private ReconMcpTools tools;

    @Test
    void listRulesTellsTheAgentWhatItCanRun() {
        String text = tools.listRules();

        assertThat(text).contains("已加载 2 条对账规则")
                .contains("BILL_VS_SETTLE").contains("CUSTOMER_SUM_RECON")
                .contains("左侧：").contains("右侧：")
                .contains("比对字段：amount、status")
                .contains("分片 4，并行 4");
    }

    @Test
    void runReturnsSummaryAndDiffsCanBeListedAndExplained() {
        long jobId = jobIdOf(tools.runReconciliation("BILL_VS_SETTLE", "mcp-it", "MCP 工具测试"));

        String summary = tools.jobSummary(jobId);
        assertThat(summary).contains("规则 BILL_VS_SETTLE")
                .contains("批次 mcp-it")
                .contains("差异分类：AMOUNT_MISMATCH=2")
                .contains("MISSING_RIGHT=1")
                .contains("分片明细：");

        String all = tools.listDiffs(jobId, null, 0, 10);
        assertThat(all).contains("差异共 6 条")
                .contains("业务键=ORD20260003")
                .contains("字段=amount")
                .contains("相对偏差");

        String missingOnly = tools.listDiffs(jobId, "MISSING_RIGHT", 0, 10);
        assertThat(missingOnly).contains("只看 MISSING_RIGHT").contains("差异共 1 条");

        String jobExplain = tools.explainJob(jobId, 10);
        assertThat(jobExplain).contains("已生成 6 条归因").contains("解释器=");

        long diffId = diffIdOf(all);
        String one = tools.explainDiff(diffId);
        assertThat(one).contains("差异 #" + diffId).contains("的归因，解释器=");
    }

    @Test
    void unknownInputsAreReportedInPlainWords() {
        assertThatThrownBy(() -> tools.runReconciliation("NO_SUCH_RULE", null, null))
                .hasMessageContaining("未找到规则 NO_SUCH_RULE");
        assertThatThrownBy(() -> tools.jobSummary(999_999L))
                .hasMessageContaining("任务不存在：999999");
        assertThatThrownBy(() -> tools.explainDiff(999_999L))
                .hasMessageContaining("差异不存在：999999");
    }

    private static long jobIdOf(String text) {
        Matcher matcher = JOB_ID.matcher(text);
        assertThat(matcher.find()).as("工具输出里应带任务号：%s", text).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static long diffIdOf(String text) {
        Matcher matcher = DIFF_ID.matcher(text);
        assertThat(matcher.find()).as("差异清单里应有编号：%s", text).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
