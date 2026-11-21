package io.github.dolow.reconlens.pricing;

import io.github.dolow.reconlens.domain.PriceEntry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 价格解析：给定 (model, token_type, 请求时间戳, tokens) 从候选条目里选出唯一命中，
 * 算出金额。纯函数，不碰库。
 *
 * <p>fail-loud 立场与对账内核一致：<b>找不到价</b>和<b>撞出两个价</b>都必须报错停下，
 * 绝不"取第一条"—— 取第一条等于把"价格表配错"洗成"账单对得上"，假差异和假平账
 * 都从这里来。两个异常都带上下文（可用窗列表/撞车的条目），人一看就知道改哪里。
 */
public final class TokenPriceResolver {

    /** 每百万 token 的除数。 */
    static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    /** 金额统一 8 位小数：比计费系统常见的 6 位多留两位，比较时再做舍入配置。 */
    private static final int AMOUNT_SCALE = 8;

    private TokenPriceResolver() {
    }

    /**
     * @throws PriceNotResolvedException 0 命中（报可用时间窗）或 ≥2 命中（报撞车条目）
     */
    public static PriceQuote resolve(List<PriceEntry> candidates, Instant at, BigDecimal tokens) {
        List<PriceEntry> hits = candidates.stream()
                .filter(e -> e.inWindow(at) && e.inTier(tokens))
                .toList();
        if (hits.isEmpty()) {
            throw new PriceNotResolvedException(notFoundMessage(candidates, at, tokens));
        }
        if (hits.size() > 1) {
            throw new PriceNotResolvedException(ambiguousMessage(hits, at, tokens));
        }
        PriceEntry hit = hits.get(0);
        BigDecimal amount = tokens.multiply(hit.unitPricePerMillion())
                .divide(PER_MILLION, MathContext.DECIMAL64)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new PriceQuote(hit, at, tokens, amount);
    }

    private static String notFoundMessage(List<PriceEntry> candidates, Instant at, BigDecimal tokens) {
        StringBuilder sb = new StringBuilder("价格未命中：该 model/tokenType 在 ")
                .append(at).append("、tokens=").append(tokens.toPlainString())
                .append(" 处没有任何价格条目。");
        if (candidates.isEmpty()) {
            sb.append("价格表里这个 model+tokenType 一条都没有 —— 先录入价格。");
        } else {
            sb.append("现有条目的时间窗/阶梯：");
            for (PriceEntry e : candidates) {
                sb.append(" [").append(e.effectiveFrom()).append(" ~ ")
                        .append(e.effectiveTo() == null ? "至今" : e.effectiveTo())
                        .append(", tier ").append(e.tierMin()).append("~")
                        .append(e.tierMax() == null ? "∞" : e.tierMax()).append("]");
            }
            sb.append("。多半是请求时间戳落在窗外的空档（调价衔接漏了一段），或 tokens 落在阶梯断档里。");
        }
        return sb.toString();
    }

    private static String ambiguousMessage(List<PriceEntry> hits, Instant at, BigDecimal tokens) {
        StringBuilder sb = new StringBuilder("价格歧义：").append(at).append("、tokens=")
                .append(tokens.toPlainString()).append(" 命中了 ").append(hits.size())
                .append(" 条价格条目，无法裁决（绝不允许'取第一条'）:");
        for (PriceEntry e : hits) {
            sb.append(" #").append(e.id()).append("[")
                    .append(e.effectiveFrom()).append(" ~ ")
                    .append(e.effectiveTo() == null ? "至今" : e.effectiveTo())
                    .append(", tier ").append(e.tierMin()).append("~")
                    .append(e.tierMax() == null ? "∞" : e.tierMax())
                    .append(", ").append(e.unitPricePerMillion()).append("/M]");
        }
        sb.append("。修价格表：两窗重叠时把旧窗的 effective_to 截到新窗 effective_from。");
        return sb.toString();
    }
}
