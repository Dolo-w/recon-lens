package io.github.dolow.reconlens.connector;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.connector.orb.OrbApiClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orb 连接器服务（v3 扩展架构 §三：第二连接器，usage-based 计费厂商）。
 *
 * <p>职责边界与 {@link LagoConnectorService} 完全同构（拉取与导入分离、fail-loud、
 * 空数据不落盘），差异只在厂商形状：
 * <ul>
 *   <li>资源 {@code invoices}（GET /invoices，一行=一张发票）与
 *       {@code subscriptions}（GET /subscriptions/summary，一行=一个订阅摘要）；</li>
 *   <li><b>金额是字符串十进制元</b>（Orb 文档核证），按 DECIMAL 直接入库，
 *       不走 Lago 的 {@code _cents} 分换算；</li>
 *   <li>日期列存 ISO 8601 原文（VARCHAR），字符串排序即时间序，对账键不受影响。</li>
 * </ul>
 */
@Service
public class OrbConnectorService implements BillingConnector {

    private static final Logger log = LoggerFactory.getLogger(OrbConnectorService.class);

    private final OrbApiClient client;
    private final ReconProperties properties;

    public OrbConnectorService(OrbApiClient client, ReconProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 资源 → profile：拉哪类账单、按哪份映射落盘、导入时传哪个 profile。 */
    public record Resource(String id, String profileId, String description) {

        public static final Resource INVOICES = new Resource("invoices", "orb-invoices",
                "发票汇总（一行=一张发票，金额为元）");
        public static final Resource SUBSCRIPTIONS = new Resource("subscriptions", "orb-subscriptions",
                "订阅摘要（一行=一个订阅，plan/customer 引用结构）");

        public static Resource resolve(String id) {
            if (INVOICES.id().equals(id)) {
                return INVOICES;
            }
            if (SUBSCRIPTIONS.id().equals(id)) {
                return SUBSCRIPTIONS;
            }
            throw new IllegalArgumentException("未知资源: " + id + "，可用: "
                    + INVOICES.id() + ", " + SUBSCRIPTIONS.id());
        }

        ConnectorResource descriptor() {
            return new ConnectorResource(id(), profileId(), description());
        }
    }

    @Override
    public String name() {
        return "orb";
    }

    @Override
    public boolean configured() {
        String key = properties.getConnector().getOrb().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public List<ConnectorResource> resources() {
        return List.of(Resource.INVOICES.descriptor(), Resource.SUBSCRIPTIONS.descriptor());
    }

    @Override
    public SyncOutcome sync(String resourceId) {
        Resource resource = Resource.resolve(resourceId);
        if (!configured()) {
            throw new IllegalStateException("Orb 连接器未配置 API key"
                    + "（recon.connector.orb.api-key）——先填 key 再同步");
        }
        List<JsonNode> rows = client.fetchAll(resource.id());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Orb API 返回 0 行 " + resource.id()
                    + " —— 空数据不落盘不误导（先核对环境/时间范围，落一张空 CSV 只会让人误以为同步成功）");
        }
        List<String> headers = ColumnProfiles.resolve(resource.profileId()).requiredHeaders();
        List<List<String>> records = resource.id().equals(Resource.INVOICES.id())
                ? mapInvoices(rows) : mapSubscriptions(rows);
        Path dir = Path.of(properties.getConnector().getOrb().getDir());
        String csvFile = ConnectorCsvWriter.write(name(), resource.id(), dir, headers, records);
        log.info("Orb 连接器同步完成: resource={} apiRows={} csv={}", resource.id(), rows.size(), csvFile);
        return new SyncOutcome(resource.id(), csvFile, rows.size(), prepareNotes(resource),
                Map.of("tool", "recon_import_file",
                        "args", "file=" + csvFile + ", table=<自取表名>, profile=" + resource.profileId(),
                        "note", "导入走 agent 侧管道（profile 归一/白名单注册都在那一步），"
                                + "需 recon.agent.enabled=true；拉取与导入分离是有意为之，"
                                + "见 LagoConnectorService 类注释"));
    }

    // ------------------------------------------------------------------
    // 字段映射：路径核证自 docs.withorb.com list-invoices / list-subscription-summaries

    private List<List<String>> mapInvoices(List<JsonNode> invoices) {
        List<String> paths = List.of(
                "id", "invoice_number", "invoice_source", "status", "currency",
                "customer.id", "customer.external_customer_id", "subscription.id",
                "subtotal", "total", "amount_due",
                "invoice_date", "due_date",
                "service_period_start", "service_period_end",
                "paid_at", "voided_at", "memo", "created_at");
        List<List<String>> out = new ArrayList<>();
        for (JsonNode inv : invoices) {
            List<String> row = new ArrayList<>(paths.size());
            for (String p : paths) {
                row.add(OrbApiClient.text(inv, p));
            }
            out.add(row);
        }
        return out;
    }

    private List<List<String>> mapSubscriptions(List<JsonNode> subs) {
        List<String> paths = List.of(
                "id", "status", "name",
                "plan.id", "plan.external_plan_id", "plan.name",
                "customer.id", "customer.external_customer_id",
                "start_date", "end_date", "created_at");
        List<List<String>> out = new ArrayList<>();
        for (JsonNode sub : subs) {
            List<String> row = new ArrayList<>(paths.size());
            for (String p : paths) {
                row.add(OrbApiClient.text(sub, p));
            }
            out.add(row);
        }
        return out;
    }

    private List<String> prepareNotes(Resource resource) {
        List<String> notes = new ArrayList<>();
        if (resource.id().equals(Resource.INVOICES.id())) {
            notes.add("金额列（subtotal/total/amount_due）是 Orb 返回的字符串十进制元，"
                    + "按 DECIMAL 直接入库——与 Lago 的 _cents 分换算不同，无需除 100");
            notes.add("日期列存 ISO 8601 原文（含时区），字符串排序即时间序，对账键不受影响");
        }
        notes.add("subscriptions 走 /subscriptions/summary（轻量端点，plan/customer 为引用结构）；"
                + "行项目级对账用 invoices 后再按需拉单张发票明细");
        return notes;
    }
}
