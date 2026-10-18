package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 归因键：{@code rule_code + diff_type + field + feature_signature} 的稳定哈希。
 *
 * <p><b>为什么不是 {@code hash(rule_code, diff_type, field, cause)}</b>（评审 R3）：
 * 查库的那一刻你还不知道 {@code cause} —— 拿答案去查索引，命中率恒为 0。
 * {@code cause} 是<b>值</b>，不是键。
 *
 * <p><b>同一个错误往下还有一层，这里必须写清楚</b>：{@code feature_signature} 里
 * <b>绝不能放具体的业务值</b>（{@code bizKey} / 左右金额 / 时间戳）。
 * 一旦放进去，每条差异都有自己的键 —— 结构上又变回"命中率恒为 0"，
 * 只是从 {@code cause} 换成了金额，看代码看不出来，跑一周数据才能发现。
 * 所以签名只描述<b>形状</b>：方向（两侧都有 / 只有一侧）+ 值类型（数值 / 文本 / 空）+ 偏差量级档。
 *
 * <p>不把 {@code severity} 放进键：它是容差算出来的派生量，容差一调就分层，
 * 同一类业务原因会被拆成多条、互相抢命中率。
 */
public record CauseKey(String ruleCode, String diffType, String fieldName, String featureSignature) {

    /** 列宽：{@code rule_code} 64 / {@code diff_type} 64 / {@code field_name} 64 / {@code feature_signature} 256。 */
    private static final int MAX_CODE = 64;
    private static final int MAX_FIELD = 64;
    private static final int MAX_SIGNATURE = 256;

    public CauseKey {
        ruleCode = clip(normalize(ruleCode), MAX_CODE);
        diffType = clip(normalize(diffType), MAX_CODE);
        // 字段为空的差异（整条记录级：单边缺失 / 重复键）用空串而不是 "null"，
        // 否则同一类差异会因为字面量不同分叉成两个键
        fieldName = clip(normalize(fieldName), MAX_FIELD);
        featureSignature = clip(normalize(featureSignature), MAX_SIGNATURE);
    }

    public static CauseKey of(String ruleCode, DiffRecord diff) {
        return new CauseKey(ruleCode, diff.diffType(), diff.field(), signatureOf(diff));
    }

    /** 键的十六进制摘要，落 {@code recon_diff_kb.cause_key}。 */
    public String digest() {
        String material = ruleCode + '\u001f' + diffType + '\u001f' + fieldName + '\u001f' + featureSignature;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 标准 JRE 必备算法，走到这里说明运行环境已经不可信
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 人读形式，进日志与工单用（{@link #digest()} 是给数据库比对用的）。 */
    public String describe() {
        return ruleCode + "/" + diffType + (fieldName.isEmpty() ? "" : "/" + fieldName)
                + (featureSignature.isEmpty() ? "" : " [" + featureSignature + "]");
    }

    // ------------------------------------------------------------------
    // 特征签名：只描述形状，不描述数值
    // ------------------------------------------------------------------

    static String signatureOf(DiffRecord diff) {
        boolean left = present(diff.leftValue());
        boolean right = present(diff.rightValue());
        String direction = left && right ? "BOTH" : left ? "L_ONLY" : right ? "R_ONLY" : "NONE";

        // 值的类型比值的本身更能区分病因：左数值 / 右文本 型不一致多半是格式或单位问题，
        // 而数值 / 数值 型不一致才是金额偏差。这一步用"类型"而不是"内容"，同样是防止键爆炸。
        String types = kind(diff.leftValue()) + "/" + kind(diff.rightValue());

        return direction + "|" + types + "|" + band(diff.deltaRatio(), diff.deltaValue());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String kind(String value) {
        if (!present(value)) {
            return "E";
        }
        String trimmed = value.trim();
        if (isNumeric(trimmed)) {
            return "N";
        }
        // 只看形状不看内容：2026-10-17 / 2026/10/17 / 2026年10月17日 归为同一档
        return looksLikeDate(trimmed) ? "D" : "T";
    }

    private static boolean isNumeric(String value) {
        try {
            new BigDecimal(value.replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean looksLikeDate(String value) {
        return value.matches("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}.*");
    }

    /**
     * 偏差量级档：分档而不是存原值 —— 0.7% 与 0.8% 不该是两个键。
     * 没有比例时看有没有差值（{@code 0} / {@code NA} 是不同的病）。
     */
    private static String band(BigDecimal ratio, BigDecimal delta) {
        if (ratio == null) {
            return delta == null ? "NA" : "D";
        }
        BigDecimal abs = ratio.abs();
        if (abs.signum() == 0) {
            return "R0";
        }
        if (abs.compareTo(new BigDecimal("0.01")) < 0) {
            return "R<1P";
        }
        return abs.compareTo(new BigDecimal("0.10")) < 0 ? "R<10P" : "R>=10P";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
