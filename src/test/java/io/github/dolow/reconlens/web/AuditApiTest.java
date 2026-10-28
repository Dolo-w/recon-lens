package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ApprovalConfirmationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计导出的实测用例。
 *
 * <p>重点不在"能返回 200"，而在三件容易写错的事：
 * ① 没有操作人就不能导出；② CSV 打开时中文不乱码、且公式注入被中和；
 * ③ 审批留痕的状态要靠时间算，不能只回一个 used_at 字段让调用方自己猜。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:audit_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class AuditApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private ApprovalConfirmationRepository approvals;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 没有操作人不能导出_导出本身也是一次行为() throws Exception {
        mvc.perform(get("/api/audit/runs"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 运行列表_按状态筛_并给出结构化字段() throws Exception {
        long runId = runs.startRun("导出用例", "NL_QUESTION", "alice");
        runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, "BILL_VS_SETTLE", null, null, null);

        mvc.perform(get("/api/audit/runs").header("X-Operator", "alice").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value((int) runId))
                .andExpect(jsonPath("$.items[0].createdBy").value("alice"))
                .andExpect(jsonPath("$.items[0].ruleCode").value("BILL_VS_SETTLE"));
    }

    @Test
    void csv_带表头且字段是逗号分隔的普通文本() throws Exception {
        long runId = runs.startRun("csv 用例", "NL_QUESTION", "bob");
        runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, null, null, null, null);

        String body = mvc.perform(get("/api/audit/runs").header("X-Operator", "bob")
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).startsWith("\uFEFFid,createdAt,status");
        assertThat(body).contains("csv 用例");
    }

    @Test
    void csv_单元格以等号开头时补单引号_避免被表格软件当公式执行() {
        assertThat(AuditController.cell("=cmd|calc")).isEqualTo("\"'=cmd|calc\"");
        assertThat(AuditController.cell("+1")).isEqualTo("\"'+1\"");
        assertThat(AuditController.cell("普通文本")).isEqualTo("普通文本");
        assertThat(AuditController.cell("含,逗号")).isEqualTo("\"含,逗号\"");
    }

    @Test
    void 单次运行的步骤能导出来() throws Exception {
        long runId = runs.startRun("步骤用例", "NL_QUESTION", "alice");
        runs.recordStep(runId, 1, "ACT", "recon_run", "jobId=1", "跑了一次", "digest-1", false, "OK", 12, 34);
        runs.finishRun(runId, AgentRunRepository.RunStatus.SUCCESS, null, 1L, null, null);

        mvc.perform(get("/api/audit/runs/" + runId).header("X-Operator", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value((int) runId))
                .andExpect(jsonPath("$.steps[0].action").value("recon_run"))
                .andExpect(jsonPath("$.steps[0].digest").value("digest-1"))
                // M15：耗时与估算 token 都要能从接口拿到，不能只躺在表里
                .andExpect(jsonPath("$.steps[0].costMs").value(12))
                .andExpect(jsonPath("$.steps[0].tokensEst").value(34));
    }

    @Test
    void 不存在的运行_400_而不是空列表() throws Exception {
        mvc.perform(get("/api/audit/runs/999999").header("X-Operator", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("没有这次运行：999999"));
    }

    @Test
    void 时间格式写错要说清楚怎么改() throws Exception {
        mvc.perform(get("/api/audit/runs").header("X-Operator", "alice").param("from", "2026/10/26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ISO-8601")));
    }

    @Test
    void 审批留痕_未用掉的码报_UNUSED_用过的报_USED() throws Exception {
        ApprovalConfirmationRepository.Confirmation c =
                approvals.issue("BILL_VS_SETTLE", 1, "APPROVE", "alice");

        mvc.perform(get("/api/audit/approvals").header("X-Operator", "alice")
                        .param("ruleCode", "BILL_VS_SETTLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value(c.code()))
                .andExpect(jsonPath("$.items[0].state").value("UNUSED"));

        approvals.consume(c.code(), "BILL_VS_SETTLE", 1, "APPROVE", "alice");

        mvc.perform(get("/api/audit/approvals").header("X-Operator", "alice")
                        .param("ruleCode", "BILL_VS_SETTLE"))
                .andExpect(jsonPath("$.items[0].state").value("USED"))
                .andExpect(jsonPath("$.items[0].usedBy").value("alice"));
    }
}
