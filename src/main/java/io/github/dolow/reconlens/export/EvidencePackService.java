package io.github.dolow.reconlens.export;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffLifecycle.Status;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.pricing.PriceImpactService;
import io.github.dolow.reconlens.pricing.PriceImpactService.PriceImpact;
import io.github.dolow.reconlens.store.DiffLifecycleRepository;
import io.github.dolow.reconlens.store.ReconRepository;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 争议证据包导出（杀手级需求③）：逐条差异 + 原始行凭证 + 适用价格，出一份
 * 可以直接发给客户/财务的 CSV。
 *
 * <p>画像里 CTO 要的是"拿着这份文件去和客户对质"，所以一份行里要凑齐三样东西：
 * <b>差异本身</b>（我方值/对方值/差额）、<b>当时的样子</b>（两侧整行 JSON 凭证，
 * 源表变了也不影响口径）、<b>适用价格</b>（单价 + 生效窗，计价有据可查）。
 * 这三样前面几个 phase 已经分别落库，这里只是把它们按差异逐条拼起来。
 *
 * <p><b>口径约定</b>：
 * <ul>
 *   <li>凭证按自然键 (job, shard, biz_key) 关联 —— 同一行多字段差异共享一份凭证；</li>
 *   <li>生命周期状态取台账最新动作；没有台账记录 = OPEN；</li>
 *   <li>计价列只有在请求带 priceCode 时才填；不可计价的差异（状态类/文本类）
 *       计价列留空 —— 导出绝不整单失败，能算的算、不能算的如实空着。</li>
 * </ul>
 */
@Service
public class EvidencePackService {

    private final ReconRepository reconRepository;
    private final DiffLifecycleRepository lifecycleRepository;
    private final PriceImpactService priceImpactService;
    private final ReconProperties properties;

    public EvidencePackService(ReconRepository reconRepository,
                               DiffLifecycleRepository lifecycleRepository,
                               PriceImpactService priceImpactService,
                               ReconProperties properties) {
        this.reconRepository = reconRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.priceImpactService = priceImpactService;
        this.properties = properties;
    }

    /** 导出一个任务的证据包 CSV。priceCode 为空时计价列整体留空。 */
    public byte[] exportJob(long jobId, String priceCode, String model, String tokenType, Instant at) {
        ReconJob job = reconRepository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: id=" + jobId));

        List<DiffRecord> diffs = reconRepository.listDiffs(jobId, null, 0, Integer.MAX_VALUE);
        Map<Long, Status> statuses = lifecycleRepository.currentStatuses(
                diffs.stream().map(DiffRecord::id).toList());
        Map<String, List<DiffEvidence>> evidence = groupEvidence(reconRepository.listEvidenceByJob(jobId));

        // 计价：有 priceCode 才算；summarizeJob 已按"可算的算、不可算的跳过"的口径工作
        Map<Long, PriceImpact> impacts = new HashMap<>();
        if (priceCode != null && !priceCode.isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = priceImpactService.summarizeJob(
                    jobId, priceCode, model, tokenType, at);
            for (Object item : (List<Object>) summary.get("items")) {
                PriceImpact impact = (PriceImpact) item;
                impacts.put(impact.diffId(), impact);
            }
        }

        EvidencePackCsv csv = new EvidencePackCsv();
        csv.row("差异ID", "任务ID", "规则code", "分片", "差异类型", "严重度", "业务键", "字段",
                "我方值(left)", "对方值(right)", "差额", "差异比", "解释",
                "事件时间", "生命周期状态", "凭证来源LEFT", "凭证JSON LEFT", "凭证来源RIGHT", "凭证JSON RIGHT",
                "计价模型", "token类型", "单价(每百万)", "我方金额", "对方金额", "金额影响",
                "币种", "价格生效起", "价格生效止", "计价时点");
        for (DiffRecord d : diffs) {
            List<DiffEvidence> ev = evidence.get(d.shardId() + "|" + d.bizKey());
            String leftSource = evSource(ev, DiffEvidence.LEFT);
            String leftJson = evJson(ev, DiffEvidence.LEFT);
            String rightSource = evSource(ev, DiffEvidence.RIGHT);
            String rightJson = evJson(ev, DiffEvidence.RIGHT);

            PriceImpact p = impacts.get(d.id());
            csv.row(
                    d.id(), d.jobId(), job.ruleCode(), d.shardId(), d.diffType(),
                    d.severity() == null ? "" : d.severity().name(),
                    d.bizKey(), d.field(),
                    d.leftValue(), d.rightValue(),
                    d.deltaValue() == null ? "" : d.deltaValue().toPlainString(),
                    d.deltaRatio() == null ? "" : d.deltaRatio().toPlainString(),
                    d.explanation() == null ? "" : d.explanation(),
                    d.eventAt() == null ? "" : d.eventAt(),
                    statuses.getOrDefault(d.id(), Status.OPEN).name(),
                    leftSource, leftJson, rightSource, rightJson,
                    p == null ? "" : nz(model), p == null ? "" : nz(tokenType),
                    p == null ? "" : nz(p.unitPricePerMillion()),
                    p == null ? "" : p.leftAmount().toPlainString(),
                    p == null ? "" : p.rightAmount().toPlainString(),
                    p == null ? "" : p.impact().toPlainString(),
                    p == null ? "" : nz(p.currency()),
                    p == null ? "" : nz(p.effectiveFrom()),
                    p == null ? "" : nz(p.effectiveTo()),
                    p == null ? "" : nz(p.pricedAt()));
        }
        return csv.bytes();
    }

    /**
     * 导出并落盘（MCP 工具用：Agent 拿到的是文件路径而不是几千行 CSV 文本）。
     * 文件名 {@code evidence-pack-job-<id>.csv}，覆盖旧包 —— 同一任务的证据包随生命周期
     * 状态演进，最新的就是最准的；旧包在 REST 响应里永远可以重新生成。
     */
    public Path exportToFile(long jobId, String priceCode, String model, String tokenType, Instant at) {
        byte[] body = exportJob(jobId, priceCode, model, tokenType, at);
        Path dir = Path.of(properties.getExport().getDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("evidence-pack-job-" + jobId + ".csv.tmp");
            Path target = dir.resolve("evidence-pack-job-" + jobId + ".csv");
            Files.write(tmp, body);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("证据包写盘失败（目录 " + dir + "）：" + e.getMessage(), e);
        }
    }

    private static Map<String, List<DiffEvidence>> groupEvidence(List<DiffEvidence> all) {
        Map<String, List<DiffEvidence>> out = new HashMap<>();
        for (DiffEvidence e : all) {
            out.computeIfAbsent(e.shardId() + "|" + e.bizKey(), k -> new java.util.ArrayList<>()).add(e);
        }
        return out;
    }

    private static String evSource(List<DiffEvidence> ev, String side) {
        if (ev == null) {
            return "";
        }
        return ev.stream().filter(e -> side.equals(e.side())).findFirst()
                .map(DiffEvidence::source).orElse("");
    }

    private static String evJson(List<DiffEvidence> ev, String side) {
        if (ev == null) {
            return "";
        }
        return ev.stream().filter(e -> side.equals(e.side())).findFirst()
                .map(DiffEvidence::rowJson).orElse("");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
