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
