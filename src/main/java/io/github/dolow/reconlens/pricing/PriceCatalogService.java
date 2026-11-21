package io.github.dolow.reconlens.pricing;

import io.github.dolow.reconlens.domain.PriceEntry;
import io.github.dolow.reconlens.store.PriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 价格表目录服务：录入校验（归一 + 重叠检测）与解析入口。
 *
 * <p>重叠检测在<b>录入时</b>就做：两条同 (code, model, type, tier) 的时间窗相交，
 * 解析时就是歧义 —— 与其让 100 万条账单解析时逐条报歧义，不如让 1 条配置写进去时
 * 就报错。调价换窗的正确姿势：把旧窗 effective_to 截到新窗 effective_from（先改旧
 * 再录新，或直接录新再截旧，两步之间短暂重叠也行 —— 截完就消了）。
 */
@Service
public class PriceCatalogService {

    private final PriceRepository repository;

    public PriceCatalogService(PriceRepository repository) {
        this.repository = repository;
    }

    public PriceEntry create(PriceEntry draft) {
        String code = requireText(draft.priceCode(), "priceCode");
        String model = requireText(draft.model(), "model").toLowerCase(Locale.ROOT);
        String type = requireText(draft.tokenType(), "tokenType").toUpperCase(Locale.ROOT);
        BigDecimal price = draft.unitPricePerMillion();
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("unitPricePerMillion 必须是正数");
        }
        Instant from = draft.effectiveFrom();
        if (from == null) {
            throw new IllegalArgumentException("effectiveFrom 必填");
        }
        if (draft.effectiveTo() != null && !draft.effectiveTo().isAfter(from)) {
            throw new IllegalArgumentException("effectiveTo 必须晚于 effectiveFrom（窗口左闭右开）");
        }
        // tierMin 统一归一成 0 存储：唯一索引 (code, model, type, from, tier_min) 里的 NULL
        // 在 H2/MySQL 都不参与去重，留着 NULL 等于唯一索引对无阶梯条目失效
        BigDecimal tierMin = draft.tierMin() == null ? BigDecimal.ZERO : draft.tierMin();
        BigDecimal tierMax = draft.tierMax();
        if (tierMax != null && tierMax.compareTo(tierMin) <= 0) {
            throw new IllegalArgumentException("tierMax 必须大于 tierMin（阶梯下含上不含）");
        }
        if (tierMin.signum() < 0) {
            throw new IllegalArgumentException("tierMin 不能为负");
        }

        PriceEntry normalized = new PriceEntry(null, code, model, type, price,
                draft.currency() == null || draft.currency().isBlank() ? "CNY" : draft.currency().trim(),
                from, draft.effectiveTo(), tierMin, tierMax,
                draft.sourceNote() == null ? null : draft.sourceNote().trim(), null);
        rejectOverlap(normalized);
        return repository.insert(normalized);
    }

    /** 录入时拦歧义：同 (code, model, type) 下时间窗相交且 tier 区间相交的旧条目即拒绝。 */
    private void rejectOverlap(PriceEntry candidate) {
        List<PriceEntry> existing = repository.candidates(
                candidate.priceCode(), candidate.model(), candidate.tokenType());
        for (PriceEntry old : existing) {
            if (windowsOverlap(candidate, old) && tiersOverlap(candidate, old)) {
                throw new IllegalArgumentException("价格窗重叠：新条目 ["
                        + candidate.effectiveFrom() + " ~ "
                        + (candidate.effectiveTo() == null ? "至今" : candidate.effectiveTo())
                        + ", tier " + candidate.tierMin() + "~"
                        + (candidate.tierMax() == null ? "∞" : candidate.tierMax())
                        + "] 与已有条目 #" + old.id() + " ["
                        + old.effectiveFrom() + " ~ "
                        + (old.effectiveTo() == null ? "至今" : old.effectiveTo())
                        + ", tier " + old.tierMin() + "~"
                        + (old.tierMax() == null ? "∞" : old.tierMax())
                        + "] 相交。解析时会歧义，绝不允许'取第一条' —— "
                        + "请把旧窗的 effective_to 截到新窗 effective_from 再录入");
            }
        }
    }

    private static boolean windowsOverlap(PriceEntry a, PriceEntry b) {
        boolean aBeforeB = a.effectiveTo() != null && !a.effectiveTo().isAfter(b.effectiveFrom());
        boolean bBeforeA = b.effectiveTo() != null && !b.effectiveTo().isAfter(a.effectiveFrom());
        return !aBeforeB && !bBeforeA;
    }

    private static boolean tiersOverlap(PriceEntry a, PriceEntry b) {
        BigDecimal aMin = a.tierMin() == null ? BigDecimal.ZERO : a.tierMin();
        BigDecimal bMin = b.tierMin() == null ? BigDecimal.ZERO : b.tierMin();
        boolean aBelowB = a.tierMax() != null && a.tierMax().compareTo(bMin) <= 0;
        boolean bBelowA = b.tierMax() != null && b.tierMax().compareTo(aMin) <= 0;
        return !aBelowB && !bBelowA;
    }

    public PriceQuote resolve(String priceCode, String model, String tokenType, Instant at, BigDecimal tokens) {
        if (tokens == null || tokens.signum() < 0) {
            throw new IllegalArgumentException("tokens 必须是非负数");
        }
        List<PriceEntry> candidates = repository.candidates(
                requireText(priceCode, "priceCode"),
                requireText(model, "model").toLowerCase(Locale.ROOT),
                requireText(tokenType, "tokenType").toUpperCase(Locale.ROOT));
        return TokenPriceResolver.resolve(candidates, at, tokens);
    }

    public List<PriceEntry> list(String priceCode, String model, String tokenType) {
        return repository.list(priceCode, model, tokenType);
    }

    public void delete(long id) {
        repository.delete(id);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return value.trim();
    }
}
