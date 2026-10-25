package io.github.dolow.reconlens.chat;

/**
 * 谁能读这条会话。
 *
 * <p>三档（ADR-0001 D8）：
 * <ul>
 *   <li>{@link #PRIVATE}：只有创建人。默认值。</li>
 *   <li>{@link #SHARED}：团队可见，但只有创建人能继续问。</li>
 *   <li>{@link #HANDOVER}：交接之后的样子——<b>新 owner 与原 owner 可读</b>，只有新 owner 可写。</li>
 * </ul>
 *
 * <p><b>HANDOVER 不等于"公开"</b>：曾经的实现把这一档写成 {@code visibility <> 'PRIVATE'}
 * 就算可读，结果是任何交接过的会话对全公司可见。所以现在读权限由
 * {@code operator} + {@code previous_operator} + {@code SHARED} 三者共同决定，
 * HANDOVER 只表示"写权已经转手"，不放大读权限。
 */
public enum Visibility {

    PRIVATE,
    SHARED,
    HANDOVER;

    public static Visibility of(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRIVATE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知可见性：" + raw + "（可选 PRIVATE / SHARED / HANDOVER）");
        }
    }

    /** 是否对团队其他人可见（只读）。 */
    public boolean readableByOthers() {
        return this == SHARED;
    }
}
