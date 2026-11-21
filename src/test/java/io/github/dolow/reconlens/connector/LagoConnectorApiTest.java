package io.github.dolow.reconlens.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dolow.reconlens.agent.FileImporter;
import io.github.dolow.reconlens.connector.lago.LagoApiClient;
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
 * Lago 原生连接器端到端：填 API key 拉厂商账单 → 按 profile 表头映射 → 落盘 CSV，
 * 再用 {@code recon_import_file} 的管道（FileImporter.confirm）灌表——拉取与导入分离
 * 是架构边界（agent 包可选退场），测试把两段都跑到，钉住七件事：
 * <ul>
 *   <li>分页拉全（meta.next_page 翻页）；</li>
 *   <li>跨资源 join：fee 拿到发票号，join 不上的留空并在 notes 计数；</li>
 *   <li>落盘 CSV 表头 = profile 表头，行内容与 API JSON 一致；</li>
 *   <li>导入后 {@code _cents} 换算成元（10900 → 109）、白名单注册；</li>
 *   <li>目录不回显 API key；</li>
 *   <li>空数据不落盘；401 说人话；未知资源 400。</li>
 * </ul>
 *
 * <p>伪 Lago 用 JDK HttpServer（零新依赖），响应形状按源码核证：
 * {@code {"fees":[...],"meta":{"next_page":2,...}}}。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.connector.lago.api-key=test-key",
        "recon.connector.lago.dir=target/lago-connector-test-import",
        "recon.agent.file-import.dir=target/lago-connector-test-import",
        "spring.datasource.url=jdbc:h2:mem:lago_connector_api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
class LagoConnectorApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FileImporter fileImporter;

    @Autowired
    private LagoApiClient lagoClient;

    private final ObjectMapper json = new ObjectMapper();

    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicInteger FEE_REQUESTS = new AtomicInteger();

    private static HttpServer fake;
    private static int port;

    @BeforeAll
    static void startFakeLago() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath().replaceFirst("^/api/v1", "");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status = 200;
            if (!"Bearer test-key".equals(auth) || path.startsWith("/locked")) {
                status = 401;
                body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/fees")) {
                body = feesPage(FEE_REQUESTS.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/invoices")) {
                body = invoicesPage().getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/empty")) {
                body = "{\"empty\":[],\"meta\":{\"next_page\":null}}".getBytes(StandardCharsets.UTF_8);
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
    static void stopFakeLago() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    @DynamicPropertySource
    static void lagoBase(DynamicPropertyRegistry registry) {
        registry.add("recon.connector.lago.base-url", () -> "http://localhost:" + port + "/api/v1");
    }

    /** 两页 fees：第 1 页 2 行（1 行能 join 发票、1 行 join 不上），第 2 页 1 行。 */
    private static String feesPage(int page) {
        if (page == 1) {
            if (MODE.get().equals("empty")) {
                return """
                        {"fees":[],"meta":{"current_page":1,"next_page":null,"total_count":0}}""";
            }
            return """
                    {"fees":[
                      {"lago_id":"fee-1","lago_invoice_id":"inv-1","external_subscription_id":"sub-1",
                       "item":{"type":"add_on","code":"setup","name":"Setup Fee","description":"一次性开通费",
                               "invoice_display_name":"开通费","filter_invoice_display_name":"","grouped_by":null},
                       "from_date":"2026-11-01T00:00:00Z","to_date":"2026-11-01T23:59:59Z",
                       "units":"1.0","precise_unit_amount":"109.0","amount_currency":"EUR",
                       "total_amount_currency":"EUR","taxes_amount_cents":900,"total_amount_cents":10900},
                      {"lago_id":"fee-2","lago_invoice_id":"inv-orphan","external_subscription_id":"sub-2",
                       "item":{"type":"charge","code":"tokens","name":"Tokens","description":"","invoice_display_name":"","grouped_by":"model"},
                       "from_date":"2026-11-01T00:00:00Z","to_date":"2026-11-30T23:59:59Z",
                       "units":"250000","precise_unit_amount":"0.000004","amount_currency":"EUR",
                       "total_amount_currency":"EUR","taxes_amount_cents":0,"total_amount_cents":100000}
                     ],
                     "meta":{"current_page":1,"next_page":2,"total_count":3}}""";
        }
        return """
                {"fees":[{"lago_id":"fee-3","lago_invoice_id":"inv-1","item":{"type":"add_on","code":"extra"},
                 "units":"2","amount_currency":"EUR","total_amount_currency":"EUR",
                 "taxes_amount_cents":50,"total_amount_cents":250}],
                 "meta":{"current_page":2,"next_page":null,"total_count":3}}""";
    }

    private static String invoicesPage() {
        return """
                {"invoices":[
                  {"lago_id":"inv-1","sequential_id":7,"self_billed":false,"issuing_date":"2026-11-01",
                   "customer":{"lago_id":"cus-1","external_id":"ext-1","name":"客户甲","email":"a@b.c",
                               "country":"CN","tax_identification_number":"91330000XX"},
                   "number":"INV-001","purchase_order_number":"","invoice_type":"subscription",
                   "payment_status":"pending","status":"finalized","file_url":"","currency":"EUR",
                   "fees_amount_cents":10000,"coupons_amount_cents":0,"taxes_amount_cents":900,
                   "credit_notes_amount_cents":0,"prepaid_credit_amount_cents":0,"total_amount_cents":10900,
                   "payment_due_date":"","payment_dispute_lost_at":null,"payment_overdue":false,
                   "total_due_amount_cents":10900,"total_paid_amount_cents":0,
                   "total_offsetted_credit_note_amount_cents":0}],
                 "meta":{"current_page":1,"next_page":null,"total_count":1}}""";
    }

    @BeforeEach
    void reset() throws Exception {
        MODE.set("ok");
        FEE_REQUESTS.set(0);
        jdbc.update("DELETE FROM recon_registered_table");
        jdbc.update("DROP TABLE IF EXISTS lago_fees_test");
        Files.createDirectories(Path.of("target/lago-connector-test-import"));
        try (var files = Files.list(Path.of("target/lago-connector-test-import"))) {
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
    @DisplayName("同步→落盘→导入全链路_分页_join_分换元_白名单")
    void syncThenImportEndToEnd() throws Exception {
        String body = mvc.perform(post("/api/connectors/lago/sync")
                        .contentType("application/json")
                        .content("{\"resource\":\"fees\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);

        assertThat(root.get("resource").asText()).isEqualTo("fees");
        assertThat(root.get("apiRows").asLong()).isEqualTo(3);
        String csvFile = root.get("csvFile").asText();
        assertThat(csvFile).endsWith(".csv");
        assertThat(root.get("notes").toString()).contains("subscription_plan_code").contains("1 行 fee");
        assertThat(root.get("importHint").get("tool").asText()).isEqualTo("recon_import_file");

        // 落盘 CSV：表头 = profile 表头，行值 = API JSON（分单位原样，换算在导入侧）
        List<String> lines = Files.readAllLines(
                Path.of("target/lago-connector-test-import").resolve(csvFile), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).contains("invoice_lago_id", "fee_total_amount_cents", "subscription_plan_code");
        assertThat(lines).hasSize(4); // 表头 + 3 行
        assertThat(lines.get(1)).contains("fee-1").contains("10900");
        assertThat(lines.get(1)).contains("INV-001"); // join 发票号
        assertThat(lines.get(2)).contains("fee-2").contains("\"\""); // join 不上 → 空发票号

        // 导入段：profile 归一（列改名 + 分换元）+ 白名单注册
        FileImporter.ImportPreview preview =
                fileImporter.confirm(csvFile, "lago_fees_test", "vv", null, "lago-invoice-fees");
        assertThat(preview.error()).isNull();
        assertThat(preview.rows()).isEqualTo(3L);
        assertThat(new java.math.BigDecimal(jdbc.queryForObject(
                "SELECT total_amount FROM lago_fees_test WHERE fee_lago_id = 'fee-1'", String.class)))
                .isEqualByComparingTo("109"); // 10900 cents ÷ 100
        // join 不上 → 发票号为空；文件导入把空字段写成 NULL（既有行为，emptyKeyMessage 也这么处理）
        assertThat(jdbc.queryForObject(
                "SELECT invoice_number FROM lago_fees_test WHERE fee_lago_id = 'fee-2'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_registered_table WHERE table_name = 'lago_fees_test'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("目录不回显key_只报configured")
    void catalogNeverEchoesApiKey() throws Exception {
        String body = mvc.perform(get("/api/connectors"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"configured\":true").contains("lago-invoice-fees");
        assertThat(body).doesNotContain("test-key");
    }

    @Test
    @DisplayName("空数据不落盘")
    void emptyFetchWritesNothing() throws Exception {
        MODE.set("empty");
        String body = mvc.perform(post("/api/connectors/lago/sync")
                        .contentType("application/json").content("{\"resource\":\"fees\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(body).get("error").asText()).contains("0 行");
        try (var files = Files.list(Path.of("target/lago-connector-test-import"))) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    @DisplayName("401说人话报key无效_不吞错")
    void unauthorizedIsTranslated() {
        // 资源名只是路径前缀：/locked 在伪服务端恒 401，报错必须翻译成人话而不是裸状态码
        LagoApiClient.LagoApiException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> lagoClient.fetchAll("locked"), LagoApiClient.LagoApiException.class);
        assertThat(thrown).hasMessageContaining("API key 无效");
    }

    @Test
    @DisplayName("未知资源_400列可用值")
    void unknownResourceIs400() throws Exception {
        String body = mvc.perform(post("/api/connectors/lago/sync")
                        .contentType("application/json").content("{\"resource\":\"plans\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(body).get("error").asText()).contains("fees, invoices");
    }

    @Test
    @DisplayName("SPI_未知连接器400_目录多厂商结构")
    void spiUnknownConnectorAndCatalogShape() throws Exception {
        // SPI 化后的新行为：POST 到不存在的厂商段，400 说人话列出已注册连接器
        String body = mvc.perform(post("/api/connectors/stripe/sync")
                        .contentType("application/json").content("{\"resource\":\"invoices\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(body).get("error").asText()).contains("lago");

        // 目录：lago 段保持原有结构（configured 只报布尔 + resources 带 profileId + sync 提示路径）
        String catalog = mvc.perform(get("/api/connectors"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(catalog).contains("\"lago\"")
                .contains("\"profileId\":\"lago-invoice-fees\"")
                .contains("POST /api/connectors/lago/sync")
                .doesNotContain("test-key");
    }
}
