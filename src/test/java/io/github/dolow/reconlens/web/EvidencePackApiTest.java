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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 争议证据包导出端到端：差异 + 原始行凭证 + 适用价格，一份可直发客户的 CSV。
 *
 * <p>钉住五件事：
 * <ul>
 *   <li>一张行凑齐三样证据：差异本身 / 当时两侧整行 JSON / 适用价格与生效窗；</li>
 *   <li>计价列只在带 priceCode 时出现数据；状态类差异计价列留空，不整单失败；</li>
 *   <li>生命周期状态进包（EXEMPTED 的差异在对质时要能看出"这条已豁免"）；</li>
 *   <li>CSV 转义：逗号/引号/换行（凭证 JSON 里全都有）不破坏列结构；</li>
 *   <li>fail-loud：任务不存在 / at 格式错 → 400 说人话。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:evidence_pack_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class EvidencePackApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private long jobId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM recon_price_entry");
        jdbc.update("DELETE FROM recon_diff_evidence");
        jdbc.update("DELETE FROM recon_diff_lifecycle");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_job");

        jdbc.update("INSERT INTO recon_job (rule_code, batch_id, status, kind, shard_count, finished_at) "
                + "VALUES ('bill_match', 'b1', 'SUCCESS', 'PROD', 1, '2026-11-16 10:00:00')");
        jobId = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);

        mvc.perform(post("/api/prices").contentType("application/json").content("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"OUTPUT","unitPricePerMillion":4,
                 "currency":"CNY","effectiveFrom":"2026-11-15T00:00:00Z"}
                """));

        // 数值漂移差异（可计价）：left 0.5M×4=2，right 1M×4=4，impact -2（少收）
        insertDiff("AMOUNT_MISMATCH", "B001", "output_tokens", "500000", "1000000");
        // 状态类差异（不可计价 → 计价列留空）
        insertDiff("STATUS_MISMATCH", "B002", "status", "PAID", "REFUNDED");
        // 业务键含逗号+引号：CSV 转义钉子
        insertDiff("AMOUNT_MISMATCH", "B,\"003", "output_tokens", "2000000", "1900000");

        insertEvidence("B001", "LEFT", "recon_left.orders", "{\"order_id\":\"B001\",\"amount\":500000}");
        insertEvidence("B001", "RIGHT", "lago.fees", "{\"fee\":\"B001\", \"note\":\"多行\n换行\"}");
        // B002 豁免：包里要能看出这条已豁免
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

    private void insertEvidence(String bizKey, String side, String source, String rowJson) {
        jdbc.update("INSERT INTO recon_diff_evidence (job_id, shard_id, side, biz_key, source, row_json) "
                + "VALUES (?, 0, ?, ?, ?, ?)", jobId, side, bizKey, source, rowJson);
    }

    /** 极简 RFC4180 解析：全字段引号包裹，"" 翻倍转义。 */
    private static List<List<String>> parseCsv(String text) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                // 忽略，等 \n
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    @Test
    @DisplayName("证据包_差异凭证价格三样齐活_计价列随priceCode出现")
    void packCombinesDiffEvidenceAndPrice() throws Exception {
        byte[] bytes = mvc.perform(get("/api/jobs/" + jobId + "/evidence-pack.csv")
                        .param("priceCode", "openai").param("model", "gpt-4o").param("tokenType", "OUTPUT"))
                .andReturn().getResponse().getContentAsByteArray();

        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text.startsWith("\uFEFF")).as("UTF-8 BOM").isTrue();
        List<List<String>> rows = parseCsv(text);
        assertThat(rows).hasSize(4); // 表头 + 3 差异
        assertThat(rows.get(0)).contains("差异ID", "业务键", "凭证JSON LEFT", "金额影响", "价格生效起");

        List<String> b001 = rows.stream().filter(r -> r.size() > 6 && "B001".equals(r.get(6)))
                .findFirst().orElseThrow();
        assertThat(b001.get(rows.get(0).indexOf("我方值(left)"))).isEqualTo("500000");
        assertThat(b001.get(rows.get(0).indexOf("凭证来源LEFT"))).isEqualTo("recon_left.orders");
        assertThat(b001.get(rows.get(0).indexOf("凭证JSON LEFT"))).contains("\"order_id\":\"B001\"");
        assertThat(new java.math.BigDecimal(b001.get(rows.get(0).indexOf("单价(每百万)"))))
                .isEqualByComparingTo("4"); // DECIMAL 带标度，比数值不比字面量
        // 金额列全带 8 位标度（计价 HALF_UP(8)），比数值不比字面量
        assertThat(new java.math.BigDecimal(b001.get(rows.get(0).indexOf("我方金额"))))
                .isEqualByComparingTo("2");
        assertThat(new java.math.BigDecimal(b001.get(rows.get(0).indexOf("对方金额"))))
                .isEqualByComparingTo("4");
        assertThat(new java.math.BigDecimal(b001.get(rows.get(0).indexOf("金额影响"))))
                .isEqualByComparingTo("-2");
        assertThat(b001.get(rows.get(0).indexOf("价格生效起"))).isEqualTo("2026-11-15T00:00:00Z");
    }

    @Test
    @DisplayName("状态类差异_计价列留空_生命周期EXEMPTED进包")
    void unpricedDiffLeavesPriceColumnsEmptyAndLifecycleShown() throws Exception {
        byte[] bytes = mvc.perform(get("/api/jobs/" + jobId + "/evidence-pack.csv")
                        .param("priceCode", "openai").param("model", "gpt-4o").param("tokenType", "OUTPUT"))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = parseCsv(new String(bytes, StandardCharsets.UTF_8));
        int statusCol = rows.get(0).indexOf("生命周期状态");
        int priceCol = rows.get(0).indexOf("单价(每百万)");
        int impactCol = rows.get(0).indexOf("金额影响");

        List<String> b002 = rows.stream().filter(r -> r.size() > 6 && "B002".equals(r.get(6)))
                .findFirst().orElseThrow();
        assertThat(b002.get(statusCol)).isEqualTo("EXEMPTED");
        assertThat(b002.get(priceCol)).isEmpty();
        assertThat(b002.get(impactCol)).isEmpty();

        List<String> b001 = rows.stream().filter(r -> r.size() > 6 && "B001".equals(r.get(6)))
                .findFirst().orElseThrow();
        assertThat(b001.get(statusCol)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("CSV转义_键与凭证JSON里的逗号引号换行不破列")
    void csvEscapingSurvivesCommasQuotesAndNewlines() throws Exception {
        byte[] bytes = mvc.perform(get("/api/jobs/" + jobId + "/evidence-pack.csv"))
                .andReturn().getResponse().getContentAsByteArray();
        List<List<String>> rows = parseCsv(new String(bytes, StandardCharsets.UTF_8));
        assertThat(rows).hasSize(4);

        List<String> weird = rows.stream().filter(r -> r.size() > 6 && r.get(6).contains("003"))
                .findFirst().orElseThrow();
        assertThat(weird.get(rows.get(0).indexOf("业务键"))).isEqualTo("B,\"003");

        List<String> b001 = rows.stream().filter(r -> r.size() > 6 && "B001".equals(r.get(6)))
                .findFirst().orElseThrow();
        assertThat(b001.get(rows.get(0).indexOf("凭证JSON RIGHT"))).contains("多行\n换行");

        // 不带 priceCode：计价列整列空
        assertThat(b001.get(rows.get(0).indexOf("金额影响"))).isEmpty();
    }

    @Test
    @DisplayName("任务不存在_400说人话")
    void missingJobIs400WithReadableError() throws Exception {
        String body = mvc.perform(get("/api/jobs/99999/evidence-pack.csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("任务不存在");
    }

    @Test
    @DisplayName("公式注入_非数字形态负数被钳_数字负数保留")
    void negativeNumericSurvivesFormulaPrefixClamp() {
        // 数字形态负数：金额语义优先，原样保留
        assertThat(io.github.dolow.reconlens.export.EvidencePackCsv.encodeCell("-2.00000000"))
                .isEqualTo("\"-2.00000000\"");
        // 非数字形态（OWASP DDE 注入形态）：钳掉
        assertThat(io.github.dolow.reconlens.export.EvidencePackCsv.encodeCell("-cmd|' /C calc'!D2"))
                .isEqualTo("\"'-cmd|' /C calc'!D2\"");
        // 既有三类前缀照旧
        assertThat(io.github.dolow.reconlens.export.EvidencePackCsv.encodeCell("=SUM(A1)"))
                .isEqualTo("\"'=SUM(A1)\"");
    }

    @Test
    @DisplayName("at格式错_400说人话")
    void badAtFormatIs400() throws Exception {
        String body = mvc.perform(get("/api/jobs/" + jobId + "/evidence-pack.csv")
                        .param("priceCode", "openai").param("at", "2026/11/14"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("ISO-8601");
    }
}
