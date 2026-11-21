package io.github.dolow.reconlens.export;

import io.github.dolow.reconlens.domain.DiffLifecycle.Status;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.explain.KnowledgeRoiService;
import io.github.dolow.reconlens.lifecycle.DiffLifecycleService;
import io.github.dolow.reconlens.pricing.PriceImpactService;
import io.github.dolow.reconlens.pricing.PriceImpactService.PriceImpact;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 财务可读报告（出口层最后一块：07 路线客户介意清单第 5 条——
 * 「报告是工程师 debug 日志还是财务核验凭证，决定谁能用它」）。
 *
 * <p>给定时间窗（默认近 30 天），把全部任务的差异汇总成一页中文 HTML：
 * 概览卡片（任务数/扫描行数/差异总数/闭环率）→ 差异分类 → 金额影响 TOP（配了价格表才出）→
 * 闭环台账 → 知识层省钱账 → 口径说明。财务拿到的是「发生了什么、追回了什么口径、还挂着多少」，
 * 不是 shard 明细和 SQL。
 *
 * <p>口径纪律（与引擎一致，不另造第二套）：
 * <ul>
 *   <li>单侧缺失先看水位：{@code *_PENDING} 是"未就绪"，不是差异；</li>
 *   <li>豁免的差异不再计入"待处理"；</li>
 *   <li>金额影响 = 我方 − 对方，负数 = 少收；没有价格表就如实说"这段缺席"；</li>
 *   <li>知识层计数是进程累计值，重启归零 —— 话必须写在页面上。</li>
 * </ul>
 */
