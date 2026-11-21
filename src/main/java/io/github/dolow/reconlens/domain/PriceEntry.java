package io.github.dolow.reconlens.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条价格条目：{@code price_code × model × token_type} 在 {@code [effectiveFrom, effectiveTo)}
 * 时间窗、{@code [tierMin, tierMax)} tokens 区间内的每百万 token 单价。
 *
 * <p>三个正交维度是刻意分开的：
 * <ul>
 *   <li><b>时间窗</b>：月中调价按<b>请求时间戳</b>匹配旧价格 —— 11-14 的请求走旧价、
 *       11-15 的走新价，回溯对账时不会把整月都按新价重算；</li>
 *   <li><b>token_type</b>：同一 model 的 input / output / cache_write / cache_read
 *       单价差好几倍，混成一个"平均价"就做不出 cache 计价差异的归因；</li>
 *   <li><b>tier</b>：阶梯价（如前 1M tokens 一档、超出部分另一档），按<b>本次请求的
 *       tokens 数</b>落档，不是按累计量 —— 累计量口径需要计费周期上下文，留给上游。</li>
 * </ul>
 *
 * @param priceCode    价格表分组（厂商/合同价），允许多套并存互不干扰
 * @param model        归一小写后的模型名
 * @param tokenType    归一后的大写 token 类型（INPUT/OUTPUT/CACHE_WRITE/CACHE_READ/…）
 * @param unitPricePerMillion 每百万 token 单价（行业报价口径）
 * @param effectiveTo  NULL = 至今；窗口左闭右开，{@code at == effectiveTo} 落下一窗
 * @param tierMin      tokens 下限（含），恒非空（0 = 无阶梯）
 * @param tierMax      tokens 上限（不含），NULL = 无限
 * @param sourceNote   来源备注（官方定价页 URL / 抓取日期），审计用
 */
public record PriceEntry(
        Long id,
        String priceCode,
        String model,
        String tokenType,
        BigDecimal unitPricePerMillion,
        String currency,
        Instant effectiveFrom,
        Instant effectiveTo,
        BigDecimal tierMin,
        BigDecimal tierMax,
        String sourceNote,
        Instant createdAt) {

    public boolean inWindow(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public boolean inTier(BigDecimal tokens) {
        BigDecimal min = tierMin == null ? BigDecimal.ZERO : tierMin;
        return tokens.compareTo(min) >= 0 && (tierMax == null || tokens.compareTo(tierMax) < 0);
    }
}
