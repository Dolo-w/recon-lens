package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 差异集合摘要（设计文档 §5.3；原 {@code DiffFingerprint}，评审 R3 改名）。
 *
 * <p>对 {@code (diff_type, biz_key, field_name, left_value, right_value, delta_value)} 归一化、
 * 排序后取 sha256，因此与落库顺序、分片数、并行度都无关 —— 这是 T2（同一份样本跑两次，摘要必须一致）
 * 能成立的前提。同时带分组计数，给人看"差异长什么样"。
 *
 * <p>为什么包含取值：只比键集合时，"键一样、值全算错"在摘要上完全看不出来。T2 本身是同一份规则跑两次，
 * 值不会变；但摘要的另一个用途是给人对比两份不同规则/不同取值的结论 —— 不含值就等于
 * 把"结果不同"报成"结果相同"。代价是每条差异多两次字符串编码，实测约 +0.5 µs/条，
 * 只发生在试算路径（生产路径不再计算摘要，见 {@code ShardRunner}）。
 *
 * <p><b>输入必须是内存态的 {@link DiffRecord}</b>（评审 B2）：{@code recon_diff} 落库时
 * {@code bizKey} 截到 128、{@code leftValue/rightValue} 截到 512。拿库里的记录算摘要，
 * 两条前 128 字符相同的业务键会得到<b>同一个</b>摘要 —— 这比漏判更糟，
 * 因为它给的是"确定"的假象。
 *
 * <p><b>超上限时明确放弃，不截断</b>。摘要要求把全部键排一次序，天然是 O(n) 内存；
 * 超过 {@code maxKeys} 时 {@link Accumulator#build()} 直接返回空，
 * 由调用方记录"为什么没有摘要"，而不是偷偷算一个"只覆盖前 N 条"的摘要 ——
 * 那种摘要看起来完全正常，却会在 T2 里制造随机的假失败。
 *
 * <p>注意本记录<b>不含 {@code Coverage}</b>：摘要只回答"差异集合变没变"，
 * 采样信封（左侧行数、覆盖分片数、键区间）是另一个概念，由闸门 2 单独持有。
 * 两者混在一起，会让 T2 的等值判断把"覆盖度"也算进去 —— 而 T2 要的恰恰是
 * "同一份样本、同一次覆盖下，结果是否可复现"。
 */
public record DiffSetDigest(String sha256,
                            int count,
                            Map<String, Long> byType,      // diff_type → 条数
                            Map<String, Long> byField) {   // field_name → 条数（整条记录级差异记在 RECORD_LEVEL）

    /** 整条记录级差异（单边缺失 / 重复键）在 {@link #byField()} 里的分组名。 */
    public static final String RECORD_LEVEL = "(记录级)";

    /** 默认上限：单分片差异键数。50k 个键约占几 MB，够观察真实分布，又不至于把分片堆爆。 */
    public static final int DEFAULT_MAX_KEYS = 50_000;

    public DiffSetDigest {
        Objects.requireNonNull(sha256, "sha256");
        byType = sortedCopy(byType);
        byField = sortedCopy(byField);
    }

    private static Map<String, Long> sortedCopy(Map<String, Long> source) {
        return Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(source, "counts")));
    }

    /** 便于日志展示：摘要前 12 位足够区分，完整值仍在 {@link #sha256()} 里。 */
    public String shortSha() {
        return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
    }

    /**
     * 累计器：边产出差异边记账，最后一次性排序取哈希。
     *
     * @param keyType 业务键类型，决定 {@link ValueComparator#canonicalKey} 的归一化口径
     * @param maxKeys 键数上限；超过后不再记录键，只在 {@link #overflowed()} 上留痕
     */
    public static Accumulator accumulator(FieldType keyType, int maxKeys) {
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys 必须 ≥ 1（要关掉摘要就别接这个 sink，"
                    + "而不是给一个 0 让摘要静默变成空）: " + maxKeys);
        }
        return new Accumulator(Objects.requireNonNull(keyType, "keyType"), maxKeys);
    }

    public static final class Accumulator {

        private final FieldType keyType;
        private final int maxKeys;
        private final List<String> lines = new ArrayList<>();
        private final Map<String, Long> byType = new HashMap<>();
        private final Map<String, Long> byField = new HashMap<>();

        private int count;
        private boolean overflowed;

        private Accumulator(FieldType keyType, int maxKeys) {
            this.keyType = keyType;
            this.maxKeys = maxKeys;
        }

        /** 记一条差异。落库失败的差异不该进来，所以调用方要先确保它真的写出去了。 */
        public void accept(DiffRecord diff) {
            Objects.requireNonNull(diff, "diff");
            count++;
            byType.merge(String.valueOf(diff.diffType()), 1L, Long::sum);
            byField.merge(isRecordLevel(diff.field()) ? RECORD_LEVEL : diff.field(), 1L, Long::sum);
            if (overflowed) {
                return;
            }
            if (lines.size() >= maxKeys) {
                // 超限就把已经存下的键全部丢掉：留着一半既不能出摘要，又占着内存
                overflowed = true;
                lines.clear();
                return;
            }
            lines.add(line(diff));
        }

        /** 已接收的差异条数（超限后仍然如实累计）。 */
        public int count() {
            return count;
        }

        public boolean overflowed() {
            return overflowed;
        }

        /**
         * 没有超限就产出摘要 —— 差异 0 条也有摘要，因为"空集合"本身就是合法的基线；
         * 超限返回空，由调用方记录原因（而不是给一个只覆盖部分数据的摘要）。
         */
        public Optional<DiffSetDigest> build() {
            if (overflowed) {
                return Optional.empty();
            }
            List<String> sorted = new ArrayList<>(lines);
            Collections.sort(sorted);
            MessageDigest digest = newSha256();
            for (String line : sorted) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return Optional.of(new DiffSetDigest(toHex(digest.digest()), count, byType, byField));
        }

        private String line(DiffRecord diff) {
            // 每个字段都按"长度:内容"编码：业务键里出现分隔符也无歧义，
            // 否则 ("ab","c") 与 ("a","bc") 会拼成同一个串
            return encode(String.valueOf(diff.diffType()))
                    + encode(ValueComparator.canonicalKey(keyType, diff.bizKey()))
                    + (isRecordLevel(diff.field()) ? "-" : "+" + encode(diff.field()))
                    + encode(text(diff.leftValue()))
                    + encode(text(diff.rightValue()))
                    + encode(delta(diff.deltaValue()));
        }

        /** null 与空串必须能分清（前者是"这一侧没有值"，后者是"值是空字符串"）。 */
        private static String text(String value) {
            return value == null ? "\u0000null" : value;
        }

        /** {@code 1.0} 与 {@code 1.00} 是同一个数：不做归一化会让两次跑的摘要无端不同。 */
        private static String delta(java.math.BigDecimal value) {
            return value == null ? "\u0000null" : value.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * "整条记录级差异"的判定 —— 引擎实际写的是<b>空串</b>（{@code MergeMatcher} 里
     * {@code emitMissingRight/emitMissingLeft/duplicateDiff} 都传 {@code ""}），
     * 而 {@link DiffRecord} 的注释写的是"为空"。只判 {@code null} 会让这类差异在
     * {@code byField} 里落到一个空名字的分组，哈希里也和普通字段差异混在一起。
     */
    private static boolean isRecordLevel(String field) {
        return field == null || field.isBlank();
    }

    private static String encode(String text) {
        return text.length() + ":" + text;
    }

    /** 不能叫 {@code sha256()} —— 那是记录组件 `sha256` 的访问器名，重名会被当成非法访问器。 */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
