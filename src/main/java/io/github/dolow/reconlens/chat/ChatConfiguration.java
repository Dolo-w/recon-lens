package io.github.dolow.reconlens.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.ChatPlanner;
import io.github.dolow.reconlens.chat.plan.CompositePlanner;
import io.github.dolow.reconlens.chat.plan.KeywordPlanner;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import io.github.dolow.reconlens.chat.plan.LlmPlanner;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.recall.RecallAugmenter;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 会话记忆的装配。
 *
 * <p><b>为什么跟着 {@code recon.agent.enabled} 走</b>：会话的写入方是 ChatRuntime，它属于 Agent 子系统；
 * 关掉 Agent 时装配一套没人写、也没人读的表访问层，只会让"关掉开关 = 回到引入之前"这句话失真。
 * 反过来，将来如果出现一个不依赖 Agent 的会话消费方（比如报表页），再把这个条件放宽就行。</p>
 *
 * <p><b>为什么 {@link ChatSessionService} 要拿 {@link AgentRunRepository}</b>：交接的审计复用
 * {@code recon_agent_run / recon_agent_step}（trigger_type=SESSION_HANDOVER），不新开表。
 * 那个仓库在 {@code store} 包，<b>不是</b> {@code agent} 包 —— {@code AgentBoundaryTest} 禁止
 * agent 包以外的代码依赖 agent 包，这条约束会拦住任何"顺手 import 一下 orchestrator"的写法。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class ChatConfiguration {

    @Bean
    public ChatSessionRepository chatSessionRepository(JdbcTemplate jdbcTemplate) {
        return new ChatSessionRepository(jdbcTemplate);
    }

    /** 会话草稿账本：这个会话起草过哪些规则版本（事实层，只增）。 */
    @Bean
    public io.github.dolow.reconlens.store.ChatDraftRepository chatDraftRepository(JdbcTemplate jdbcTemplate) {
        return new io.github.dolow.reconlens.store.ChatDraftRepository(jdbcTemplate);
    }

    /** 会话结论板：同一主题后写的覆盖先写的（"早就说过、后来推翻"只保留后者）。 */
    @Bean
    public ChatClaims chatClaims(io.github.dolow.reconlens.store.ChatClaimRepository chatClaimRepository,
                                 @Value("${recon.chat.shared-claims.enabled:false}") boolean sharedClaimsEnabled) {
        return new ChatClaims(chatClaimRepository, sharedClaimsEnabled);
    }
    @Bean
    public ChatSessionService chatSessionService(ChatSessionRepository chatSessionRepository,
                                                 AgentRunRepository agentRunRepository) {
        return new ChatSessionService(chatSessionRepository, agentRunRepository);
    }

    /**
     * 摘要服务。
     *
     * <p>窗口大小取 {@code recon.chat.window-tokens}，缺省 8000：这个值只影响"什么时候开始压"，
     * 取小一点只会让压缩更早发生，不会丢数据。真正的窗口大小取决于部署时接的模型，
     * 猜大了的后果是压得太晚、上下文被挤掉——那才是会丢信息的失败方式。
     */
    @Bean
    public ChatSummaryService chatSummaryService(ChatSessionRepository chatSessionRepository,
            @Value("${recon.chat.window-tokens:8000}") int windowTokens,
            SummaryEnricher summaryEnricher) {
        return new ChatSummaryService(chatSessionRepository, windowTokens, summaryEnricher);
    }

    /**
     * 摘要的归纳层：只写"结论/未决"两段，事实与坐标仍由截断版提供。
     * 关掉它或没有 key 时，摘要回到纯截断——行为与 P1 完全一致。
     */
    @Bean
    public SummaryEnricher summaryEnricher(LlmChatClient chatLlmClient,
            @Value("${recon.chat.summary.llm:true}") boolean enabled) {
        return new SummaryEnricher(chatLlmClient, enabled);
    }

    /**
     * 跨会话检索。刻意不挂 {@code recon.agent.enabled} 之外的开关：
     * 它是会话功能的延伸（"我以前是不是问过这个"），关掉会话就没意义了。
     */
    @Bean
    public SessionRecallService sessionRecallService(ChatSessionRepository chatSessionRepository) {
        return new SessionRecallService(chatSessionRepository);
    }

    /**
     * 意图规划器：词表优先，模型兜底，失败熔断。
     *
     * <p><b>key 从哪来</b>：{@code recon.chat.llm.api-key}，默认读环境变量
     * {@code DEEPSEEK_API_KEY}（见 application.yml）。为空时整条模型链路不装配成"可用"，
     * 但 bean 照样存在——装配期就分叉的话，无 key 环境里连 {@code /api/chat/planner} 都问不到状态。
     */
    @Bean
    public LlmChatClient chatLlmClient(@Value("${recon.chat.llm.base-url:https://api.deepseek.com}") String baseUrl,
                                       @Value("${recon.chat.llm.api-key:}") String apiKey,
                                       @Value("${recon.chat.llm.model:deepseek-chat}") String model,
                                       @Value("${recon.chat.llm.timeout-ms:8000}") int timeoutMs,
                                       ObjectMapper objectMapper) {
        return new LlmChatClient(baseUrl, apiKey, model, timeoutMs, objectMapper);
    }

    @Bean
    public ChatPlanner chatPlanner(LlmChatClient chatLlmClient, ObjectMapper objectMapper) {
        return new CompositePlanner(new KeywordPlanner(new ChatIntentRouter()),
                new LlmPlanner(chatLlmClient, objectMapper));
    }

    /**
     * 人话总结。默认开，但它自己会在没配 key 时变成空操作（{@link ChatNarrator#active()}）。
     *
     * <p>单独一个开关（{@code recon.chat.llm.summarize}）的理由：某些部署要的是"逐字回放的证据"，
     * 多一句模型总结反而干扰对账口径的复核；关掉它不影响意图理解。
     */
    @Bean
    public ChatNarrator chatNarrator(LlmChatClient chatLlmClient,
            @Value("${recon.chat.llm.summarize:true}") boolean summarize) {
        return new ChatNarrator(chatLlmClient, summarize);
    }

    /**
     * 给新规则起名。没有 key 时它返回空，起草退回 {@code AUTO_} 编码——
     * 名字是给人认的，不是起草的前置条件。
     */
    @Bean
    public RuleNamer ruleNamer(LlmChatClient chatLlmClient, ObjectMapper objectMapper) {
        return new RuleNamer(chatLlmClient, objectMapper);
    }

    /**
     * 起草参数补齐。没有 key 时返回空，起草退回 {@link DraftIntake} 的确定性解析——
     * 裸列名（「order_no」）照样能接住，只是听不懂「用状态和订单对」这种省略说法。
     */
    @Bean
    public DraftSlotFiller draftSlotFiller(LlmChatClient chatLlmClient, ObjectMapper objectMapper) {
        return new DraftSlotFiller(chatLlmClient, objectMapper);
    }

    @Bean
    public ChatRuntime chatRuntime(ChatSessionService chatSessionService, ChatSummaryService chatSummaryService,
                                   ToolRegistry toolRegistry, RecallAugmenter recallAugmenter,
                                   ReconRepository reconRepository, ReconEngine reconEngine,
                                   RuleCatalog ruleCatalog, ObjectMapper objectMapper, ChatPlanner chatPlanner,
                                   ChatNarrator chatNarrator, RuleNamer ruleNamer,
                                   io.github.dolow.reconlens.store.ChatDraftRepository chatDraftRepository,
                                   DraftSlotFiller draftSlotFiller, ChatClaims chatClaims) {
        return new ChatRuntime(chatSessionService, chatSummaryService, toolRegistry, recallAugmenter,
                reconRepository, reconEngine, ruleCatalog, objectMapper, chatPlanner, chatNarrator,
                chatDraftRepository, ruleNamer, draftSlotFiller, chatClaims);
    }

    /** 表单入口：与会话共用 {@link ChatRuntime#submit}，不另起一套动作实现。 */
    @Bean
    public ChatFormService chatFormService(ChatSessionService chatSessionService, ChatRuntime chatRuntime) {
        return new ChatFormService(chatSessionService, chatRuntime);
    }

    /**
     * SSE 用的小线程池。
     *
     * <p>为什么必须异步：{@code SseEmitter} 得先返回给容器，事件才能一条条写出去；
     * 在请求线程里同步跑完再返回，浏览器只会看到一次性的整包响应，分片就没意义了。
     *
     * <p>为什么池子这么小（2/4/16）：一次"跑对账"本来就要几十秒，并发再多也只是把
     * 数据库连接池（默认 24）和结果集游标摊薄；队列满时直接拒绝，比悄悄排队到超时更好排障。
     */
    @Bean(name = "chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("chat-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