@Service
public class FinanceReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReconRepository repository;
    private final DiffLifecycleService lifecycle;
    private final PriceImpactService priceImpact;
    private final KnowledgeRoiService roiService;

    public FinanceReportService(ReconRepository repository, DiffLifecycleService lifecycle,
                                PriceImpactService priceImpact, KnowledgeRoiService roiService) {
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.priceImpact = priceImpact;
        this.roiService = roiService;
    }

    /** 报告数据（tests 用）。窗口内任务按完成/创建时刻过滤。 */
    public record ReportData(int days, Instant windowStart, Instant windowEnd,
                             int jobCount, long scannedRows, long diffTotal, long matchedRows,
                             Map<String, Long> byType, Map<String, Long> bySeverity,
                             long lifecycleTotal, Map<String, Long> byStatus, Double closureRate,
                             boolean priced, String priceCode, BigDecimal totalImpact,
                             int pricedCount, int skippedPricingCount, List<PriceImpact> topImpacts,
                             KnowledgeRoiService.Roi roi) {
    }

    public ReportData data(int days, String priceCode, String model, String tokenType) {
        int windowDays = Math.max(1, Math.min(days, 365));
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(windowDays));

        List<ReconJob> jobs = repository.listJobs(1000).stream()
                .filter(j -> {
                    Instant t = j.finishedAt() != null ? j.finishedAt() : j.createdAt();
                    return t != null && !t.isBefore(start) && !t.isAfter(end);
                })
                .toList();

        long scanned = 0;
        long diffs = 0;
        long matched = 0;
        Map<String, Long> byType = new TreeMap<>();
        Map<String, Long> bySeverity = new TreeMap<>();
        Map<Status, Long> byStatus = new TreeMap<>();
        long lifecycleTotal = 0;
        long lifecycleClosed = 0;
        List<PriceImpact> allImpacts = new ArrayList<>();
        int pricedCount = 0;
        int skippedPricing = 0;
        boolean priced = priceCode != null && !priceCode.isBlank();

        for (ReconJob job : jobs) {
            scanned += job.leftRows() + job.rightRows();
            diffs += job.diffRows();
            matched += job.matchedRows();
            repository.summaryByType(job.id()).forEach(
                    (k, v) -> byType.merge(k, v, Long::sum));
            repository.summaryBySeverity(job.id()).forEach(
                    (k, v) -> bySeverity.merge(k, v, Long::sum));
            DiffLifecycleService.Snapshot snap = lifecycle.summary(job.id());
            if (snap.total() > 0) {
                lifecycleTotal += snap.total();
                lifecycleClosed += snap.byStatus().getOrDefault(Status.RESOLVED, 0L)
                        + snap.byStatus().getOrDefault(Status.EXEMPTED, 0L);
                snap.byStatus().forEach((k, v) -> byStatus.merge(k, v, Long::sum));
            }
            if (priced) {
                Map<String, Object> s = priceImpact.summarizeJob(job.id(), priceCode, model, tokenType, null);
                pricedCount += (int) s.get("pricedCount");
                skippedPricing += (int) s.get("skippedCount");
                @SuppressWarnings("unchecked")
                List<PriceImpact> items = (List<PriceImpact>) s.get("items");
                allImpacts.addAll(items);
            }
        }

        Double closureRate = lifecycleTotal == 0 ? null : (double) lifecycleClosed / lifecycleTotal;
        // TOP 口径：impact 升序 = 最负（少收最多）在前，只取前 10
        List<PriceImpact> top = priced
                ? allImpacts.stream()
                        .filter(i -> i.impact() != null)
                        .sorted(Comparator.comparing(PriceImpact::impact))
                        .limit(10)
                        .toList()
                : List.of();
        BigDecimal totalImpact = priced
                ? allImpacts.stream().map(PriceImpact::impact)
                        .filter(i -> i != null).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        return new ReportData(windowDays, start, end, jobs.size(), scanned, diffs, matched,
                byType, bySeverity, lifecycleTotal, toStringKeys(byStatus), closureRate,
                priced, priceCode, totalImpact, pricedCount, skippedPricing, top, roiService.roi());
    }

    private static Map<String, Long> toStringKeys(Map<Status, Long> byStatus) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            if (byStatus.containsKey(s)) {
                m.put(s.name(), byStatus.get(s));
            }
        }
        return m;
    }

    /** 一页中文 HTML（内联样式、可直接打印/转发）。 */
    public String html(int days, String priceCode, String model, String tokenType) {
        ReportData d = data(days, priceCode, model, tokenType);
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>对账财务报告（近 ").append(d.days()).append(" 天）</title>\n")
                .append("""
                        <style>
                          :root { color-scheme: light; }
                          body { font-family: "Microsoft YaHei", "PingFang SC", sans-serif; margin: 0;
                                 background: #f5f6f8; color: #1f2329; }
                          .page { max-width: 960px; margin: 0 auto; padding: 32px 24px 48px; }
                          h1 { font-size: 24px; margin: 0 0 4px; }
                          h2 { font-size: 17px; margin: 32px 0 12px; border-left: 4px solid #2f6fed;
                               padding-left: 10px; }
                          .meta { color: #646a73; font-size: 13px; margin-bottom: 24px; }
                          .cards { display: flex; gap: 12px; flex-wrap: wrap; }
                          .card { flex: 1 1 160px; background: #fff; border: 1px solid #e2e4ea;
                                  border-radius: 8px; padding: 14px 16px; }
                          .card .num { font-size: 26px; font-weight: 600; }
                          .card .label { color: #646a73; font-size: 12px; margin-top: 4px; }
                          table { width: 100%; border-collapse: collapse; background: #fff;
                                  border: 1px solid #e2e4ea; border-radius: 8px; overflow: hidden; }
                          th, td { padding: 8px 12px; text-align: left; font-size: 13px;
                                   border-bottom: 1px solid #f0f1f3; }
                          th { background: #f7f8fa; color: #4e5260; font-weight: 500; }
                          td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
                          .neg { color: #d83931; }
                          .pos { color: #2ea121; }
                          .note { background: #fffbe6; border: 1px solid #ffe58f; border-radius: 8px;
                                  padding: 10px 14px; font-size: 13px; color: #614700; margin: 12px 0; }
                          ul.caliber { font-size: 13px; color: #4e5260; line-height: 1.9; }
                          @media print { body { background: #fff; } .page { padding: 0; } }
                        </style>
                        </head>\n<body>\n<div class="page">\n""")
                .append("<h1>对账财务报告</h1>\n")
                .append("<div class=\"meta\">统计窗口：").append(fmt(d.windowStart(), zone))
                .append(" — ").append(fmt(d.windowEnd(), zone))
                .append("（近 ").append(d.days()).append(" 天）· 生成时刻 ")
                .append(fmt(Instant.now(), zone)).append("</div>\n");

        // ① 概览
        sb.append("<h2>一、本期概览</h2>\n<div class=\"cards\">\n");
        card(sb, d.jobCount(), "对账任务数");
        card(sb, d.scannedRows(), "扫描行数（两侧合计）");
        card(sb, d.matchedRows(), "比对一致行数");
        card(sb, d.diffTotal(), "差异总数");
        card(sb, d.closureRate() == null ? "—" : Math.round(d.closureRate() * 100) + "%", "闭环率");
        sb.append("</div>\n");

        // ② 差异分类
        sb.append("<h2>二、差异分类</h2>\n");
        if (d.byType().isEmpty()) {
            sb.append("<div class=\"note\">窗口内没有差异记录。</div>\n");
        } else {
            table(sb, List.of("差异类型", "条数"), d.byType(), null);
        }
        if (!d.bySeverity().isEmpty()) {
            sb.append("<div style=\"height:12px\"></div>\n");
            table(sb, List.of("严重级别", "条数"), d.bySeverity(), null);
        }

        // ③ 金额影响
        sb.append("<h2>三、金额影响</h2>\n");
        if (!d.priced()) {
            sb.append("<div class=\"note\">本期未指定价格表（priceCode），金额影响段缺席。")
                    .append("要出「少收的钱」，带 priceCode/model/tokenType 再生成一次。</div>\n");
        } else {
            sb.append("<div class=\"meta\">口径：金额影响 = 我方 − 对方；<b>负数 = 少收</b>。价格档：")
                    .append(esc(d.priceCode())).append("。可计价差异 ")
                    .append(d.pricedCount()).append(" 条，不可计价（状态/文本类）跳过 ")
                    .append(d.skippedPricingCount()).append(" 条。</div>\n")
                    .append("<p>合计影响：<b class=\"").append(d.totalImpact().signum() < 0 ? "neg" : "pos")
                    .append("\">").append(d.totalImpact().toPlainString())
                    .append("</b>（币种跟随价格表）</p>\n");
            if (d.topImpacts().isEmpty()) {
                sb.append("<div class=\"note\">窗口内没有可计价的差异。</div>\n");
            } else {
                sb.append("<table><tr><th>业务键</th><th>差异类型</th><th>字段</th>")
                        .append("<th class=\"num\">我方金额</th><th class=\"num\">对方金额</th>")
                        .append("<th class=\"num\">金额影响</th><th>业务发生时刻</th></tr>\n");
                for (PriceImpact i : d.topImpacts()) {
                    boolean neg = i.impact().signum() < 0;
                    sb.append("<tr><td>").append(esc(i.bizKey()))
                            .append("</td><td>").append(esc(i.diffType()))
                            .append("</td><td>").append(esc(i.field()))
                            .append("</td><td class=\"num\">").append(plain(i.leftAmount()))
                            .append("</td><td class=\"num\">").append(plain(i.rightAmount()))
                            .append("</td><td class=\"num ").append(neg ? "neg" : "pos").append("\">")
                            .append(plain(i.impact()))
                            .append("</td><td>").append(esc(i.eventAt() == null ? "—" : i.eventAt()))
                            .append("</td></tr>\n");
                }
                sb.append("</table>\n");
            }
        }

        // ④ 闭环台账
        sb.append("<h2>四、差异闭环台账</h2>\n");
        if (d.lifecycleTotal() == 0) {
            sb.append("<div class=\"note\">窗口内还没有差异进入闭环流程（认领/核销/豁免）。</div>\n");
        } else {
            Map<String, Long> ordered = new LinkedHashMap<>();
            for (String name : List.of("OPEN", "CLAIMED", "RESOLVED", "EXEMPTED")) {
                if (d.byStatus().containsKey(name)) {
                    ordered.put(name, d.byStatus().get(name));
                }
            }
            table(sb, List.of("生命周期状态", "条数"), ordered, null);
            sb.append("<p style=\"font-size:13px;color:#646a73\">闭环率 = （已核销 RESOLVED + 已豁免 EXEMPTED）÷ 台账总数，当前 ")
                    .append(d.closureRate() == null ? "—" : Math.round(d.closureRate() * 100) + "%").append("。</p>\n");
        }

        // ⑤ 知识层省钱账
        KnowledgeRoiService.Roi roi = d.roi();
        sb.append("<h2>五、知识层省钱账</h2>\n")
                .append("<p style=\"font-size:13px;color:#4e5260\">归因四层分工：L1 知识库（人工确认，零成本）→ ")
                .append("L0 模式库（机械联想，零成本）→ L2 大模型（花钱）→ L3 模板（免费兜底）。</p>\n")
                .append("<div class=\"cards\">\n");
        card(sb, roi.knowledgeHits(), "L1 知识库命中");
        card(sb, roi.patternHits(), "L0 模式联想");
        card(sb, roi.llmExplained(), "L2 模型调用");
        card(sb, roi.templateFallbacks(), "L3 模板兜底");
        card(sb, roi.llmCallsSaved(), "省下的模型调用");
        sb.append("</div>\n")
                .append("<p style=\"font-size:13px;color:#646a73\">知识库命中率 ")
                .append(String.format("%.0f%%", roi.knowledgeHitRate() * 100))
                .append("；库内条目按来源：").append(sourceText(roi.kbBySource()))
                .append("。</p>\n")
                .append("<div class=\"note\">").append(esc(roi.note())).append("</div>\n");

        // ⑥ 口径说明
        sb.append("<h2>六、口径说明</h2>\n<ul class=\"caliber\">\n")
                .append("<li>单侧记录缺失先看水位：标 <code>*_PENDING</code> 的差异是「对方数据未就绪」，不是真实差异，不进催收口径。</li>\n")
                .append("<li>已豁免（EXEMPTED）的差异是双方确认过的已知口径差，不再计入待处理。</li>\n")
                .append("<li>归因文本里标注「机器联想 [模式库:*]」的是统计规律；标注人工确认的才是实证结论。</li>\n")
                .append("<li>金额影响 = 我方账单口径 − 对方口径；负数 = 少收，正数 = 多收。月中调价按每条差异的业务发生时刻匹配当时价格。</li>\n")
                .append("</ul>\n</div>\n</body>\n</html>");
        return sb.toString();
    }

    private static void card(StringBuilder sb, Object num, String label) {
        sb.append("<div class=\"card\"><div class=\"num\">").append(num)
                .append("</div><div class=\"label\">").append(label).append("</div></div>\n");
    }

    private static void table(StringBuilder sb, List<String> headers, Map<String, Long> rows, String unit) {
        sb.append("<table><tr>");
        for (String h : headers) {
            sb.append("<th>").append(h).append("</th>");
        }
        sb.append("</tr>\n");
        for (Map.Entry<String, Long> e : rows.entrySet()) {
            sb.append("<tr><td>").append(esc(e.getKey())).append("</td><td class=\"num\">")
                    .append(e.getValue()).append(unit == null ? "" : unit).append("</td></tr>\n");
        }
        sb.append("</table>\n");
    }

    private static String sourceText(Map<String, Long> bySource) {
        if (bySource.isEmpty()) {
            return "暂无条目";
        }
        StringBuilder sb = new StringBuilder();
        bySource.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(esc(k)).append(' ').append(v).append(" 条");
        });
        return sb.toString();
    }

    private static String fmt(Instant t, ZoneId zone) {
        return t == null ? "—" : FMT.format(ZonedDateTime.ofInstant(t, zone));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
