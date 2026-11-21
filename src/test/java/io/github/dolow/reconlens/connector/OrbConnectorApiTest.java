package io.github.dolow.reconlens.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.agent.FileImporter;
import io.github.dolow.reconlens.connector.orb.OrbApiClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Orb 连接器端到端（v3 扩展架构 §三：第二连接器）：与 {@link LagoConnectorApiTest} 同构
 * （伪服务 + sync 落盘 + FileImporter 导入），另钉 Orb 特有形状四件事：
 * <ul>
 *   <li>cursor 分页：{@code pagination_metadata.has_more/next_cursor} 翻页拉全；</li>
 *   <li>金额字符串元直接入库（DECIMAL，无分换算）："8.00" → 8；</li>
 *   <li>多厂商目录：GET /api/connectors 同时出现 lago 与 orb 两段；</li>
 *   <li>资源错误/401 说人话；未知连接器 400。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.connector.orb.api-key=orb-test-key",
        "recon.connector.orb.dir=target/orb-connector-test-import",
        "recon.agent.file-import.dir=target/orb-connector-test-import",
        "spring.datasource.url=jdbc:h2:mem:orb_connector_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class OrbConnectorApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileImporter fileImporter;

    @Autowired
    private OrbApiClient orbClient;

    private final ObjectMapper json = new ObjectMapper();

    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicInteger INVOICE_PAGES = new AtomicInteger();

    private static HttpServer fake;
    private static int port;

    @BeforeAll
    static void startFakeOrb() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().replaceFirst("^/v1", "");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status = 200;
            if (!"Bearer orb-test-key".equals(auth) || path.startsWith("/locked")) {
                status = 401;
                body = ("{\"type\":\"https://docs.withorb.com/reference/error-responses#401-authentication-error\","
                        + "\"status\":401,\"detail\":\"\",\"title\":\"\"}").getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/invoices")) {
                body = invoicesPage(INVOICE_PAGES.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/subscriptions")) {
                body = """
                        {"data":[
                          {"id":"sub-1","status":"active","name":"AI 企业版",
                           "plan":{"id":"plan-1","external_plan_id":"ai-enterprise","name":"AI Enterprise"},
                           "customer":{"id":"cus-1","external_customer_id":"ext-1"},
                           "start_date":"2026-01-01T00:00:00Z","end_date":null,
                           "created_at":"2026-01-01T00:00:00Z"}
                         ],
                         "pagination_metadata":{"has_more":false,"next_cursor":null}}"""
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        fake = server;
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeOrb() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    @DynamicPropertySource
    static void orbBase(DynamicPropertyRegistry registry) {
        registry.add("recon.connector.orb.base-url", () -> "http://localhost:" + port + "/v1");
    }

    /** 两页 invoices：cursor 分页，第 1 页 next_cursor="c2"，第 2 页结束。 */
    private static String invoicesPage(int page) {
        if (MODE.get().equals("empty")) {
            return """
                    {"data":[],"pagination_metadata":{"has_more":false,"next_cursor":null}}""";
        }
        if (page == 1) {
            return """
                    {"data":[
                      {"id":"inv-1","invoice_number":"JYEFHK-00001","invoice_source":"subscription",
                       "status":"issued","currency":"USD",
                       "customer":{"id":"cus-1","external_customer_id":"ext-1"},
                       "subscription":{"id":"sub-1"},
                       "subtotal":"100.00","total":"108.00","amount_due":"108.00",
                       "invoice_date":"2026-11-01T07:00:00+00:00","due_date":"2026-11-30T07:00:00+00:00",
                       "service_period_start":"2026-10-01T00:00:00Z","service_period_end":"2026-10-31T23:59:59Z",
                       "paid_at":null,"voided_at":null,"memo":"","created_at":"2026-11-01T07:00:00+00:00"},
                      {"id":"inv-2","invoice_number":"JYEFHK-00002","invoice_source":"subscription",
                       "status":"paid","currency":"USD",
                       "customer":{"id":"cus-2","external_customer_id":"ext-2"},
                       "subscription":{"id":"sub-2"},
                       "subtotal":"8.00","total":"8.00","amount_due":"8.00",
                       "invoice_date":"2026-11-02T07:00:00+00:00","due_date":"2026-12-01T07:00:00+00:00",
                       "service_period_start":"2026-10-01T00:00:00Z","service_period_end":"2026-10-31T23:59:59Z",
                       "paid_at":"2026-11-03T00:00:00Z","voided_at":null,"memo":"","created_at":"2026-11-02T07:00:00+00:00"}
                     ],
                     "pagination_metadata":{"has_more":true,"next_cursor":"c2"}}""";
        }
        return """
                {"data":[
                  {"id":"inv-3","invoice_number":"JYEFHK-00003","invoice_source":"subscription",
                   "status":"void","currency":"USD",
                   "customer":{"id":"cus-3","external_customer_id":"ext-3"},
                   "subscription":{"id":"sub-3"},
                   "subtotal":"0.00","total":"0.00","amount_due":"0.00",
                   "invoice_date":"2026-11-03T07:00:00+00:00","due_date":null,
                   "service_period_start":"2026-10-01T00:00:00Z","service_period_end":"2026-10-31T23:59:59Z",
                   "paid_at":null,"voided_at":"2026-11-04T00:00:00Z","memo":"作废","created_at":"2026-11-03T07:00:00+00:00"}
                 ],
                 "pagination_metadata":{"has_more":false,"next_cursor":null}}""";
    }

    @BeforeEach
    void reset() throws Exception {
        MODE.set("ok");
        INVOICE_PAGES.set(0);
        jdbc.update("DELETE FROM recon_registered_table");
        jdbc.update("DROP TABLE IF EXISTS orb_invoices_test");
        Files.createDirectories(Path.of("target/orb-connector-test-import"));
        try (var files = Files.list(Path.of("target/orb-connector-test-import"))) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响测试
                }
            });
        }
    }

    @Test
    @DisplayName("Orb同步→落盘→导入全链路_cursor分页_元直入")
    void syncThenImportEndToEnd() throws Exception {
        String body = mvc.perform(post("/api/connectors/orb/sync")
                        .contentType("application/json")
                        .content("{\"resource\":\"invoices\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);

        assertThat(root.get("resource").asText()).isEqualTo("invoices");
        assertThat(root.get("apiRows").asLong()).isEqualTo(3); // cursor 翻页拉全 2 页
        String csvFile = root.get("csvFile").asText();
        assertThat(csvFile).startsWith("orb-invoices-").endsWith(".csv");
        assertThat(root.get("notes").toString()).contains("DECIMAL").contains("分换算");

        // 落盘 CSV：表头 = profile 表头，行值 = API JSON（字符串元原样）
        List<String> lines = Files.readAllLines(
                Path.of("target/orb-connector-test-import").resolve(csvFile), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).contains("orb_id", "invoice_number", "customer_external_id", "total");
        assertThat(lines).hasSize(4); // 表头 + 3 行
        assertThat(lines.get(1)).contains("inv-1").contains("\"108.00\"").contains("JYEFHK-00001");
        assertThat(lines.get(3)).contains("inv-3").contains("作废");

        // 导入段：profile 归一 + DECIMAL 直入（字符串元，无分换算）
        FileImporter.ImportPreview preview =
                fileImporter.confirm(csvFile, "orb_invoices_test", "vv", null, "orb-invoices");
        assertThat(preview.error()).isNull();
        assertThat(preview.rows()).isEqualTo(3L);
        assertThat(new java.math.BigDecimal(jdbc.queryForObject(
                "SELECT total_amount FROM orb_invoices_test WHERE orb_id = 'inv-1'", String.class)))
                .isEqualByComparingTo("108"); // "108.00" 直入，无分换算
        assertThat(jdbc.queryForObject(
                "SELECT invoice_number FROM orb_invoices_test WHERE orb_id = 'inv-1'", String.class))
                .isEqualTo("JYEFHK-00001");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_registered_table WHERE table_name = 'orb_invoices_test'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("多厂商目录_lago与orb并存_未知连接器400")
    void multiVendorCatalog() throws Exception {
        String catalog = mvc.perform(get("/api/connectors"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(catalog)
                .contains("\"lago\"").contains("\"orb\"")
                .contains("\"profileId\":\"orb-invoices\"").contains("\"profileId\":\"lago-invoice-fees\"")
                .contains("POST /api/connectors/orb/sync")
                .doesNotContain("orb-test-key");

        String unknown = mvc.perform(post("/api/connectors/metronome/sync")
                        .contentType("application/json").content("{\"resource\":\"invoices\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(unknown).get("error").asText()).contains("lago, orb");
    }

    @Test
    @DisplayName("空数据不落盘_401说人话_未知资源400")
    void failLoud() throws Exception {
        MODE.set("empty");
        String empty = mvc.perform(post("/api/connectors/orb/sync")
                        .contentType("application/json").content("{\"resource\":\"invoices\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(empty).get("error").asText()).contains("0 行");
        try (var files = Files.list(Path.of("target/orb-connector-test-import"))) {
            assertThat(files.count()).isZero();
        }

        // 401：伪服务 /locked 恒 401，client 的人话翻译原样透出
        OrbApiClient.OrbApiException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> orbClient.fetchPath("/locked"), OrbApiClient.OrbApiException.class);
        assertThat(thrown).hasMessageContaining("API key 无效");

        // 未知资源：service 层 resolve 挡住并列出可用值
        String unknown = mvc.perform(post("/api/connectors/orb/sync")
                        .contentType("application/json").content("{\"resource\":\"plans\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(unknown).get("error").asText()).contains("invoices, subscriptions");
    }
}
