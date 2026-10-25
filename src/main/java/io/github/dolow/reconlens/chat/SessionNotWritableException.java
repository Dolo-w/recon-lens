package io.github.dolow.reconlens.chat;

/**
 * 能看，但不能往里写（别人 {@link Visibility#SHARED} 给你的会话）。
 *
 * <p>这一档可以明确回 403，因为它没有泄露任何东西：调用方本来就能读到这条会话，
 * 知道"它存在、它属于谁"是共享语义的一部分。与 {@link SessionNotFoundException} 的区别是
 * <b>可见性已成立，缺的是写权限</b>。
 */
public class SessionNotWritableException extends RuntimeException {

    public SessionNotWritableException(long sessionId, String operator, String owner) {
        super("会话 " + sessionId + " 属于 " + owner + "，你（" + operator + "）只能看不能继续提问");
    }
}
