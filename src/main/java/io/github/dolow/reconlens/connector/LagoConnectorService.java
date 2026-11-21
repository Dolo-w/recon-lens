package io.github.dolow.reconlens.connector;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.connector.lago.LagoApiClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lago 连接器服务（连接器层第三步）：填 API key 拉厂商账单，落盘成可导入的 CSV。
 *
 * <p><b>职责边界是刻意的</b>：连接器只做"取数 → 按 profile 表头映射 → 落盘 CSV"，
 * <b>不建表不灌数</b>。导入（建表、{@code _cents} 换算、白名单注册）全部留在
 * {@code recon_import_file} 的既有管道里。原因有二：
 * <ul>
 *   <li>架构边界：agent 包（导入管道所在）是可选子系统，{@code recon.agent.enabled=false}
 *       必须等于"回到引入之前"——连接器（常驻）依赖它，这个等式就破了，ArchUnit 会拦；</li>
 *   <li>口径唯一：profile 归一逻辑只有一份，连接器若自己灌数就会长出第二条口径，
 *       对账系统里口径分叉就是假差异的温床。</li>
 * </ul>
 *
 * <p>落盘目录默认与 agent 导入目录相同（{@code recon.connector.lago.dir}），拉完即可
 * {@code recon_import_file(file=<csvFile>, table=..., profile=<resource 对应 profile>)} 一键导入；
 * 拉下来的 CSV 本身就是原始凭证，留在导入目录里可复跑可审计。
 *
 * <p><b>字段映射口径（全部核证自 Lago 源码）</b>：CSV 导出内部用的就是 V1 serializer
 * （REST API 同款），所以 API JSON → CSV 列的映射可以直接从
 * {@code data_exports/csv/invoice_fees.rb} / {@code invoices.rb} 逐列读出来。
 * 两个已知缺口如实留空并写进 notes：
 * <ul>
 *   <li>{@code subscription_plan_code}：fee JSON 不含 plan_code（CSV 版走 AR 关联取），
 *       API 路径下留空；</li>
 *   <li>fee 的 {@code invoice_number}/{@code invoice_issuing_date}：fee JSON 只有
 *       {@code lago_invoice_id}，跨资源 join 从 invoices 拉全量建映射，join 不上的留空并计数。</li>
 * </ul>
 */
@Service
public class LagoConnectorService {

    private static final Logger log = LoggerFactory.getLogger(LagoConnectorService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final LagoApiClient client;
    private final ReconProperties properties;

    public LagoConnectorService(LagoApiClient client, ReconProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 资源 → profile：拉哪类账单、按哪份映射落盘、导入时传哪个 profile。 */
    public record Resource(String id, String profileId, String description) {

        public static final Resource FEES = new Resource("fees", "lago-invoice-fees",
                "Fee 明细（一行=发票×计费项），对账粒度最细");
        public static final Resource INVOICES = new Resource("invoices", "lago-invoices",
                "发票汇总（一行=一张发票）");

        public static Resource resolve(String id) {
            if (FEES.id().equals(id)) {
                return FEES;
            }
            if (INVOICES.id().equals(id)) {
                return INVOICES;
            }
            throw new IllegalArgumentException("未知资源: " + id + "，可用: " + FEES.id() + ", " + INVOICES.id());
        }
    }

    /** 同步结果：落盘文件 + 拉取侧说明 + 下一步导入怎么做（说人话，不让人猜）。 */
    public record SyncResult(String resource, String csvFile, long apiRows,
                             List<String> notes, Map<String, String> importHint) {
    }

    public SyncResult sync(String resourceId) {
        Resource resource = Resource.resolve(resourceId);
        List<JsonNode> rows = client.fetchAll(resource.id());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Lago API 返回 0 行 " + resource.id()
                    + " —— 空数据不落盘不误导（先核对环境/时间范围，落一张空 CSV 只会让人误以为同步成功）");
        }
        List<String> headers = ColumnProfiles.resolve(resource.profileId()).requiredHeaders();
        List<List<String>> records = resource.id().equals(Resource.FEES.id())
                ? mapFees(rows) : mapInvoices(rows);
        String csvFile = writeCsv(resource.id(), headers, records);
        log.info("Lago 连接器同步完成: resource={} apiRows={} csv={}", resource.id(), rows.size(), csvFile);
        return new SyncResult(resource.id(), csvFile, rows.size(), prepareNotes(resource, records),
                Map.of("tool", "recon_import_file",
                        "args", "file=" + csvFile + ", table=<自取表名>, profile=" + resource.profileId(),
                        "note", "导入走 agent 侧管道（profile 归一/分换元/白名单注册都在那一步），"
                                + "需 recon.agent.enabled=true；拉取与导入分离是有意为之，见 LagoConnectorService 类注释"));
    }

    // ------------------------------------------------------------------
    // 字段映射：逐列核证自 data_exports/csv/*.rb（CSV 内部用的就是 V1 serializer）

