package io.github.dolow.reconlens.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.LlmCall;
import io.github.dolow.reconlens.store.LlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI 兼容的 {@code /chat/completions} 实现（DeepSeek / 通义 / vLLM / Ollama 的 openai 兼容端点都能用）。
 *
 * <p>这段代码原样来自 {@code LlmDiffExplainer}：baseUrl 结尾斜杠归一、连接与读取超时、Bearer key、
 * 懒初始化 {@link RestClient}。抽取时<b>只搬了"发请求 + 取 content"</b>，
 * 把"失败要不要落回模板"留在了原处 —— 那是调用方的语义，不是客户端的能力。
 *
 * <p>温度固定 0.2：两个用途（写一句归因、起草一条规则）都需要稳定复现，
 * 而"更有创意"在这个系统里等于"更不可审计"。要调就改这一处，别散到调用方去。
 *
 * <p>它<b>不是 agent 包的 Bean</b>：归因链路在引入 Agent 之前就要用它，所以无条件装配。
 * 这不会破坏"关掉 Agent 等于回到引入之前"——它没有后台线程、没有调度、不读写任何表。
 *
 * <h2>生产级容错（清单 P0-2 / P0-4）</h2>
 *
 * <p>在只有"一次尝试"的实现里，DeepSeek 抖一下整条采集链就瘫（2026-11-22 实测过一次）。
 * 现在这里有四件事，每一件都对着一个真实故障模式：
 * <ol>
 *   <li><b>重试 + 指数退避 + 抖动</b>：网络抖动、429 限流、5xx 都是暂时的，隔一会儿就好；
 *       抖动能避免多实例在同一时刻一起重试形成新的尖峰。</li>
 *   <li><b>可重试 vs 不可重试</b>：401/400 是配置或请求错，同一 provider 内重试一万次也一样，
 *       直接跳到下一个 provider 更快暴露问题。</li>
 *   <li><b>备用 provider</b>：主 provider 整体不可用时换一家接管——这是"DeepSeek 挂了全链路瘫"
 *       的直接解药。备用没 base-url 就当没配，行为与旧版一致。</li>
 *   <li><b>每次尝试都记账</b>：成功/重试/失败/接管全落 {@code recon_llm_call} 并打指标。
 *       没有这份账，"这个月成本为什么涨了""失败率多少"都答不上来。</li>
 * </ol>
 *
 * <p><b>记账与打点都是旁路</b>：写库或写指标失败，绝不影响模型调用本身的结果——
 * 对账结论比计费账本重要。
 */
