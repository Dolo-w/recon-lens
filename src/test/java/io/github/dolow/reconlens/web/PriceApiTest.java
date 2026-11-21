package io.github.dolow.reconlens.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * token 价格表 HTTP 出口端到端：录入归一、重叠 fail-loud、解析试算、删除后未命中。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.datasource.url=jdbc:h2:mem:price_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class PriceApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private String postEntry(String body) throws Exception {
        return mvc.perform(post("/api/prices")
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String postResolve(String body) throws Exception {
        return mvc.perform(post("/api/prices/resolve")
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void createNormalizesAndResolvePricesByRequestTimestamp() throws Exception {
        // 月中调价两窗：归一（model 小写、tokenType 大写、tierMin 缺省 0）
        assertThat(json.readTree(postEntry("""
                {"priceCode":"openai","model":"GPT-4o","tokenType":"input",
                 "unitPricePerMillion":8.00,"currency":"USD",
                 "effectiveFrom":"2026-11-01T00:00:00Z","effectiveTo":"2026-11-15T00:00:00Z"}
                """)).get("model").asText()).isEqualTo("gpt-4o");
        assertThat(json.readTree(postEntry("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"Input",
                 "unitPricePerMillion":4.00,"currency":"USD",
                 "effectiveFrom":"2026-11-15T00:00:00Z"}
                """)).get("tokenType").asText()).isEqualTo("INPUT");

        // 11-14 旧价 / 11-15 新价 —— 月中调价按请求时间戳回溯
        JsonNode old = json.readTree(postResolve("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"input",
                 "at":"2026-11-14T12:00:00Z","tokens":1500000}
                """));
        assertThat(old.get("unitPricePerMillion").asDouble()).isEqualTo(8.0);
        assertThat(old.get("amount").asDouble()).isEqualTo(12.0);

        JsonNode latest = json.readTree(postResolve("""
                {"priceCode":"openai","model":"gpt-4o","tokenType":"input",
                 "at":"2026-11-15T00:00:00Z","tokens":1500000}
                """));
        assertThat(latest.get("unitPricePerMillion").asDouble()).isEqualTo(4.0);
        assertThat(latest.get("entryId").asLong()).isGreaterThan(0);
    }

    @Test
    void overlappingWindowIsRejectedAtWriteTimeNotAtResolveTime() throws Exception {
        postEntry("""
                {"priceCode":"dup","model":"m1","tokenType":"INPUT","unitPricePerMillion":8,
                 "effectiveFrom":"2026-11-01T00:00:00Z","effectiveTo":"2026-11-20T00:00:00Z"}
                """);
        // 与已有窗重叠 → 录入时就 400，绝不等到 100 万条账单解析时逐条炸
        String body = postEntry("""
                {"priceCode":"dup","model":"m1","tokenType":"INPUT","unitPricePerMillion":4,
                 "effectiveFrom":"2026-11-15T00:00:00Z"}
                """);
        assertThat(json.readTree(body).get("error").asText()).contains("重叠").contains("effective_to");
    }

    @Test
    void resolveMissFailsWith400AndUsableContext() throws Exception {
        String body = postResolve("""
                {"priceCode":"nothing","model":"m1","tokenType":"INPUT",
                 "at":"2026-11-10T00:00:00Z","tokens":100}
                """);
        assertThat(json.readTree(body).get("error").asText()).contains("一条都没有");
    }

    @Test
    void badTimeFormatIs400WithReadableMessage() throws Exception {
        String body = postResolve("""
                {"priceCode":"c","model":"m","tokenType":"INPUT",
                 "at":"2026/11/10","tokens":100}
                """);
        assertThat(json.readTree(body).get("error").asText()).contains("ISO-8601");
    }

    @Test
    void deleteThenListThenResolve() throws Exception {
        String created = postEntry("""
                {"priceCode":"del","model":"m1","tokenType":"INPUT","unitPricePerMillion":2,
                 "effectiveFrom":"2026-11-01T00:00:00Z"}
                """);
        long id = json.readTree(created).get("id").asLong();

        // 列表过滤生效
        JsonNode list = json.readTree(mvc.perform(get("/api/prices")
                        .param("priceCode", "del").param("model", "m1"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(list.get("total").asInt()).isEqualTo(1);

        mvc.perform(delete("/api/prices/" + id)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_price_entry WHERE price_code = 'del'", Integer.class)).isZero();

        // 删完解析 → 未命中 400，不会静默
        assertThat(json.readTree(postResolve("""
                        {"priceCode":"del","model":"m1","tokenType":"INPUT",
                         "at":"2026-11-10T00:00:00Z","tokens":100}
                        """)).has("error")).isTrue();
    }
}
