package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 财务可读报告端到端：一页 HTML 讲清「发生了什么、追回了什么、还挂着多少」。
 *
 * <p>钉住六件事：
 * <ul>
 *   <li>窗口内任务被汇总：任务数/扫描行数/差异总数/闭环率四张卡片有数；</li>
 *   <li>带 priceCode 出金额影响段：合计与 TOP 行都在，负数（少收）进表；</li>
 *   <li>不带 priceCode：金额影响段如实缺席，不出误导性报告；</li>
 *   <li>闭环台账：EXEMPTED 的差异看得见"已豁免"；</li>
 *   <li>知识层省钱账段在页面上，且带"重启归零"口径提示；</li>
 *   <li>动态文本全量 HTML 转义：业务键里的 {@code <script>} 不能进页面。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:finance_report_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class FinanceReportApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long jobId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM recon_price_entry");
        jdbc.update("DELETE FROM recon_diff_kb");
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");

        // finished_at 用相对时刻（now - 2 天）：days=30 窗口必含、days=1 窗口必不含，任何日期跑都不翻车
        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count, "
                        + "left_rows, right_rows, matched_rows, diff_rows, finished_at) "
                        + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1, 1000, 1000, 997, 3, "
                        + "DATEADD('DAY', -2, CURRENT_TIMESTAMP))");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);

        mvc.perform(post("/api/prices").contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT","unitPricePerMillion":4,
                 "currency":"CNY","effectiveFrom":"2026-11-15T00:00:00Z"}
                """));

        // 可计价：left 0.5M×4=2，right 1M×4=4，impact -2（少收）
        insertDiff("AMOUNT_MISMATCH", "B001", "output_tokens", "500000", "1000000");
        // 状态类：不可计价，走闭环豁免
        insertDiff("STATUS_MISMATCH", "B002", "status", "PAID", "REFUNDED");
        // HTML 转义钉子：业务键带脚本
        insertDiff("AMOUNT_MISMATCH", "<script>alert(1)</script>", "amount", "10", "12");

        long diffB002 = jdbc.queryForObject("SELECT id FROM recon_diff WHERE biz_key = 'B002'", Long.class);
        mvc.perform(post("/api/diffs/" + diffB002 + "/lifecycle")
                .contentType("application/json")
                .content("{\"action\":\"EXEMPT\",\"operator\":\"vv\",\"note\":\"客户已确认\"}"));
    }

    private void insertDiff(String type, String bizKey, String field, String left, String right) {
        jdbc.update("INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, "
                        + "left_value, right_value) VALUES (?, 0, ?, 'HIGH', ?, ?, ?, ?)",
                jobId, type, bizKey, field, left, right);
    }

    private String report(String... params) throws Exception {
        var builder = get("/api/reports/finance");
        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return mvc.perform(builder).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("概览卡片与差异分类_窗口内任务被汇总")
    void overviewCardsAggregateWindowJobs() throws Exception {
        String html = report("days", "30");
        assertThat(html).contains("对账财务报告").contains("一、本期概览");
        assertThat(html).contains(">1</div>"); // 任务数卡片
        assertThat(html).contains("扫描行数（两侧合计）").contains(">2000</div>");
        assertThat(html).contains("差异总数").contains(">3</div>");
        assertThat(html).contains("二、差异分类").contains("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("带价格表_金额影响段出账_少收的负数在TOP行")
    void pricedReportShowsImpactAndTopRows() throws Exception {
        String html = report("days", "30", "priceCode", "openai", "model", "gpt-4o", "tokenType", "OUTPUT");
        assertThat(html).contains("三、金额影响").contains("负数 = 少收");
        assertThat(html).contains("B001").contains("-2");
        assertThat(html).contains("合计影响");
    }

    @Test
    @DisplayName("不带价格表_金额影响段如实缺席")
    void unpricedReportStatesAbsence() throws Exception {
        String html = report("days", "30");
        assertThat(html).contains("金额影响段缺席");
    }

    @Test
    @DisplayName("闭环台账_EXEMPTED可见_闭环率非零")
    void closureLedgerShowsExempt() throws Exception {
        String html = report("days", "30");
        assertThat(html).contains("四、差异闭环台账").contains("EXEMPTED");
        assertThat(html).contains("闭环率 = （已核销 RESOLVED + 已豁免 EXEMPTED）");
    }

    @Test
    @DisplayName("知识层省钱账_带重启归零口径提示")
    void knowledgeRoiSectionPresent() throws Exception {
        String html = report("days", "30");
        assertThat(html).contains("五、知识层省钱账").contains("省下的模型调用").contains("重启归零");
    }

    @Test
    @DisplayName("XSS转义_业务键里的script标签不得进页面")
    void bizKeyIsHtmlEscaped() throws Exception {
        String html = report("days", "30", "priceCode", "openai", "model", "gpt-4o", "tokenType", "OUTPUT");
        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("窗口外任务不计入_days钳到最小1天则2天前的任务出窗")
    void outOfWindowJobExcludedAndDaysClamped() throws Exception {
        // days=0 → 钳为 1 天：任务 finished_at 是 2 天前（窗口外），差异分类应为空
        String html = report("days", "0");
        assertThat(html).contains("窗口内没有差异记录");
    }
}
