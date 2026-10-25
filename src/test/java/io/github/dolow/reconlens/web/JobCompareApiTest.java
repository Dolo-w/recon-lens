package io.github.dolow.reconlens.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务比较与数据快照在 HTTP 这一层的口径（ADR-0002 D4/D7）。
 *
 * <p>守两件事：
 * <ol>
 *   <li>同一个规则跑两次，接口要敢说"可比"，并给出差值；</li>
 *   <li>没记过快照的老任务要显示"未记录"，不能伪装成"数据一致"。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:job_compare_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class JobCompareApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long runOnce(String batch) throws Exception {
        String body = mvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleCode\":\"BILL_VS_SETTLE\",\"sync\":true,\"batchId\":\"" + batch + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read("jobId", Integer.class).longValue();
    }

    @Test
    void 同规则两次跑可以比_并且给出差值() throws Exception {
        long first = runOnce("cmp-a");
        long second = runOnce("cmp-b");

        mvc.perform(get("/api/jobs/compare").param("a", String.valueOf(first)).param("b", String.valueOf(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.reason").value("同规则同版本"))
                .andExpect(jsonPath("$.delta.differences").value(0))
                .andExpect(jsonPath("$.a.dataRecorded").value(true))
                .andExpect(jsonPath("$.b.dataRecorded").value(true));
    }

    @Test
    void 任务详情带数据快照_两侧表都在里面() throws Exception {
        long jobId = runOnce("snap-a");

        mvc.perform(get("/api/jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSnapshot.left.table").value("bill_left"))
                .andExpect(jsonPath("$.dataSnapshot.right.table").value("bill_right"))
                .andExpect(jsonPath("$.dataSnapshot.left.schemaHash").isNotEmpty())
                .andExpect(jsonPath("$.dataSnapshot.left.rowCount").isNumber());
    }

    @Test
    void 不存在的任务比较返回404而不是空结论() throws Exception {
        long jobId = runOnce("cmp-404");

        mvc.perform(get("/api/jobs/compare").param("a", String.valueOf(jobId)).param("b", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
    @Test
    void 数据被动过之后_详情要说出哪里变了() throws Exception {
        long jobId = runOnce("drift-a");

        jdbc.update("INSERT INTO bill_left (id, order_no, customer_id, amount, status, updated_at) "
                + "VALUES (999, 'ORD20269999', 'C099', 42.00, 'PAID', CURRENT_TIMESTAMP)");

        mvc.perform(get("/api/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataDrift").isArray())
                .andExpect(jsonPath("$.dataDrift[0]").value(org.hamcrest.Matchers.containsString("行数")));
    }

    // ------------------------------------- ADR-0001 N7：截断必须留结构化的翻页出口

    @Test
    void 差异分页在截断时给出nextOffset_翻到底就给null() throws Exception {
        long jobId = runOnce("paging-n7");

        // 这个演示数据一单 4 条差异：limit=2 必然截断
        mvc.perform(get("/api/diffs").param("jobId", String.valueOf(jobId)).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.nextOffset").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));

        // 拿 nextOffset 直接翻第二页，不该再截断
        mvc.perform(get("/api/diffs").param("jobId", String.valueOf(jobId))
                        .param("limit", "2").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.nextOffset").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(2));
    }
}
