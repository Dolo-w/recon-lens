package io.github.dolow.reconlens.chat;

import java.time.Instant;

/**
 * 一轮对话（{@code recon_chat_turn}）。只增不改。
 *
 * <p>为什么原文永久保留而不是"压缩后就删"：压缩产物是模型写的散文，而复盘要看的是原话。
 * 对账出的差异最后要有人签字，签字的人问"当时为什么这么问"，答案必须是原文（ADR-0001 N6）。
 */
public record ChatTurn(long id, long sessionId, int seq, String role, String content,
                       String toolCalls, Integer tokens, Instant createdAt) {
}
