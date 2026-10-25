package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 看表结构的 HTTP 出口。
 *
 * <p>重点不是"能返回列"，而是<b>不能看的表怎么回答</b>：
 * 不在白名单 → 404，且响应体里不区分"不存在"和"不允许看"——
 * 区分了就等于给了一个枚举表名的接口。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right",
        "spring.datasource.url=jdbc:h2:mem:table_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class TableApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("白名单里的表_能拿到列与主键")
    void 允许的表() throws Exception {
        mvc.perform(get("/api/tables/bill_left"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").value("bill_left"))
                .andExpect(jsonPath("$.columns").isArray())
                .andExpect(jsonPath("$.columns[0].name").exists())
                .andExpect(jsonPath("$.columns[0].type").exists())
                .andExpect(jsonPath("$.rowSource").exists());
    }

    @Test
    @DisplayName("白名单外的表_404 且不透露它存在")
    void 不许看的表() throws Exception {
        // 这张表在 schema.sql 里真实存在（recon_job），但不在白名单
        mvc.perform(get("/api/tables/recon_job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("表 recon_job 不可见"));
    }

    @Test
    @DisplayName("不存在的表_也是同一个 404 口径")
    void 不存在的表() throws Exception {
        mvc.perform(get("/api/tables/no_such_table_at_all"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("表 no_such_table_at_all 不可见"));
    }

    @Test
    @DisplayName("maxColumns 能截断宽表")
    void 截断() throws Exception {
        mvc.perform(get("/api/tables/bill_left").param("maxColumns", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    @DisplayName("不传 sample 时一个数据行都不读_响应里没有 profile")
    void 默认不采样() throws Exception {
        mvc.perform(get("/api/tables/bill_left"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    @Test
    @DisplayName("sample=true 返回窗口估算_并明确标 estimated")
    void 采样() throws Exception {
        mvc.perform(get("/api/tables/bill_left").param("sample", "true").param("window", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.estimated").value(true))
                .andExpect(jsonPath("$.profile.window").value(10))
                .andExpect(jsonPath("$.profile.columns").isArray());
    }

    @Test
    @DisplayName("要建议时才给建议_否则一个骨架都别塞")
    void 默认不建议() throws Exception {
        mvc.perform(get("/api/tables/bill_left"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advice").doesNotExist());
    }

    @Test
    @DisplayName("要建议时_给出维度候选与比对字段与可粘贴骨架")
    void 给建议() throws Exception {
        mvc.perform(get("/api/tables/bill_left").param("advice", "true").param("against", "bill_right"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advice.dimensions").isArray())
                .andExpect(jsonPath("$.advice.compares").isArray())
                .andExpect(jsonPath("$.advice.notes").isArray())
                .andExpect(jsonPath("$.advice.snippet").value(org.hamcrest.Matchers.containsString("code: NEW_RECON")))
                .andExpect(jsonPath("$.advice.snippet").value(org.hamcrest.Matchers.containsString("table: bill_right")));
    }
}
