package io.github.dolow.reconlens.pricing;

import io.github.dolow.reconlens.domain.PriceEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 价格解析的四个核心裁决，全部纯函数测试：
 * 月中调价回溯（按请求时间戳匹配旧价）/ cache 分价 / 阶梯落档 / fail-loud。
 */
class TokenPriceResolverTest {

    private static final Instant NOV_10 = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant NOV_14 = Instant.parse("2026-11-14T23:59:59Z");
    private static final Instant NOV_15 = Instant.parse("2026-11-15T00:00:00Z");

    private static PriceEntry entry(String type, String price, Instant from, Instant to,
                                    String tierMin, String tierMax) {
        return new PriceEntry(1L, "openai", "gpt-4o", type, new BigDecimal(price), "USD",
                from, to,
                tierMin == null ? null : new BigDecimal(tierMin),
                tierMax == null ? null : new BigDecimal(tierMax), null, null);
    }

    @Test
    void midMonthPriceChangeMatchesByRequestTimestamp() {
        // 月中调价：11-15 前后两窗。11-14 的请求走旧价 8.0，11-15 走新价 4.0
        List<PriceEntry> candidates = List.of(
                entry("INPUT", "8.00", Instant.parse("2026-11-01T00:00:00Z"), NOV_15, null, null),
                entry("INPUT", "4.00", NOV_15, null, null, null));

        PriceQuote old = TokenPriceResolver.resolve(candidates, NOV_14, new BigDecimal("1000000"));
        assertThat(old.entry().unitPricePerMillion()).isEqualByComparingTo("8.00");
        assertThat(old.amount()).isEqualByComparingTo("8.00000000");

        PriceQuote latest = TokenPriceResolver.resolve(candidates, NOV_15, new BigDecimal("1000000"));
        assertThat(latest.entry().unitPricePerMillion()).isEqualByComparingTo("4.00");

        // 窗口左闭右开：恰好落在 effective_to 的瞬间归下一窗，绝不两窗都算
        assertThat(TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("1"))
                .amount()).isEqualByComparingTo("0.00000800");
    }

    @Test
    void cacheTypesPriceSeparately() {
        // 同一 model：INPUT 8.0 / CACHE_READ 0.4 —— cache 分价是归因"cache 计价差异"的前提。
        // tokenType 过滤在上游 repository.candidates()，resolver 只裁决时间窗与阶梯
        List<PriceEntry> input = List.of(entry("INPUT", "8.00", NOV_10, null, null, null));
        List<PriceEntry> cacheRead = List.of(entry("CACHE_READ", "0.40", NOV_10, null, null, null));

        assertThat(TokenPriceResolver.resolve(input, NOV_10, new BigDecimal("2000000"))
                .amount()).isEqualByComparingTo("16.00000000");
        assertThat(TokenPriceResolver.resolve(cacheRead, NOV_10, new BigDecimal("2000000"))
                .amount()).isEqualByComparingTo("0.80000000");
    }

    @Test
    void tieredPricingFallsIntoBucketByThisRequestSize() {
        // 阶梯：前 1M tokens 8.0/M，1M 以上（本次请求量）4.0/M
        List<PriceEntry> candidates = List.of(
                entry("INPUT", "8.00", NOV_10, null, "0", "1000000"),
                entry("INPUT", "4.00", NOV_10, null, "1000000", null));

        assertThat(TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("999999"))
                .amount()).isEqualByComparingTo("7.99999200");
        assertThat(TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("1500000"))
                .amount()).isEqualByComparingTo("6.00000000");
        // 恰好 1M 落高档（下含上不含）
        assertThat(TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("1000000"))
                .entry().unitPricePerMillion()).isEqualByComparingTo("4.00");
    }

    @Test
    void amountPrecisionIsDeterministicBigDecimal() {
        // 1234567 tokens × 8.00/M = 9.876536 —— 绝不用 double，浮点金额是假差异温床
        List<PriceEntry> candidates = List.of(entry("INPUT", "8.00", NOV_10, null, null, null));
        assertThat(TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("1234567"))
                .amount()).isEqualByComparingTo("9.87653600");
    }

    @Test
    void noHitFailsWithUsableWindowList() {
        List<PriceEntry> candidates = List.of(
                entry("INPUT", "8.00", Instant.parse("2026-11-01T00:00:00Z"), NOV_15, null, null));
        // 12 月的请求落在窗外空档：报错要列出现有窗，让人看出"调价衔接漏了一段"
        Instant dec = Instant.parse("2026-12-01T00:00:00Z");
        assertThatThrownBy(() -> TokenPriceResolver.resolve(candidates, dec, new BigDecimal("100")))
                .isInstanceOf(PriceNotResolvedException.class)
                .hasMessageContaining("2026-11-01T00:00:00Z")
                .hasMessageContaining("2026-11-15T00:00:00Z")
                .hasMessageContaining("空档");
    }

    @Test
    void overlappingWindowsFailLoudInsteadOfTakingTheFirstOne() {
        // 歧义窗绝不允许"取第一条" —— 取第一条等于把配置错误洗成账单对上了
        List<PriceEntry> candidates = List.of(
                entry("INPUT", "8.00", NOV_10, Instant.parse("2026-11-20T00:00:00Z"), null, null),
                entry("INPUT", "4.00", Instant.parse("2026-11-15T00:00:00Z"), null, null, null));
        assertThatThrownBy(() -> TokenPriceResolver.resolve(candidates, NOV_15, new BigDecimal("100")))
                .isInstanceOf(PriceNotResolvedException.class)
                .hasMessageContaining("歧义").hasMessageContaining("2 条")
                .hasMessageContaining("取第一条");
    }

    @Test
    void tierGapIsReportedAsNotFound() {
        // 阶梯断档：0~1M 和 2M~∞，1.5M 落在缝里
        List<PriceEntry> candidates = List.of(
                entry("INPUT", "8.00", NOV_10, null, "0", "1000000"),
                entry("INPUT", "4.00", NOV_10, null, "2000000", null));
        assertThatThrownBy(() -> TokenPriceResolver.resolve(candidates, NOV_10, new BigDecimal("1500000")))
                .isInstanceOf(PriceNotResolvedException.class)
                .hasMessageContaining("阶梯");
    }
}
