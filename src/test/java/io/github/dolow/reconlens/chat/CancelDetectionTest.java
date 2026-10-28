package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 确认/取消判定的回归测试。
 *
 * <p>线上发现：用户看到回执后反问「不是三个维度吗」，系统把它当成"取消"，
 * 回了一句"好，这次不做"，并且把待确认的动作静默丢掉。
 * 根因是取消词表在整句里找 `不`，而中文的反问句几乎都带 `不`。
 */
class CancelDetectionTest {

    @Test
    void 反问句不是取消() {
        assertThat(ChatRuntime.isCancel("不是三个维度吗")).isFalse();
        assertThat(ChatRuntime.isCancel("刚刚我说的不是三个维度吗")).isFalse();
        assertThat(ChatRuntime.isCancel("这个不是已经批过了吗？")).isFalse();
        assertThat(ChatRuntime.isCancel("为什么不是按客户号对齐呢")).isFalse();
    }

    @Test
    void 干脆的否定仍然是取消() {
        assertThat(ChatRuntime.isCancel("取消")).isTrue();
        assertThat(ChatRuntime.isCancel("算了，不做了")).isTrue();
        assertThat(ChatRuntime.isCancel("不用了")).isTrue();
        assertThat(ChatRuntime.isCancel("不好")).isTrue();
        assertThat(ChatRuntime.isCancel("先别跑")).isTrue();
    }

    @Test
    void 长句与换话题都不算取消() {
        // 超过 12 个字就不是"一句干脆的话"，交给正常路由（可能是在改主意）
        assertThat(ChatRuntime.isCancel("等等先看一下差异再说这个")).isFalse();
        assertThat(ChatRuntime.isCancel("不要这样，改成按订单号对齐")).isFalse();
    }
}
