package io.github.dolow.reconlens.chat;

import java.util.Locale;

/**
 * 对话的两种装法：老路径（判意图 → 固定分支）与自由循环（给模型事实和工具，让它自己走）。
 *
 * <p>为什么要一个开关而不是直接换掉：自由循环的失败模式与老路径不同（多跳、成本、乱调工具），
 * 换过去之后老路径必须还能一键回来对照。默认 {@link #LEGACY}，改动只在显式打开时生效。
 */
public enum ChatMode {

    /** 词表 + 意图枚举 + 固定分支：P2 之前的样子。 */
    LEGACY,

    /** 只给模型事实、记忆、工具清单和项目手册，由它决定下一步（可多跳，写动作仍要回执）。 */
    LOOP;

    public static ChatMode of(String raw) {
        if (raw == null) {
            return LEGACY;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "loop", "free", "agent" -> LOOP;
            default -> LEGACY;
        };
    }

    public boolean loop() {
        return this == LOOP;
    }
}
