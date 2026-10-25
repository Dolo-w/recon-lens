package io.github.dolow.reconlens.chat;

import java.time.Instant;

/**
 * 一段对话（{@code recon_chat_session}）的快照。
 *
 * <p>{@code stateJson} 是给程序用的结构化槽位（ruleCode / jobId / shard / 时间窗），
 * {@code summary} 是给模型读的一段短散文。两者<b>刻意分开</b>：压成一段自然语言，指代解析就要
 * 从散文里猜 jobId，而猜错一次就是对着错的任务解释差异（ADR-0001 D7）。
 *
 * <p>{@code previousOperator} 只在交接之后有值：读权限要认它，否则"HANDOVER 对所有人可见"
 * 会让一次交接把私有会话变成公开记录。
 *
 * <p>{@code stateRev} 是槽位的版本号，用来做比较交换（CAS）：写之前带上读到的版本，
 * 条件没命中说明有人在你之前改过槽位，这一轮就重读一次再写。没有它，两个人同时问一句，
 * 后写的那个会把前一个的 {@code jobId} 直接盖掉——表现是"我刚跑完，接着问为什么，
 * 它却让我给任务号"。
 * <p>{@code summaryUptoSeq} 是摘要的水位线：{@code seq <= summaryUptoSeq} 的轮次已经压进
 * {@code summary} 了。没有它就做不到增量压缩，每次都要从第一轮重压一遍——而压缩存在的理由
 * 恰恰是"别再读全部历史"。
 */
public record ChatSession(long id, String operator, String previousOperator, Visibility visibility,
                          String title, String stateJson, int stateRev, String summary, int summaryUptoSeq,
                          int turnCount, String status, Instant createdAt, Instant lastAt) {

    public boolean ownedBy(String candidate) {
        return operator != null && operator.equals(candidate);
    }

    /** 是否曾经属于某人（交接前的 owner）。 */
    public boolean wasOwnedBy(String candidate) {
        return previousOperator != null && previousOperator.equals(candidate);
    }
}
