package io.github.dolow.reconlens.recall;

import java.util.Optional;

/**
 * 向量化的出口：<b>只负责"把一段话变成一串数"，不负责存、不负责比</b>。
 *
 * <p>为什么做成接口而不是直接调 HTTP：不装模型的部署必须能跑（ADR-0001 N4 的降级链第一环就是
 * "向量不可用"，不是"向量算错"）。这里的默认实现没配地址时 {@link #configured()} 就是 false，
 * 整条链会自动降级到全文/关键词，而不是抛异常。
 *
 * <p>为什么 {@code embed} 返回 {@link Optional}：模型服务挂了、超时了、限流了，都不该让
 * "查一条口径"变成 500。返回空 = 这一层不可用，上层继续往下走。
 */
public interface EmbeddingClient {

    Optional<float[]> embed(String text);

    /** 配了没有。没配时调用方不该再去试。 */
    boolean configured();

    /** 维度。没配时返回 0。 */
    int dim();

    /** 写进日志、写进接口响应的一句话，用来解释"这次为什么用/没用向量"。 */
    String describe();
}
