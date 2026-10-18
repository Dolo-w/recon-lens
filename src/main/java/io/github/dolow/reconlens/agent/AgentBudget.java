package io.github.dolow.reconlens.agent;

import java.time.Duration;

/**
 * 一次 Agent 会话的预算护栏（设计 §6 / {@code AgentBudget}）。
 *
 * <p><b>为什么要有它</b>：自纠正循环的终止条件不能只有"模型说好了"。
 * 模型完全可以在"违规 → 改一版 → 又违规"里无限往返，而每一次往返都是真金白银；
 * 更糟的是它会一直占用一个 HTTP 线程，让调用方等到 MCP 客户端超时才发现什么都没拿到。
 *
 * <p><b>三维度都要有</b>，因为它们防的不是同一件事：
 * <ul>
 *   <li>轮数：防"改不完"（语义问题，模型自己不收敛）；</li>
 *   <li>token：防"一次塞太多"（上下文问题，schema + few-shot + 违规列表会越滚越大）；</li>
 *   <li>墙钟：防"卡住"（网络问题，读超时 30s × 3 轮就已经 90s）。</li>
 * </ul>
 *
 * <p><b>token 是估算值，不是账单</b>：{@code LlmChatClient.complete} 按评审 R19 只返回文本、
 * 不回传 usage，所以这里按"字符数即 token 上界"折算 —— 中文接近真实值，英文会被高估约 4 倍。
 * 方向是对的：护栏宁可早一点熔断，也不要因为低估而在第 3 轮才发现已经烧了 10 万 token。
 * 需要精确计费的话得让客户端回传 usage，那是另一件事（且会污染"只做一件事"的接口）。
 *
 * <p><b>超限是异常，不是返回 null</b>：预算耗尽必须让整条链路停下来并落库为 BLOCKED，
 * 而不是返回一个"看起来像草稿"的空对象 —— 后者会被下游当成一次正常的起草结果。
 */
public final class AgentBudget {

    /** 预算耗尽。调用方必须把它落成 {@code recon_agent_run.status = BLOCKED}。 */
    public static class ExceededException extends RuntimeException {

        /** 哪个维度耗尽的。用枚举而不是中文文案：调用方要按维度分流（轮数用尽 = 转人工，token/墙钟 = 中止）。 */
        public enum Dimension {
            ROUNDS("轮数"), TOKENS("token"), WALL_CLOCK("墙钟");

            private final String label;

            Dimension(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }

        private final Dimension dimension;

        public ExceededException(Dimension dimension, String detail) {
            super("Agent 预算耗尽（" + dimension.label() + "）：" + detail);
            this.dimension = dimension;
        }

        public Dimension dimension() {
            return dimension;
        }
    }

    private final int maxRounds;
    private final int maxTokens;
    private final long maxMillis;
    private final long startNanos;

    private int rounds;
    private long tokens;

    public AgentBudget(int maxRounds, int maxTokens, int maxWallClockSeconds) {
        this(maxRounds, maxTokens, maxWallClockSeconds * 1000L, System.nanoTime());
    }

    /**
     * 包可见：{@code startNanos} 可注入，让"已经超时"这件事能在一行里构造出来。
     * 不然测墙钟只能 {@code Thread.sleep}，既慢又只能测到"至少超了"。
     */
    AgentBudget(int maxRounds, int maxTokens, long maxMillis, long startNanos) {
        if (maxRounds < 1 || maxTokens < 1 || maxMillis < 1) {
            throw new IllegalArgumentException("预算三维度都必须 ≥ 1");
        }
        this.maxRounds = maxRounds;
        this.maxTokens = maxTokens;
        this.maxMillis = maxMillis;
        this.startNanos = startNanos;
    }

    public static AgentBudget of(AgentProperties properties) {
        return new AgentBudget(properties.getMaxLlmRounds(), properties.getMaxLlmTokensPerRun(),
                properties.getMaxWallClockSeconds());
    }

    /**
     * 开始新一轮之前调用：轮数与墙钟都还够不够。
     *
     * <p>token 不在这里查：它是<b>花出去才知道</b>的，只能事后拦（见 {@link #spendLlm}）。
     * 所以"第 4 轮不该开始"由这里拦，"这一轮太贵"由花钱处拦 —— 两个检查点不能合并。
     */
    public void requireAnotherRound() {
        if (rounds >= maxRounds) {
            throw new ExceededException(ExceededException.Dimension.ROUNDS,
                    "已用 " + rounds + " 轮，上限 " + maxRounds);
        }
        requireTime();
    }

    /**
     * 记一次 LLM 往返的开销。
     *
     * <p>按"提示 + 回复"的总字符数计：提示是我们要付的输入费，回复是输出费，两边都算。
     */
    public void spendLlm(String prompt, String reply) {
        rounds++;
        tokens += estimateTokens(prompt) + estimateTokens(reply);
        if (tokens > maxTokens) {
            throw new ExceededException(ExceededException.Dimension.TOKENS,
                    "已用约 " + tokens + "，上限 " + maxTokens + "（估算口径：字符数即 token 上界）");
        }
        requireTime();
    }

    /** 任意耗时动作前后都可以查一次（例如闸门 2 的试算）。 */
    public void requireTime() {
        long elapsed = elapsedMillis();
        if (elapsed > maxMillis) {
            throw new ExceededException(ExceededException.Dimension.WALL_CLOCK,
                    "已用 " + elapsed + " ms，上限 " + maxMillis + " ms");
        }
    }

    public int rounds() {
        return rounds;
    }

    public long tokens() {
        return tokens;
    }

    public int maxRounds() {
        return maxRounds;
    }

    public long elapsedMillis() {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /** 字符数即 token 上界（方向保守，见类注释）。 */
    static int estimateTokens(String text) {
        return text == null ? 0 : text.length();
    }
}
