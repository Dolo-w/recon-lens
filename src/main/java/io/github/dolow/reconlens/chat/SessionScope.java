package io.github.dolow.reconlens.chat;

/**
 * 会话读取的唯一收窄入口（ADR-0001 D8 / N8）。
 *
 * <p><b>为什么要有这个类</b>：隔离最容易做错的地方不是"少加一个 WHERE"，而是<b>过滤发生在查询之后</b>——
 * 先 {@code SELECT *} 再在内存里判断"这条是不是我的"。后者在单条读取上看起来等价，
 * 一旦出现跨会话语义检索（P3），检索结果里就会混进别人的会话片段，而调用方根本看不出哪条不属于自己。
 * 所以：<b>过滤必须前置</b>，而且只在这一个地方拼条件。
 *
 * <p><b>为什么不让 {@code ChatSessionRepository} 提供无 scope 的 {@code find(long)}</b>：
 * 那样的方法一定会在某次"临时查一下"里被用上，然后这次收敛就白做了。宁可让调用方每次都显式构造
 * {@code SessionScope}——多打几个字，换一条结构上的保证。
 *
 * <p><b>权限口径</b>：读 = 自己的 ∪ 别人共享/交接的；写 = 只有 owner。两个人同时往一条会话里发消息，
 * 槽位会互相覆盖。
 *
 * <p>{@code tenant} 刻意不存在：现在唯一的身份源是一个 api-key，{@code X-Operator} 由调用方自己填、
 * 可伪造。这种情况下 {@code tenant_id} 永远等于 {@code default}，表里有了这一列，所有人就以为
 * 多租户已经支持了。将来真有认证，改这个类一处 + 一次 ALTER 即可。
 */
public record SessionScope(String operator) {

    private static final int MAX_OPERATOR_LENGTH = 64;

    public SessionScope {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("缺少操作人：会话按 operator 隔离，未署名不落库");
        }
        operator = operator.trim();
        if (operator.length() > MAX_OPERATOR_LENGTH) {
            throw new IllegalArgumentException("operator 过长（上限 " + MAX_OPERATOR_LENGTH + "）：" + operator.length());
        }
    }

    public static SessionScope of(String raw) {
        return new SessionScope(raw);
    }

    /**
     * 读取（列表 / 单条）条件的 SQL 片段，配合 {@link #readFilterParams()} 使用。
     *
     * <p>三个来源：<b>自己的</b>、<b>交接前属于自己</b>、<b>团队共享（SHARED）</b>。
     *
     * <p>为什么不是 {@code visibility <> 'PRIVATE'}：那样 HANDOVER 就等于"谁都能看"。
     * 一次交接会把一条私有会话变成全公司可读的记录——这比不做隔离更糟，因为它看起来仍然受控。
     * 交接只是<b>写权转手</b>，不该同时放大读权限。
     */
    public String readFilterSql() {
        return "(operator = ? OR previous_operator = ? OR visibility = 'SHARED')";
    }

    public Object[] readFilterParams() {
        return new Object[]{operator, operator};
    }

    public boolean canRead(ChatSession session) {
        return session.ownedBy(operator) || session.wasOwnedBy(operator) || session.visibility().readableByOthers();
    }

    public boolean canWrite(ChatSession session) {
        return session.ownedBy(operator);
    }
}
