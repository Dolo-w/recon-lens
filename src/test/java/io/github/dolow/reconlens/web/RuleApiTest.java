package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用方通过 HTTP 自己定义对账维度与比对字段：
 * POST /api/rules 落库一个新版本并立刻生效，GET 能回看版本历史，DELETE 让规则下线；
 * 只想临时试一手就用 POST /api/jobs 的 rule / ruleYaml 内联规则，不落库。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        // 直写通路默认关闭（P0-1）；这个测试类验的正是"显式打开 + 带密钥"之后的行为
        "recon.rules.legacy-define-enabled=true",
        "recon.review.api-key=test-key",
        // 独立内存库：默认库是 jdbc:h2:mem:recon（DB_CLOSE_DELAY=-1），
        // 与其它上下文的属性集不同 → 新建上下文时会重跑 data.sql，共享库就会撞主键
        "spring.datasource.url=jdbc:h2:mem:rule_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
class RuleApiTest {

    private static final String YAML_RULE = """
            code: API_DIM_TEST
            name: API 定义的规则
            left:
              label: 账单
              table: bill_left
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            right:
              label: 结算
              table: bill_right
              keyField: order_no
              keyType: STRING
              orderBy: order_no
            shard:
              count: 1
              parallelism: 1
            compare:
              - field: amount
                type: DECIMAL
                tolerance: 0.01
                diffType: AMOUNT_MISMATCH
                severity: HIGH
            missing:
              leftMissingDiffType: MISSING_LEFT
              rightMissingDiffType: MISSING_RIGHT
              severity: HIGH
            duplicate:
              diffType: DUPLICATE_KEY
              severity: MEDIUM
            """;

    private static final String JSON_RULE = """
            {
              "code": "API_DIM_TEST",
              "name": "API 定义的规则（改比对字段）",
              "left": {"label": "账单", "table": "bill_left", "keyField": "order_no", "keyType": "STRING", "orderBy": "order_no"},
              "right": {"label": "结算", "table": "bill_right", "keyField": "order_no", "keyType": "STRING", "orderBy": "order_no"},
              "shard": {"count": 1, "parallelism": 1},
              "compare": [
                {"field": "status", "type": "STRING", "ignoreCase": true, "diffType": "STATUS_MISMATCH", "severity": "MEDIUM"}
              ],
              "missing": {"leftMissingDiffType": "MISSING_LEFT", "rightMissingDiffType": "MISSING_RIGHT", "severity": "HIGH"},
              "duplicate": {"diffType": "DUPLICATE_KEY", "severity": "MEDIUM"}
            }
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void rulesCanBeDefinedReloadedAndRetiredOverHttp() throws Exception {
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.valueOf("application/yaml"))
                        .header("X-Operator", "alice")
                        .header("X-Api-Key", "test-key")
                        .content(YAML_RULE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("API_DIM_TEST"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.keyField").value("order_no"))
                .andExpect(jsonPath("$.compare[0].field").value("amount"));

        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "bob")
                        .header("X-Api-Key", "test-key")
                        .content(JSON_RULE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.compare[0].field").value("status"));

        mvc.perform(get("/api/rules/API_DIM_TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[0].createdBy").value("bob"))
                .andExpect(jsonPath("$.versions[1].version").value(1))
                .andExpect(jsonPath("$.versions[1].createdBy").value("alice"));

        mvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("API_DIM_TEST")));

        mvc.perform(delete("/api/rules/API_DIM_TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("API_DIM_TEST"))
                .andExpect(jsonPath("$.disabledVersions").value(2));

        mvc.perform(get("/api/rules/API_DIM_TEST")).andExpect(status().isNotFound());
    }

    @Test
    void badRuleBodyIsRejectedWithTheParserMessage() throws Exception {
        mvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Key", "test-key")
                        .content("{\"code\":\"lowercase_code\",\"left\":{\"table\":\"t\",\"keyField\":\"k\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("code 只能是大写字母、数字与下划线")));
    }

    @Test
    void unknownRuleIsNotFound() throws Exception {
        mvc.perform(get("/api/rules/NO_SUCH_RULE")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/rules/NO_SUCH_RULE")).andExpect(status().isNotFound());
    }

    @Test
    void inlineRuleRunsWithoutBeingPersisted() throws Exception {
        String request = """
                {
                  "batchId": "api-inline",
                  "sync": true,
                  "rule": {
                    "code": "API_INLINE_TEST",
                    "name": "内联规则",
                    "left": {"table": "bill_left", "keyField": "order_no", "keyType": "STRING"},
                    "right": {"table": "bill_right", "keyField": "order_no"},
                    "shard": {"count": 1, "parallelism": 1},
                    "compare": [{"field": "amount", "type": "DECIMAL", "tolerance": 0.01,
                                 "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"}],
                    "missing": {"leftMissingDiffType": "MISSING_LEFT", "rightMissingDiffType": "MISSING_RIGHT"},
                    "duplicate": {"diffType": "DUPLICATE_KEY"}
                  }
                }
                """;

        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("API_INLINE_TEST"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.diffRows").value(5))
                .andExpect(jsonPath("$.ruleVersion").doesNotExist());

        mvc.perform(get("/api/rules/API_INLINE_TEST")).andExpect(status().isNotFound());
    }

    @Test
    void jobWithoutRuleCodeAndWithoutInlineRuleIsABadRequest() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"batchId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("ruleCode 不能为空")));
    }

    /** batchId 是 recon_job 的非空列：省略时过去直接 500（响应体还没有原因），现在自动补一个并回显。 */
    @Test
    void jobWithoutBatchIdGetsAGeneratedOneInsteadOfAServerError() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleCode": "BILL_VS_SETTLE", "sync": true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(containsString("api-")))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    /**
     * 键列为 NULL 时（dimension 用 CONCAT，任一列为空整键就为空）不能只甩一句
     * "Column 'biz_key' cannot be null"：分片失败的原因要能直接指向列和处理办法。
     */
    @Test
    void nullBusinessKeyFailsTheJobWithAnActionableReason() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS null_key_left (k VARCHAR(32), amount DECIMAL(18,2))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS null_key_right (k VARCHAR(32), amount DECIMAL(18,2))");
        jdbc.update("DELETE FROM null_key_left");
        jdbc.update("DELETE FROM null_key_right");
        jdbc.update("INSERT INTO null_key_left (k, amount) VALUES (NULL, 10.00)");

        String request = """
                {
                  "batchId": "null-key",
                  "sync": true,
                  "rule": {
                    "code": "NULL_KEY_TEST",
                    "left": {"table": "null_key_left", "keyType": "STRING", "dimension": ["k"]},
                    "right": {"table": "null_key_right", "keyType": "STRING", "dimension": ["k"]},
                    "shard": {"count": 1, "parallelism": 1},
                    "compare": [{"field": "amount", "type": "DECIMAL", "tolerance": 0.01,
                                 "diffType": "AMOUNT_MISMATCH", "severity": "HIGH"}],
                    "missing": {"leftMissingDiffType": "MISSING_LEFT", "rightMissingDiffType": "MISSING_RIGHT"},
                    "options": {"maxAttempts": 1}
                  }
                }
                """;

        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMsg").value(containsString("对账键为空")))
                .andExpect(jsonPath("$.errorMsg").value(containsString("IS NULL")));
    }
}
