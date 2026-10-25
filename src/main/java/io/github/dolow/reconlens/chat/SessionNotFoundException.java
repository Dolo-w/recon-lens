package io.github.dolow.reconlens.chat;

/**
 * 会话不存在，<b>或者存在但不属于你</b>（ADR-0001 N8）。
 *
 * <p>两种情况合并成同一个异常、同一个 404，是刻意的：如果"不属于你"返回 403、"不存在"返回 404，
 * 拿一串自增 id 扫一遍就能问出"3 号到 900 号之间哪些会话存在"——<b>存在性本身就是信息</b>。
 * 对账会话的标题里常常直接写着客户名和账期。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(long sessionId) {
        super("会话不存在或不属于当前操作人：" + sessionId);
    }
}
