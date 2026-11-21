package io.github.dolow.reconlens.pricing;

/** 解析失败（未命中或歧义）异常，message 已含可定位上下文（可用窗列表 / 撞车条目）。 */
public class PriceNotResolvedException extends RuntimeException {

    public PriceNotResolvedException(String message) {
        super(message);
    }
}