    private List<List<String>> mapInvoices(List<JsonNode> invoices) {
        List<String> paths = List.of(
                "lago_id", "sequential_id", "self_billed", "issuing_date",
                "customer.lago_id", "customer.external_id", "customer.name", "customer.email",
                "customer.country", "customer.tax_identification_number",
                "number", "purchase_order_number", "invoice_type", "payment_status", "status",
                "file_url", "currency",
                "fees_amount_cents", "coupons_amount_cents", "taxes_amount_cents",
                "credit_notes_amount_cents", "prepaid_credit_amount_cents", "total_amount_cents",
                "payment_due_date", "payment_dispute_lost_at", "payment_overdue",
                "total_due_amount_cents", "total_paid_amount_cents",
                "total_offsetted_credit_note_amount_cents");
        List<List<String>> out = new ArrayList<>();
        for (JsonNode inv : invoices) {
            List<String> row = new ArrayList<>(paths.size());
            for (String p : paths) {
                row.add(LagoApiClient.text(inv, p));
            }
            out.add(row);
        }
        return out;
    }

    /** fee JSON 不含发票号与签发日 → 全量拉 invoices 建 join 表（拉取侧已核证 invoice JSON 1:1 对齐 CSV 列）。 */
    private List<List<String>> mapFees(List<JsonNode> fees) {
        Map<String, JsonNode> invoicesById = new HashMap<>();
        for (JsonNode inv : client.fetchAll(Resource.INVOICES.id())) {
            String id = LagoApiClient.text(inv, "lago_id");
            if (!id.isEmpty()) {
                invoicesById.put(id, inv);
            }
        }
        List<List<String>> out = new ArrayList<>();
        for (JsonNode fee : fees) {
            JsonNode invoice = invoicesById.get(LagoApiClient.text(fee, "lago_invoice_id"));
            List<String> row = new ArrayList<>(20);
            row.add(LagoApiClient.text(fee, "lago_invoice_id"));
            row.add(invoice == null ? "" : LagoApiClient.text(invoice, "number"));
            row.add(invoice == null ? "" : LagoApiClient.text(invoice, "issuing_date"));
            row.add(LagoApiClient.text(fee, "lago_id"));
            row.add(LagoApiClient.text(fee, "item.type"));
            row.add(LagoApiClient.text(fee, "item.code"));
            row.add(LagoApiClient.text(fee, "item.name"));
            row.add(LagoApiClient.text(fee, "item.description"));
            row.add(LagoApiClient.text(fee, "item.invoice_display_name"));
            row.add(LagoApiClient.text(fee, "item.filter_invoice_display_name"));
            row.add(LagoApiClient.text(fee, "item.grouped_by"));
            row.add(LagoApiClient.text(fee, "external_subscription_id"));
            row.add(""); // subscription_plan_code：fee JSON 不返回，见类注释
            row.add(LagoApiClient.text(fee, "from_date"));
            row.add(LagoApiClient.text(fee, "to_date"));
            String currency = LagoApiClient.text(fee, "total_amount_currency");
            if (currency.isEmpty()) {
                currency = LagoApiClient.text(fee, "amount_currency");
            }
            row.add(currency);
            row.add(LagoApiClient.text(fee, "units"));
            row.add(LagoApiClient.text(fee, "precise_unit_amount"));
            row.add(LagoApiClient.text(fee, "taxes_amount_cents"));
            row.add(LagoApiClient.text(fee, "total_amount_cents"));
            out.add(row);
        }
        return out;
    }

    private List<String> prepareNotes(Resource resource, List<List<String>> records) {
        List<String> notes = new ArrayList<>();
        if (resource.id().equals(Resource.FEES.id())) {
            notes.add("subscription_plan_code 列留空：fee API JSON 不返回 plan_code"
                    + "（CSV 文件导出走数据库关联才有），键位/计费项/金额列不受影响");
            int noInvoice = 0;
            for (List<String> r : records) {
                if (r.get(1).isEmpty()) {
                    noInvoice++;
                }
            }
            if (noInvoice > 0) {
                notes.add(noInvoice + " 行 fee 的 invoice_number/invoice_issuing_date 留空："
                        + "对应发票不在 invoices 拉取范围内（发票多于 max-pages 或已归档），"
                        + "业务键与金额列不受影响");
            }
        }
        notes.add("映射口径核证自 Lago 源码 data_exports/csv/*.rb（CSV 导出与 REST API 同用 V1 serializer）");
        return notes;
    }

    // ------------------------------------------------------------------
    // CSV 落盘：RFC4180 引号转义；先写临时文件再原子改名，半截响应不落目录

    private String writeCsv(String resource, List<String> headers, List<List<String>> records) {
        Path dir = Path.of(properties.getConnector().getLago().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            String name = "lago-" + resource + "-" + TS.format(Instant.now()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".csv";
            Path tmp = dir.resolve(name + ".tmp");
            Path target = dir.resolve(name);
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(csvLine(headers));
                w.write("\r\n");
                for (List<String> record : records) {
                    w.write(csvLine(record));
                    w.write("\r\n");
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("拉取结果写盘失败（目录 " + dir + "）：" + e.getMessage(), e);
        }
    }

    private static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }
}
