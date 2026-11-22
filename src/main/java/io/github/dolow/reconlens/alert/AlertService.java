package io.github.dolow.reconlens.alert;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.config.SecurityPosture;
import io.github.dolow.reconlens.domain.Alert;
import io.github.dolow.reconlens.store.AlertRepository;
import io.github.dolow.reconlens.store.LlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警评估与通知（清单 P0-6）。
 *
 * <h2>三条规则，对应三类真实损失</h2>
 * <ol>
 *   <li><b>LLM 失败率</b>：模型层挂了意味着归因/路由/蒸馏全瘫。窗口内失败率超阈值即告警。
 *       要求最小样本量，否则"1 次失败 = 100%"天天响。</li>
 *   <li><b>写接口裸奔</b>：写接口匿名可调 + 对外监听 = 安全事故。这条不该等人发现。</li>
 *   <li><b>单日成本</b>：防跑飞（死循环重试、批量任务失控）。</li>
 * </ol>
 *
 * <h2>为什么"样本不足"时不解决已有告警</h2>
 * <p>如果没流量就把失败率告警置为 RESOLVED，那故障其实还在、只是没人来调 ——
 * "没数据"不能当成"已恢复"。所以样本不足时什么都不做，让告警保持原状。
 *
 * <h2>去重与通知</h2>
 * <p>同一 key 同时只保留一条 OPEN。只有<b>新产生</b>的告警才推 webhook，
 * 避免每 5 分钟重复轰炸（那是把告警变成噪音的最快方式）。
 */
@Service
public class AlertService {

    public static final String LLM_FAILURE_RATE = "llm_failure_rate";
    public static final String SECURITY_OPEN_WRITE = "security_open_write";
    public static final String DAILY_COST = "daily_cost";

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final LlmCallRepository llmCalls;
    private final SecurityPosture posture;
    private final ReconProperties properties;
    private final RestClient webhookClient;

    public AlertService(AlertRepository alerts, LlmCallRepository llmCalls,
                        SecurityPosture posture, ReconProperties properties) {
        this.alerts = alerts;
        this.llmCalls = llmCalls;
        this.posture = posture;
        this.properties = properties;
        this.webhookClient = RestClient.builder().build();
    }

    /**
     * 定时评估。{@code enabled=false} 时整个 Bean 不会被创建（见 {@link AlertConfiguration}），
     * 所以这里不需要再判开关。
     *
     * <p>初始延迟 20 秒：等应用把数据源、迁移、种子都跑完再判，避免启动瞬间的假告警。
     */
    @Scheduled(initialDelayString = "${recon.alert.initial-delay-ms:20000}",
            fixedDelayString = "${recon.alert.check-interval-ms:300000}")
    public void scheduledEvaluate() {
        if (!properties.getAlert().isEnabled()) {
            return; // 开关只影响"自动跑"；手动 /api/alerts/check 仍可用
        }
        try {
            evaluate();
        } catch (RuntimeException e) {
            // 告警系统自己不能把应用搞挂 —— 它只是个旁路观察者
            log.warn("告警评估失败（下次继续）: {}", e.toString());
        }
    }

    /**
     * 跑一轮全部规则，返回<b>本轮新产生</b>的告警（已存在的不重复返回）。
     *
     * <p>手动触发（{@code POST /api/alerts/check}）也走这里，方便交付时现场演示。
     */
    public List<Alert> evaluate() {
        List<Alert> raised = new ArrayList<>();
        checkLlmFailureRate(raised);
        checkSecurityPosture(raised);
        checkDailyCost(raised);
        return raised;
    }

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    private void checkLlmFailureRate(List<Alert> raised) {
        ReconProperties.Alert cfg = properties.getAlert();
        int[] stats = llmCalls.failureStats(cfg.getLlmFailureWindowMinutes());
        int total = stats[0];
        int failed = stats[1];

        if (total < cfg.getLlmFailureMinCalls()) {
            // 样本不足：既不报警也不解除（"没流量"≠"已恢复"）
            return;
        }

        double rate = (double) failed / total;
        if (rate >= cfg.getLlmFailureRateThreshold()) {
            raise(raised, LLM_FAILURE_RATE, Alert.SEVERITY_CRITICAL,
                    String.format("近 %d 分钟大模型调用失败率 %.1f%%（%d/%d），"
                                    + "归因/路由/蒸馏已降级。检查 provider 可用性与密钥。",
                            cfg.getLlmFailureWindowMinutes(), rate * 100, failed, total),
                    rate, cfg.getLlmFailureRateThreshold());
        } else {
            resolveQuietly(LLM_FAILURE_RATE);
        }
    }

    private void checkSecurityPosture(List<Alert> raised) {
        boolean exposedAndOpen = posture.listeningExternal() && !posture.writeAuthEnforced();
        if (exposedAndOpen) {
            raise(raised, SECURITY_OPEN_WRITE, Alert.SEVERITY_CRITICAL,
                    "写接口匿名可调且对外监听（" + posture.listenAddress() + "）："
                            + "任何人都能导入数据、建表、跑批、改规则。立即配置 recon.security.api-key。",
                    1.0, 0.0);
        } else {
            resolveQuietly(SECURITY_OPEN_WRITE);
        }
    }

    private void checkDailyCost(List<Alert> raised) {
        double threshold = properties.getAlert().getDailyCostYuanThreshold();
        if (threshold <= 0) {
            return; // 0 = 不检查（有些部署按量付费但不想被这个阈值打扰）
        }
        double todayYuan = llmCalls.todayCostMicros() / 1_000_000.0;
        if (todayYuan >= threshold) {
            raise(raised, DAILY_COST, Alert.SEVERITY_WARNING,
                    String.format("今日大模型成本已达 %.2f 元（阈值 %.2f 元）。"
                                    + "检查是否有批量任务失控或重试风暴。", todayYuan, threshold),
                    todayYuan, threshold);
        } else {
            resolveQuietly(DAILY_COST);
        }
    }

    // ------------------------------------------------------------------
    // 落库与通知
    // ------------------------------------------------------------------

    /** 已存在 OPEN 就复用（不重复插入、不重复通知），否则新建并推送。 */
    private void raise(List<Alert> raised, String key, String severity, String message,
                       double value, double threshold) {
        Optional<Alert> existing = alerts.findOpen(key);
        if (existing.isPresent()) {
            return; // 已经在报，不刷屏
        }
        Alert created = alerts.insert(new Alert(null, key, severity, message, value, threshold,
                Alert.OPEN, false, null, null));
        raised.add(created);
        log.warn("告警产生 [{}] {}：{}", severity, key, message);
        notify(created);
    }

    private void resolveQuietly(String key) {
        int resolved = alerts.resolve(key);
        if (resolved > 0) {
            log.info("告警恢复：{}（条件不再满足）", key);
        }
    }

    /** 推送 webhook；失败只记日志，绝不影响主流程（通知渠道本身可能是挂的）。 */
    private void notify(Alert alert) {
        String url = properties.getAlert().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertKey", alert.alertKey());
        payload.put("severity", alert.severity());
        payload.put("message", alert.message());
        payload.put("metricValue", alert.metricValue());
        payload.put("threshold", alert.thresholdValue());
        payload.put("application", "recon-lens");
        try {
            webhookClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            if (alert.id() != null) {
                alerts.markNotified(alert.id());
            }
        } catch (RuntimeException e) {
            log.warn("告警推送失败（告警已落库，可查 /api/alerts）: {}", e.toString());
        }
    }
}
