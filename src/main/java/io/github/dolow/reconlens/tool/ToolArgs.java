package io.github.dolow.reconlens.tool;

import java.util.Map;

/**
 * 参数解析。语义与 {@code McpToolFactory} 里那几个同名方法<b>逐条对齐</b>
 * （必填缺失报「缺少必填参数 x」、数字解析失败报「参数 x 必须是整数，收到：…」），
 * 因为两条路径给用户看到的错误文案必须是同一句 —— 否则用户会以为是两个不同的问题。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String text(Map<String, Object> args, String key, boolean required) {
        Object raw = args.get(key);
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("缺少必填参数 " + key);
            }
            return null;
        }
        return value;
    }

    public static long integer(Map<String, Object> args, String key, long fallback, boolean required) {
        Object raw = args.get(key);
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException("缺少必填参数 " + key);
            }
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 必须是整数，收到：" + raw);
        }
    }

    /** 可选整数：{@code null} 表示调用方没传（与"传了 0"是两件事）。 */
    public static Integer optionalInteger(Map<String, Object> args, String key) {
        return args.get(key) == null ? null : (int) integer(args, key, 0, true);
    }

    /** 带默认值的可选整数：没传就用 {@code fallback}，传了仍走 {@link #integer} 的校验与文案。 */
    public static int optionalInteger(Map<String, Object> args, String key, int fallback) {
        Integer value = optionalInteger(args, key);
        return value == null ? fallback : value;
    }
}
