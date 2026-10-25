package io.github.dolow.reconlens.chat;

import java.time.Instant;
import java.util.List;

/**
 * 跨会话检索命中的一条轮次。
 *
 * <p>为什么带 {@code owner}：默认只搜自己，但 {@code scope=team} 时要能回答"这是谁说的"。
 * 一条记忆如果没有出处，就没法判断该不该信——这和案例记忆带操作人是同一个道理。
 *
 * <p>为什么带 {@code visibility}：同一条命中，来自 SHARED 会话和来自交接过的会话，
 * 权限含义不一样；接口把事实给出来，让调用方自己判断，而不是在服务端猜"他应不应该看到"。
 */
public record SessionHit(
        long sessionId,
        String owner,
        String visibility,
        String title,
        int seq,
        String role,
        String content,
        Instant createdAt,
        int score,
        List<String> matched) {

    /** 截断到一段话，避免把 20000 字的长回答整段搬进检索结果。 */
    public String snippet(int max) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
