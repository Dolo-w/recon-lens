package io.github.dolow.reconlens.pricing;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.store.ReconRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计量漂移归因：把差异条目乘上价格，出"少收的钱"。
 *
 * <p>画像里 CTO 的原话是"我要的不是 HIGH severity，是客户 A 少收 ¥8,200"——
 * severity 是工程师的口径，金额是财务的口径，这道翻译就在这里。
 *
 * <p><b>语义约定</b>：left_value = 我方账单口径（应收），right_value = 对方/厂商口径
 * （实收/实付）。{@code impact = leftAmount - rightAmount}：
 * <ul>
 *   <li>负数 = 我方少收（客户少付了 / 厂商多扣了），催收或索赔的依据；</li>
 *   <li>正数 = 我方多收，客户来对质时要有解释。</li>
 * </ul>
 *
 * <p><b>计价时点（at）的优先级</b>：请求显式传 at（人主动指定，最高）→
 * 差异的<b>业务发生时刻</b>（规则配了 {@code eventTime} 列，事件级对账——月中调价时
 * 同一份差异两侧能算出各自时点的价）→ 任务完成时刻（没配 eventTime 的老规则兜底）。
 * 事件时间解析不了（来源列类型五花八门）就静默回退下一级——宁可回退也不猜错。
 *
 * <p>v1 是<b>单映射</b>：一个 (priceCode, model, tokenType) 作用于整个 job ——
 * 单价格场景已经能出数；多模型混跑（按 field_name 路由到不同价格）留给接了
 * usage 明细之后。
 */
@Service
public class PriceImpactService {

    private final ReconRepository reconRepository;
    private final PriceCatalogService catalog;

    public PriceImpactService(ReconRepository reconRepository, PriceCatalogService catalog) {
        this.reconRepository = reconRepository;
        this.catalog = catalog;
    }

    /** 单条差异计价。值不可解析成数值时直接抛错（单条是人主动查的，fail-loud）。 */
    public PriceImpact impactOf(DiffRecord diff, String priceCode, String model, String tokenType,
                                Instant at) {
        BigDecimal left = numeric(diff.leftValue());
        BigDecimal right = numeric(diff.rightValue());
        if (left == null || right == null) {
            throw new IllegalArgumentException("差异值不是数值，无法计价：left=\"" + diff.leftValue()
                    + "\" right=\"" + diff.rightValue() + "\"（" + diff.diffType() + " "
                    + (diff.field().isBlank() ? "记录级" : diff.field()) + "）—— "
                    + "状态类/文本类差异没有金额语义，不参与计量漂移归因");
        }
        Instant atFinal = resolveAt(diff, at, reconRepository.findJob(diff.jobId())
                .map(ReconJob::finishedAt)
                .map(j -> j == null ? null : j)
                .orElse(null));
        if (atFinal == null) {
            atFinal = Instant.now();
        }
        return compute(diff, priceCode, model, tokenType, atFinal, left, right);
    }

    /**
     * job 级汇总：逐条计价 + 汇总"少收的钱"。不可计价的差异（状态类/文本类）跳过并
     * 计数，绝不整单失败 —— 100 条里 80 条能算就先出 80 条的账，剩下 20 条如实报告。
     */
    public Map<String, Object> summarizeJob(long jobId, String priceCode, String model,
                                            String tokenType, Instant at) {
        ReconJob job = reconRepository.findJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: id=" + jobId));
        Instant atFinal = at != null ? at : null;
        List<PriceImpact> priced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<DiffRecord> diffs = reconRepository.listDiffs(jobId, null, 0, Integer.MAX_VALUE);
        for (DiffRecord diff : diffs) {
            BigDecimal left = numeric(diff.leftValue());
            BigDecimal right = numeric(diff.rightValue());
            if (left == null || right == null) {
                skipped.add("#" + diff.id() + " " + diff.diffType() + " 值非数值，跳过");
                continue;
            }
            // job 级汇总：显式 at > 差异事件时间（每条各自的业务发生时刻，月中调价归因的关键）
            // > 任务完成时刻。atFinal 为 null 说明前两级都没给出，由 resolveAt 用任务时刻兜底。
            Instant diffAt = atFinal != null ? atFinal
                    : resolveAt(diff, null, job.finishedAt() != null ? job.finishedAt() : job.createdAt());
            priced.add(compute(diff, priceCode, model, tokenType, diffAt, left, right));
        }
        BigDecimal total = priced.stream()
                .map(PriceImpact::impact)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("priceCode", priceCode);
        body.put("model", model);
        body.put("tokenType", tokenType);
        body.put("at", at != null ? at.toString()
                : "逐条按业务发生时刻（规则 eventTime）计价；未配 eventTime 的差异回退任务完成时刻");
        body.put("diffTotal", diffs.size());
        body.put("pricedCount", priced.size());
        body.put("skippedCount", skipped.size());
        body.put("skipped", skipped);
        body.put("totalImpact", total.toPlainString());
        body.put("impactNote", "impact = left(我方账单口径) - right(对方口径)；负数=少收，正数=多收");
        body.put("items", priced);
        return body;
    }

