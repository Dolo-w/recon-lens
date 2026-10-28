package io.github.dolow.reconlens.config;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动期的安全自检：把两条"文档约定"变成"启动条件"，让配错的环境起不来而不是带着洞跑。
 *
 * <p>第一条是密钥：{@code recon.security.api-key} 没配时，所有写接口都变成匿名可调。
 * 这件事在开发档是常态（省事），在生产档是事故 —— 所以生产 profile 下**不配就启动失败**，
 * 错误信息直接给出该配什么，而不是等到第一次有人匿名改规则才发现。
 *
 * <p>第二条是审批：{@code recon.model.require-approval=false} 会让 {@code recon_build_model}
 * 把机器生成的 {@code __MODEL} 规则直接写成 ACTIVE，跳过试算与人工审批。这个开关保留给
 * "没启用 Agent、拿不到试算能力"的历史部署，但它在生产档下等于把审批这道关拆了 ——
 * 所以生产 profile 下直接拒绝启动。
 *
 * <p>"生产"的判定刻意保守：显式的 profile 名里带 {@code prod} 才算（{@code prod}、
 * {@code production}、{@code prod-mysql} 都算）。没有显式 profile（等于 {@code default}）、
 * {@code test}、{@code mcp}、{@code mysql} 都不算 —— 后者是联调档，文档里本来就不带密钥。
 * 想让某个部署提前上锁，可以显式设 {@code recon.security.require-api-key=true}。
 */
@Component
public class StartupSafety implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupSafety.class);

    /** profile 名里带这个词就按生产处理。 */
    static final String PRODUCTION_MARK = "prod";

    private final ReconProperties properties;
    private final Environment environment;

    public StartupSafety(ReconProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> profiles = List.of(environment.getActiveProfiles());
        boolean production = isProduction(profiles);
        checkModelApproval(production, profiles);
        checkApiKey(production, profiles);
        checkReviewKey(production, profiles);
        reportLlmOptional(profiles);
    }

    /** 显式的 profile 名里有 {@code prod} 才算生产；没有显式 profile 时是 {@code default}。 */
    static boolean isProduction(List<String> profiles) {
        return profiles.stream()
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(profile -> profile.contains(PRODUCTION_MARK));
    }

    private void checkModelApproval(boolean production, List<String> profiles) {
        if (properties.getModel().isRequireApproval()) {
            return;
        }
        String message = "recon.model.require-approval=false：机器生成的 __MODEL 规则会直接写成 ACTIVE，"
                + "跳过试算与人工审批。这个开关只允许在开发档用（当前 profile=" + profiles + "）";
        if (production) {
            throw new IllegalStateException(message + "；生产档下必须保持 true。");
        }
        log.warn("{}。它在生产档下会拒绝启动。", message);
    }

    private void checkApiKey(boolean production, List<String> profiles) {
        Boolean explicit = properties.getSecurity().getRequireApiKey();
        boolean required = explicit != null ? explicit : production;
        if (!required) {
            if (production) {
                // 生产档但显式关了（recon.security.require-api-key=false）：允许，但必须留痕。
                log.warn("生产 profile（{}）下显式关闭了密钥校验：写接口匿名可调，"
                        + "请确认这是你想要的（recon.security.require-api-key=false）。", profiles);
            }
            return;
        }
        if (properties.getSecurity().enforced()) {
            return;
        }
        throw new IllegalStateException("recon.security.api-key 未配置："
                + (production ? "生产 profile（" + profiles + "）下写接口会变成匿名可调" : "已显式要求校验密钥")
                + "。请设置 --recon.security.api-key=<密钥>，或把 recon.security.require-api-key 设为 false "
                + "（只应在开发档这么做）。");
    }

    /**
     * 审批密钥：{@code recon.review.api-key} 留空时审批接口<b>一律拒绝</b>（fail-closed）。
     * 那是刻意的，不是事故；但它意味着"闸门 3 永久关闭、规则再也批不了"，<b>生产档下等于起不来</b>
     * —— 所以按启动条件处理。
     *
     * <p>和数据面密钥（{@link #checkApiKey}）分开写错误信息：一把是"人签字"的凭据，
     * 一把是"谁可以发任务"的凭据，混用的后果是"能跑对账"自动等于"能批准规则"。
     */
    private void checkReviewKey(boolean production, List<String> profiles) {
        String key = properties.getReview().getApiKey();
        if (key != null && !key.isBlank()) {
            return;
        }
        String message = "recon.review.api-key 未配置：审批接口（POST /api/rule-reviews/**）一律拒绝，"
                + "闸门 3 无法通过，规则只能停在 DRAFT";
        if (production) {
            throw new IllegalStateException(message + "（当前 profile=" + profiles + "）。"
                    + "请设置 --recon.review.api-key=<审批密钥>；它和数据面密钥 --recon.security.api-key 是两把锁，不要混用。");
        }
        log.warn("{}。开发档可以这样跑；生产档下会拒绝启动。", message);
    }

    /**
     * LLM 密钥是<b>可选项</b>：没有就降级为 P1（词表路由 + 模板归因），这是设计内的降级，不是故障。
     *
     * <p>这条 INFO 存在的唯一目的，是让运维别把"没有模型"当成"起不来"——P0/P1 档本来就
     * 不依赖任何外部服务。
     */
    private void reportLlmOptional(List<String> profiles) {
        String chatKey = environment.getProperty("recon.chat.llm.api-key", "");
        boolean chatReady = chatKey != null && !chatKey.isBlank();
        boolean explainReady = !properties.getLlm().resolveApiKey().isEmpty();
        if (properties.getLlm().isEnabled() && !explainReady) {
            log.warn("recon.llm.enabled=true 但没有 api-key：归因/起草会降级到模板或直接拒绝，请补密钥或关掉这个开关。");
        }
        if (chatReady || explainReady) {
            return;
        }
        log.info("未配置任何 LLM api-key（recon.chat.llm.api-key / recon.llm.api-key）：按 P1 档运行 —— "
                + "意图识别走词表、差异归因走模板、规则起草不可用。这是设计内的降级，不是启动失败（profile={}）。", profiles);
    }
}
