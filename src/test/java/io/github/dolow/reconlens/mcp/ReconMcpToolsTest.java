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
 * 演示数据（data.sql）里埋了 4 条差异：1 金额超差 + 1 右表缺失 + 1 左表缺失 + 1 重复；
 * 另有 1 条容差内（相对偏差 0.005% < 0.01%）不报。
 */
@SpringBootTest(properties = {
        // 独立库名：agent 开启会新建一个 Spring 上下文，共享默认 H2 会重跑 data.sql 撞主键
        "spring.datasource.url=jdbc:h2:mem:mcp_tools;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        // recon_define_rule 落草稿后要自动跑影子试算，试算服务挂在 agent 开关下 ——
        // 生产 mcp profile 本来就是 enabled=true，这里与生产一致
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right"
})
class ReconMcpToolsTest {

    private static final Pattern JOB_ID = Pattern.compile("任务 #(\\d+)");
    private static final Pattern DIFF_ID = Pattern.compile("^#(\\d+)", Pattern.MULTILINE);

    @Autowired
    private ReconMcpTools tools;

    @Autowired
    private io.github.dolow.reconlens.rule.RuleLifecycleService lifecycle;

    @Autowired
    private io.github.dolow.reconlens.store.RuleRepository rules;

    @Test
    void listRulesTellsTheAgentWhatItCanRun() {
        String text = tools.listRules();

        // 不断言精确条数：测试之间共享同一个 H2 实例（DB_CLOSE_DELAY=-1），别的用例也会往规则库里加规则
        assertThat(text).containsPattern("规则库里有 \\d+ 条对账规则")
                .contains("BILL_VS_SETTLE").contains("CUSTOMER_SUM_RECON")
                .contains("CUSTOMER_STATUS_RECON")
                .contains("左侧：").contains("右侧：")
                .contains("比对字段：amount")
                .contains("分片 2，并行 2")
                .contains("recon_define_rule");
    }

    @Test
    void defineRuleStoresANewVersionAndRunsWithAnInlineRule() {
        String defined = tools.defineRule("""
                code: MCP_DEFINED_RULE
                name: MCP 定义的规则
                left:
                  label: 账单
                  table: bill_left
                  keyType: STRING
                  dimension: [customer_id, order_no]
                right:
                  label: 结算
                  table: bill_right
                  keyType: STRING
                  dimension: [customer_id, order_no]
                shard:
                  count: 1
                  parallelism: 1
                compare:
                  - field: amount
                    type: DECIMAL
                    tolerance: 0.2
                    diffType: FEE_MISMATCH
                    severity: HIGH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                  severity: HIGH
                """, "agent");

        // 试算改成后台排队（不再阻塞 MCP 会话），所以这里等后台把结论落库再审批
        assertThat(defined).contains("已落库为草稿 v").contains("影子试算已排队")
                .contains("recon_trial_status").contains("草稿不生效");

        // 草稿未审批：recon_run 用不了这条规则
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("草稿 v(\\d+)").matcher(defined);
        assertThat(v.find()).isTrue();
        int version = Integer.parseInt(v.group(1));
        assertThatThrownBy(() -> tools.runReconciliation("MCP_DEFINED_RULE", null, "mcp-blocked", "未审批不能跑"))
                .isInstanceOf(Exception.class);

        awaitTrial("MCP_DEFINED_RULE", version);

        // 走完审批（与 REST 语义一致的进程内三步），规则才生效、才能跑
        lifecycle.submit("MCP_DEFINED_RULE", version, "tester");
        String token = lifecycle.issueToken("MCP_DEFINED_RULE", version);
        lifecycle.approve("MCP_DEFINED_RULE", version, "tester", token, "测试审批");
        String summary = tools.jobSummary(jobIdOf(tools.runReconciliation("MCP_DEFINED_RULE", null, "mcp-rule", "跑新规则")));
        assertThat(summary).contains("规则 MCP_DEFINED_RULE").contains("FEE_MISMATCH=1");

        String inline = tools.runReconciliation(null, """
                {
                  "code": "MCP_INLINE_RULE",
                  "left": {"table": "bill_left", "keyField": "order_no"},
                  "right": {"table": "bill_right", "keyField": "order_no"},
                  "shard": {"count": 1, "parallelism": 1},
                  "compare": [{"field": "amount", "type": "DECIMAL", "tolerance": 0.01,
                               "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"}],
                  "missing": {"leftMissingDiffType": "MISSING_LEFT", "rightMissingDiffType": "MISSING_RIGHT"}
                }
                """, "mcp-inline", "临时试算");
        assertThat(inline).contains("规则 MCP_INLINE_RULE（内联规则）").contains("AMOUNT_MISMATCH=2");
    }

    @Test
    void runReturnsSummaryAndDiffsCanBeListedAndExplained() {
        long jobId = jobIdOf(tools.runReconciliation("BILL_VS_SETTLE", null, "mcp-it", "MCP 工具测试"));

        String summary = tools.jobSummary(jobId);
        assertThat(summary).contains("规则 BILL_VS_SETTLE")
                .contains("批次 mcp-it")
                .contains("差异分类：AMOUNT_MISMATCH=1")
                .contains("MISSING_RIGHT=1")
                .contains("分片明细：");

        String all = tools.listDiffs(jobId, null, 0, 10);
        assertThat(all).contains("差异共 4 条")
                .contains("业务键=C002|ORD20260003")
                .contains("字段=amount")
                .contains("相对偏差");

        String missingOnly = tools.listDiffs(jobId, "MISSING_RIGHT", 0, 10);
        assertThat(missingOnly).contains("只看 MISSING_RIGHT").contains("差异共 1 条");

        String jobExplain = tools.explainJob(jobId, 10);
        assertThat(jobExplain).contains("已生成 4 条归因").contains("解释器=");

        long diffId = diffIdOf(all);
        String one = tools.explainDiff(diffId);
        assertThat(one).contains("差异 #" + diffId).contains("的归因，解释器=");
    }

    @Test
    void unknownInputsAreReportedInPlainWords() {
        assertThatThrownBy(() -> tools.runReconciliation("NO_SUCH_RULE", null, null, null))
                .hasMessageContaining("未找到规则 NO_SUCH_RULE");
        assertThatThrownBy(() -> tools.jobSummary(999_999L))
                .hasMessageContaining("任务不存在：999999");
        assertThatThrownBy(() -> tools.explainDiff(999_999L))
                .hasMessageContaining("差异不存在：999999");
    }

    /**
     * 后台试算是异步的：试算开始时会先落一条 verdict 为空的记录（表示"进行中"），
     * 等到结论（verdict 非空）落库才能审批，否则 approve 会以"还没有试算结论"被拒。
     */
    private void awaitTrial(String code, int version) {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            boolean done = rules.findTrial(code, version)
                    .map(record -> record.verdict() != null && !record.verdict().isBlank())
                    .orElse(false);
            if (done) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待后台试算被中断", e);
            }
        }
        throw new AssertionError("后台试算 90 秒内没有落库：" + code + " v" + version);
    }

    private static long jobIdOf(String text) {        Matcher matcher = JOB_ID.matcher(text);
        assertThat(matcher.find()).as("工具输出里应带任务号：%s", text).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static long diffIdOf(String text) {
        Matcher matcher = DIFF_ID.matcher(text);
        assertThat(matcher.find()).as("差异清单里应有编号：%s", text).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