    /**
     * 计价时点解析：显式 at 最高；其次差异的业务发生时刻（{@code eventAt} 原样字符串，
     * 尽力解析，解析不了静默回退）；最后任务完成/创建时刻。全部缺失返回 null（调用方 now() 兜底）。
     */
    private static Instant resolveAt(DiffRecord diff, Instant explicitAt, Instant jobFallback) {
        if (explicitAt != null) {
            return explicitAt;
        }
        Instant fromEvent = parseEventAt(diff.eventAt());
        if (fromEvent != null) {
            return fromEvent;
        }
        return jobFallback;
    }

    /** 事件时间字符串解析（尽力而为，public 供回退行为断言直接测试）：支持 ISO 与 {@code yyyy-MM-dd[ HH:mm[:ss]]}。 */
    public static Instant parseEventAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            // 不是 ISO-Instant 形态，继续试本地日期时间
        }
        try {
            String normalized = v.replace('T', ' ');
            String[] parts = normalized.split(" ");
            java.time.LocalDate date = java.time.LocalDate.parse(parts[0]);
            java.time.LocalTime time = parts.length > 1
                    ? java.time.LocalTime.parse(parts[1])
                    : java.time.LocalTime.MIN;
            return date.atTime(time).toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception e) {
            return null; // 解析不了：宁可回退任务时刻，也不猜一个错时点
        }
    }

    private PriceImpact compute(DiffRecord diff, String priceCode, String model, String tokenType,
                                Instant at, BigDecimal left, BigDecimal right) {
        // 两侧 token 数可能落不同阶梯档 → 各自 resolve，命中条目作为证据一起带回
        PriceQuote leftQuote = catalog.resolve(priceCode, model, tokenType, at, left);
        PriceQuote rightQuote = catalog.resolve(priceCode, model, tokenType, at, right);
        BigDecimal impact = leftQuote.amount().subtract(rightQuote.amount());
        return new PriceImpact(diff.id(), diff.diffType(), diff.bizKey(),
                diff.field().isBlank() ? null : diff.field(),
                left, right,
                leftQuote.amount(), rightQuote.amount(), impact,
                leftQuote.entry().currency(),
                leftQuote.entry().unitPricePerMillion().toPlainString(),
                leftQuote.entry().effectiveFrom().toString(),
                leftQuote.entry().effectiveTo() == null ? null : leftQuote.entry().effectiveTo().toString(),
                diff.eventAt(), at.toString());
    }

    /** 严格数值解析：空、null、非数值都返回 null（"3000 tokens" 这种带字的也不猜）。 */
    private static BigDecimal numeric(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 一条差异的计价结果（命中价格条目与计价时点证据一起带出，供争议证据包直接引用）。 */
    public record PriceImpact(Long diffId, String diffType, String bizKey, String field,
                              BigDecimal leftTokens, BigDecimal rightTokens,
                              BigDecimal leftAmount, BigDecimal rightAmount, BigDecimal impact,
                              String currency, String unitPricePerMillion,
                              String effectiveFrom, String effectiveTo,
                              String eventAt, String pricedAt) {
    }
}
