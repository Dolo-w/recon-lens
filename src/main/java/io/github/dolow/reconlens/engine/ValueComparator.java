package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.dsl.FieldRule;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 字段比较与业务键比较。所有数值比较都走 BigDecimal，避免 double 精度问题带来的"假差异"。
 */
public final class ValueComparator {

    /** 相对差的保留位数 */
    private static final int RATIO_SCALE = 6;

    private ValueComparator() {
    }

    /**
     * 按规则比较两个字段值。
     * <ul>
     *   <li>绝对容差与相对容差都配时，任一超限即算差异</li>
     *   <li>没配容差时，只要不相等就是差异</li>
     * </ul>
     */
    public static CompareResult compare(FieldRule rule, Object left, Object right) {
        if (left == null && right == null) {
            return CompareResult.same();
        }
        if (left == null || right == null) {
            // 一边有值一边为空：这本身就是需要人看的差异
            return new CompareResult(true, null, null);
        }
        return switch (rule.type()) {
            case STRING -> compareText(rule, left, right);
            case BOOLEAN -> compareBoolean(left, right);
            case LONG, DECIMAL -> compareNumber(rule, left, right);
        };
    }

    private static CompareResult compareText(FieldRule rule, Object left, Object right) {
        String l = Row.render(left).trim();
        String r = Row.render(right).trim();
        boolean same = rule.ignoreCase() ? l.equalsIgnoreCase(r) : l.equals(r);
        return new CompareResult(!same, null, null);
    }

    private static CompareResult compareBoolean(Object left, Object right) {
        boolean same = Boolean.parseBoolean(String.valueOf(left).trim())
                == Boolean.parseBoolean(String.valueOf(right).trim());
        return new CompareResult(!same, null, null);
    }

    private static CompareResult compareNumber(FieldRule rule, Object left, Object right) {
        BigDecimal l = toDecimal(left);
        BigDecimal r = toDecimal(right);
        if (l == null || r == null) {
            // 配置了数值类型但数据不是数字：报差异而不是抛异常，保证整个任务能跑完
            return new CompareResult(true, null, null);
        }
        BigDecimal delta = l.subtract(r);
        BigDecimal ratio = r.signum() == 0 ? null : delta.divide(r.abs(), RATIO_SCALE, RoundingMode.HALF_UP);

        boolean different;
        if (!rule.hasTolerance()) {
            different = delta.signum() != 0;
        } else {
            different = false;
            if (rule.tolerance() != null && delta.abs().compareTo(rule.tolerance()) > 0) {
                different = true;
            }
            if (rule.tolerancePercent() != null) {
                // 基数为 0 时相对差没有意义：任何非零偏差都判为差异
                different = different || (ratio == null ? delta.signum() != 0 : ratio.abs().compareTo(rule.tolerancePercent()) > 0);
            }
        }
        return new CompareResult(different, delta, ratio);
    }

    /** 业务键比较：必须与 SQL 的 ORDER BY 语义一致，否则归并会错位。 */
    public static int compareKeys(FieldType type, Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return switch (type) {
            case STRING -> Row.render(left).compareTo(Row.render(right));
            case BOOLEAN -> Boolean.compare(Boolean.parseBoolean(String.valueOf(left)), Boolean.parseBoolean(String.valueOf(right)));
            case LONG, DECIMAL -> {
                BigDecimal l = toDecimal(left);
                BigDecimal r = toDecimal(right);
                if (l == null || r == null) {
                    yield Row.render(left).compareTo(Row.render(right));
                }
                yield l.compareTo(r);
            }
        };
    }

    /**
     * 业务键的规范形态：只回答"这两个值是同一个键吗"，不参与大小比较。
     * <p><b>必须与 {@link #compareKeys} 同一口径</b>：{@code canonicalKey(x).equals(canonicalKey(y))}
     * ⟺ {@code compareKeys(type, x, y) == 0}。这条不变式由 {@code ValueComparatorTest} 对着样本矩阵断言。
     * <p>为什么非要对齐：摘要的用途是判断"两次跑的差异集合一样不一样"。如果匹配时 {@code 10.00} 与 {@code 10}
     * 算同一笔业务、算摘要时又算两个键，同一份数据两次跑就会得到不同摘要 —— T2 变成假失败，
     * 而排障时几乎不会怀疑到"归一化"头上。
     * <p>反过来也一样危险：STRING 下不能顺手 {@code trim()} / 转小写，因为归并用的是逐字符比较，
     * 那样做等于把两个<b>不同</b>的业务键并成一个，摘要会比实际差异"少"。
     */
    public static String canonicalKey(FieldType type, Object value) {
        if (value == null) {
            return "\u0000null";
        }
        return switch (type) {
            case STRING -> "s:" + Row.render(value);
            case BOOLEAN -> "b:" + Boolean.parseBoolean(String.valueOf(value));
            case LONG, DECIMAL -> {
                BigDecimal decimal = toDecimal(value);
                // 数字统一成"去掉尾随零的十进制串"：10.00 与 10 是同一个键；
                // 不是数字就退回文本形态，与 compareKeys 的降级分支一致
                yield decimal == null
                        ? "s:" + Row.render(value)
                        : "n:" + decimal.stripTrailingZeros().toPlainString();
            }
        };
    }

    public static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        String text = String.valueOf(value).trim().replace(",", "");
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
