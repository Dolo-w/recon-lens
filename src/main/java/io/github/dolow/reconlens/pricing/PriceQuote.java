package io.github.dolow.reconlens.pricing;

import io.github.dolow.reconlens.domain.PriceEntry;

import java.math.BigDecimal;
import java.time.Instant;

/** 解析成功的结果：命中条目 + 请求参数 + 算出的金额。 */
public record PriceQuote(PriceEntry entry, Instant at, BigDecimal tokens, BigDecimal amount) {
}