@Component
public class OpenAiCompatibleChatClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private static final double TEMPERATURE = 0.2;

    // outcome 取值，与 LlmCall 的说明保持一致
    static final String OK = "OK";
    static final String RETRIED = "RETRIED";
    static final String FALLBACK = "FALLBACK";
    static final String RETRY = "RETRY";
    static final String FAILED = "FAILED";
    static final String UNAVAILABLE = "UNAVAILABLE";

    private final ReconProperties properties;
    private final LlmCallRepository callRepository;
    private final LlmMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 最近一次请求的真实 token 用量。用 ThreadLocal 而不是字段：client 是单例 Bean，
     * 会话线程可能并发；{@code complete → lastTokens} 必须落在同一个线程里才有意义。
     */
    private static final ThreadLocal<Integer> LAST_TOKENS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> LAST_PROMPT = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> LAST_COMPLETION = ThreadLocal.withInitial(() -> 0);

    /** RestClient 按 (baseUrl, apiKey) 缓存：多 provider 场景下每家的连接池各自独立。 */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /** 测试与老代码入口：无账本、无指标，行为退化为"能发请求 + 能取 content"。 */
    public OpenAiCompatibleChatClient(ReconProperties properties) {
        this(properties, null, LlmMetrics.noop());
    }

    @Autowired
    public OpenAiCompatibleChatClient(ReconProperties properties,
                                      LlmCallRepository callRepository,
                                      LlmMetrics metrics) {
        this.properties = properties;
        this.callRepository = callRepository;
        this.metrics = metrics == null ? LlmMetrics.noop() : metrics;
    }

    @Override
    public boolean available() {
        return properties.getLlm().usable();
    }

    @Override
    public String name() {
        return "llm-openai-compatible";
    }

    @Override
    public Optional<String> complete(String system, String user) {
        return complete(LlmPurpose.OTHER, system, user);
    }

    /**
     * 带用途标注的完整调用：主 provider 重试 → 备用 provider 重试 → 放弃。
     *
     * <p>失败语义与接口契约一致：<b>不抛异常</b>，返回 {@link Optional#empty()}，
     * 由调用方决定降级到模板、词表还是转人工。
     */
    @Override
    public Optional<String> complete(String purpose, String system, String user) {
        LAST_TOKENS.set(0);
        LAST_PROMPT.set(0);
        LAST_COMPLETION.set(0);

        if (!available()) {
            record(purpose, "none", "-", UNAVAILABLE, 0, 0, 0, 0, "not-configured");
            return Optional.empty();
        }

        List<Provider> providers = providers();
        int maxAttempts = Math.max(1, properties.getLlm().getMaxAttempts());

        String lastError = null;
        String lastProvider = providers.isEmpty() ? "none" : providers.get(0).name();
        String lastModel = providers.isEmpty() ? "-" : providers.get(0).model();
        // 最后一次尝试是否已经记过终态（FAILED）。避免"致命错误记一条 + 末尾总结再记一条"的重复账。
        boolean terminalRecorded = false;

        for (int p = 0; p < providers.size(); p++) {
            Provider provider = providers.get(p);
            lastProvider = provider.name();
            lastModel = provider.model();
            boolean useFallback = p > 0;
            terminalRecorded = false;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                long started = System.currentTimeMillis();
                try {
                    Attempt result = callOnce(provider, system, user);
                    long latency = System.currentTimeMillis() - started;

                    LAST_TOKENS.set(result.total());
                    LAST_PROMPT.set(result.prompt());
                    LAST_COMPLETION.set(result.completion());

                    String outcome = useFallback ? FALLBACK : (attempt > 1 ? RETRIED : OK);
                    record(purpose, provider.name(), provider.model(), outcome, latency,
                            result.prompt(), result.completion(), attempt, null);
                    if (useFallback) {
                        log.warn("主 provider 不可用，已由备用 provider（{}）接管，本次调用成功", provider.name());
                    }
                    return Optional.of(result.content());
                } catch (RetryableFailure e) {
                    long latency = System.currentTimeMillis() - started;
                    lastError = e.kind;
                    terminalRecorded = false;
                    // 这次尝试失败但还会再试：单记 RETRY，不计入最终失败率，
                    // 否则重试机制会把"最终成功的调用"污染成失败
                    record(purpose, provider.name(), provider.model(), RETRY, latency, 0, 0, attempt, e.kind);
                    log.warn("调用大模型失败（{}，第 {}/{} 次，provider={}）: {}",
                            e.kind, attempt, maxAttempts, provider.name(), e.getMessage());
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                    }
                } catch (FatalFailure e) {
                    long latency = System.currentTimeMillis() - started;
                    lastError = e.kind;
                    record(purpose, provider.name(), provider.model(), FAILED, latency, 0, 0, attempt, e.kind);
                    terminalRecorded = true;
                    log.warn("调用大模型遇到不可重试错误（{}，provider={}）：{}；"
                                    + "本 provider 内不再重试，直接尝试下一个 provider（若已配置）",
                            e.kind, provider.name(), e.getMessage());
                    break; // 跳出 attempt 循环，进下一个 provider
                }
            }
        }

        // 所有 provider、所有尝试都失败，且最后一次尝试没有留下终态记录：补一条总结
        if (!terminalRecorded) {
            record(purpose, lastProvider, lastModel, FAILED, 0, 0, 0, maxAttempts, lastError);
        }
        log.warn("调用大模型最终失败（所有 provider 尝试穷尽，purpose={}）：本次按不可用处理，由调用方决定降级方式", purpose);
        return Optional.empty();
    }

    @Override
    public int lastTokens() {
        return LAST_TOKENS.get();
    }

    /** 上一次调用的输入 token（成本归因用）；拿不到时为 0。 */
    public int lastPromptTokens() {
        return LAST_PROMPT.get();
    }

    /** 上一次调用的输出 token（成本归因用）；拿不到时为 0。 */
    public int lastCompletionTokens() {
        return LAST_COMPLETION.get();
    }

    // ------------------------------------------------------------------
    // 一次真实请求
    // ------------------------------------------------------------------

    private Attempt callOnce(Provider provider, String system, String user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", provider.model());
        payload.put("temperature", TEMPERATURE);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system == null ? "" : system),
                Map.of("role", "user", "content", user == null ? "" : user)));

        String response;
        try {
            response = clientFor(provider).post()
                    .uri("/chat/completions")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String kind = "http-" + status;
            // 429 = 限流（等一会儿就好）；5xx = 服务端暂时性故障。其余 4xx 是配置/请求错。
            if (status == 429 || status >= 500) {
                throw new RetryableFailure(kind, e.getMessage(), e);
            }
            throw new FatalFailure(kind, e.getMessage(), e);
        } catch (ResourceAccessException e) {
            // 连不上 / 读到一半断开 / 超时 —— 最典型的"重试就能好"
            throw new RetryableFailure("network", e.getMessage(), e);
        } catch (Exception e) {
            // 序列化失败、其他意外：按可重试处理（通常是一次性的），但记录原始类型
            throw new RetryableFailure("other", e.toString(), e);
        }

        try {
            JsonNode root = mapper.readTree(response == null ? "{}" : response);
            int[] usage = usageParts(root);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content == null || content.isBlank()) {
                // 拿到了 200 但没有正文：这不算网络故障，重试大概率也一样，按致命处理
                throw new FatalFailure("empty-content", "响应里没有 content", null);
            }
            return new Attempt(content, usage[0], usage[1], usage[2]);
        } catch (FatalFailure e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableFailure("bad-response", e.toString(), e);
        }
    }

    /**
     * 从响应里取真实用量，返回 {@code [prompt, completion, total]}。
     *
     * <p>{@code total_tokens} 优先；只有分项时相加；都没有返回 0/0/0。
     * 返回 0 不代表"没花钱"，只代表这次拿不到，调用方会退回字符数估算。
     */
    static int[] usageParts(JsonNode root) {
        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(0);
        if (total <= 0) {
            total = prompt + completion;
        }
        return new int[]{prompt, completion, total};
    }

    /**
     * 从响应里取真实用量：{@code usage.total_tokens} 优先；只有分项时相加；都没有返回 0。
     *
     * <p>保留这个方法是因为它是既有的公开契约（{@code LlmUsageParsingTest} 直接断言它），
     * 内部委托给 {@link #usageParts}，避免两套口径漂移。
     */
    static int usageOf(JsonNode root) {
        return usageParts(root)[2];
    }

    // ------------------------------------------------------------------
    // provider 与账本
    // ------------------------------------------------------------------

    /** 主 provider 一定在列；备用 provider 仅在配了 base-url 时追加。 */
    private List<Provider> providers() {
        ReconProperties.Llm llm = properties.getLlm();
        List<Provider> list = new ArrayList<>(2);
        list.add(new Provider("primary", llm.getBaseUrl(), llm.resolveApiKey(), llm.getModel(),
                llm.getPriceInputPerMtok(), llm.getPriceOutputPerMtok()));

        ReconProperties.Llm.Fallback fb = llm.getFallback();
        if (fb != null && fb.configured()) {
            list.add(new Provider("fallback", fb.getBaseUrl(), fb.resolveApiKey(llm.resolveApiKey()),
                    fb.getModel() == null || fb.getModel().isBlank() ? llm.getModel() : fb.getModel(),
                    fb.getPriceInputPerMtok() != null ? fb.getPriceInputPerMtok() : llm.getPriceInputPerMtok(),
                    fb.getPriceOutputPerMtok() != null ? fb.getPriceOutputPerMtok() : llm.getPriceOutputPerMtok()));
        }
        return list;
    }

    /** 指数退避 + ±25% 抖动；避免多实例在同一毫秒一起重试形成二次尖峰。 */
    private void sleepBackoff(int attempt) {
        long base = properties.getLlm().getRetryBaseDelayMillis();
        if (base <= 0) {
            return;
        }
        long exp = base * (1L << Math.min(attempt - 1, 6)); // 1x,2x,4x...封顶 64x，防止溢出
        long jitter = (long) (exp * 0.25);
        long delay = exp + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        try {
            Thread.sleep(Math.max(0, delay));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记账 + 打指标。两条都是旁路，任何异常都吞掉。 */
    private void record(String purpose, String provider, String model, String outcome,
                        long latencyMs, int promptTokens, int completionTokens,
                        int attempt, String errorKind) {
        long cost = LlmCall.costMicros(promptTokens, completionTokens,
                priceOf(provider, true), priceOf(provider, false));
        if (callRepository != null) {
            try {
                callRepository.insertQuietly(new LlmCall(null, purpose, provider, model,
                        promptTokens, completionTokens, promptTokens + completionTokens,
                        latencyMs, attempt, outcome, errorKind, cost,
                        priceOf(provider, true), priceOf(provider, false), null));
            } catch (RuntimeException ignored) {
                // 旁路账，静默
            }
        }
        metrics.record(purpose, model, provider, outcome, latencyMs, promptTokens, completionTokens);
    }

    private double priceOf(String providerName, boolean input) {
        ReconProperties.Llm llm = properties.getLlm();
        if ("fallback".equals(providerName)) {
            ReconProperties.Llm.Fallback fb = llm.getFallback();
            if (fb != null) {
                Double v = input ? fb.getPriceInputPerMtok() : fb.getPriceOutputPerMtok();
                if (v != null) {
                    return v;
                }
            }
        }
        return input ? llm.getPriceInputPerMtok() : llm.getPriceOutputPerMtok();
    }

    private RestClient clientFor(Provider provider) {
        String key = provider.baseUrl() + "|" + provider.apiKey();
        return clients.computeIfAbsent(key, k -> {
            int timeoutMillis = (int) (properties.getLlm().getTimeoutSeconds() * 1000L);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeoutMillis);
            factory.setReadTimeout(timeoutMillis);

            String baseUrl = provider.baseUrl().endsWith("/")
                    ? provider.baseUrl().substring(0, provider.baseUrl().length() - 1)
                    : provider.baseUrl();
            return RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        });
    }

    /** 一家 provider 的静态配置快照。 */
    private record Provider(String name, String baseUrl, String apiKey, String model,
                            double priceIn, double priceOut) {
    }

    /** 一次成功往返的结果。 */
    private record Attempt(String content, int prompt, int completion, int total) {
    }

    /** 重试可能有救的失败。 */
    private static final class RetryableFailure extends RuntimeException {
        final String kind;

        RetryableFailure(String kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** 重试没救的失败（配置错、请求错、空正文）。 */
    private static final class FatalFailure extends RuntimeException {
        final String kind;

        FatalFailure(String kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }
}
