package io.github.dolow.reconlens.llm;

import java.util.Optional;

/**
 * 通用 chat 客户端：<b>只做一件事 —— 把 (system, user) 换成一段文本</b>（设计 §8.2 / 评审 R19）。
 *
 * <p>接口刻意不返回 {@code Optional<Choice>}、不暴露 HTTP 细节，主契约只有"文本进、文本出"。
 * token 统计是<b>旁路</b>（{@link #lastTokens}）：它不是主契约，理由是它有两个差异极大的调用方：
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

    /**
     * 带用途标注的一次对话往返。
     *
     * <p><b>为什么要加这个重载</b>：成本归因的整个价值在于"按功能算账"，而 {@code complete(system,user)}
     * 里没有任何信息能告诉账本"这次是路由还是归因"。加参数而不是猜，是最诚实的做法。
     *
     * <p>默认实现直接委托给两参版本（标签记 {@code other}）——这样既有的实现与调用点不用一次性全改，
     * 而新调用点只要传 {@link LlmPurpose} 就能拿到细粒度账。
     *
     * @param purpose 用途标签，取自 {@link LlmPurpose}（必须是常量，不能是用户输入）
     */
    default Optional<String> complete(String purpose, String system, String user) {
        return complete(system, user);
    }

    /**
     * 最近一次 {@link #complete} 的真实 token 用量（{@code usage.total_tokens}）；拿不到时返回 0。
     *
     * <p>为什么不放进返回值：返回 {@code Optional<String>} 是接口的稳定契约，多包一层 record 会让
     * 所有调用方都要改签名；而 token 是"顺便记一笔"的账，取不到就退化成估算（调用方本来就有
     * 字符数估算兜底）。实现必须是线程安全的：调用方是同一个线程内 {@code complete → lastTokens}。
     */
    default int lastTokens() {
        return 0;
    }

    /** 用于日志与报告：当前接入的是哪套实现。 */
    default String name() {
        return "llm";
    }
}
