package io.github.dolow.reconlens.llm;

import java.util.Optional;

/**
 * 通用 chat 客户端：<b>只做一件事 —— 把 (system, user) 换成一段文本</b>（设计 §8.2 / 评审 R19）。
 *
 * <p>接口刻意不返回 {@code Optional<Choice>}、不带 token 统计、不暴露 HTTP 细节。原因是它有两个
 * 差异极大的调用方：
 * <ul>
 *   <li>{@code explain.LlmDiffExplainer}：失败要静默落回规则模板，多一句报错都会污染对账结论；</li>
 *   <li>{@code agent.RuleDrafter}：失败要变成"转人工"，<b>绝不能</b>变成一条自动批准的规则。</li>
 * </ul>
 * 两者对"失败"的处理完全相反，所以判定留给调用方，客户端只回答"拿到了吗"。
 *
 * <p><b>{@link #complete} 不抛异常</b>：网络不通、模型 500、返回体不合约定都是 {@link Optional#empty()}。
 * 这不是"吞异常"，而是这个接口的语义 —— 大模型是增强项，任何一次失败都只该降低能力，不该让调用方崩。
 */
public interface LlmChatClient {

    /** 当前配置是否真的能用（enabled + key + baseUrl）。判定放在这里，但<b>决策</b>留在调用方。 */
    boolean available();

    /**
     * 一次对话往返。
     *
     * @return 模型返回的正文；不可用、请求失败、返回体里没有 content、content 全空白 → {@link Optional#empty()}
     */
    Optional<String> complete(String system, String user);

    /** 用于日志与报告：当前接入的是哪套实现。 */
    default String name() {
        return "llm";
    }
}
