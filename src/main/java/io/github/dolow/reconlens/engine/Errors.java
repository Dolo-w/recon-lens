package io.github.dolow.reconlens.engine;

/** 异常信息整理：把最内层原因也带出来，否则分片失败信息只有一句"执行失败"。 */
public final class Errors {

    private static final int MAX_LENGTH = 900;

    private Errors() {
    }

    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(throwable.getClass().getSimpleName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage().trim());
        }
        Throwable root = rootCause(throwable);
        if (root != throwable && root.getMessage() != null) {
            sb.append(" | 根因 ").append(root.getClass().getSimpleName()).append(": ").append(root.getMessage().trim());
        }
        String text = sb.toString().replaceAll("\\s+", " ");
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
