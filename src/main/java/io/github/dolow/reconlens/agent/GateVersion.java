package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.rule.SpecHasher;

/**
 * 闸门的"版本号"：实现标签 + <b>全部判定阈值</b>的摘要。
 *
 * <p>为什么阈值要进版本：这些值不是调优旋钮，而是判据的一部分。
 * 把 {@code min-sample-rows} 从 200 调到 20，同一条规则会从 {@code FULL} 变成
 * {@code SAMPLE_PARTIAL}（或反过来）—— 这已经换了一套闸门，之前那次 canary 的结论不能顺延。
 *
 * <p>反过来，如果版本号只由代码常量决定，运维改配置就能让一个"canary 已经失败"的闸门
 * 重新看起来可信 —— 而这正是降级签字通道最容易被悄悄打开的方式。
 */
public final class GateVersion {

    private GateVersion() {
    }

    public static String of(ReconProperties.Trial cfg) {
        String material = String.join("|",
                "trial-gate", cfg.getGateVersion(),
                "windowRows=" + cfg.getWindowRows(),
                "windowAttempts=" + cfg.getWindowAttempts(),
                "minSampleRows=" + cfg.getMinSampleRows(),
                "minCommonKeys=" + cfg.getMinCommonKeys(),
                "minShardsWithData=" + cfg.getMinShardsWithData(),
                "minShardCoverageRatio=" + cfg.getMinShardCoverageRatio(),
                "maxSampleBytes=" + cfg.getMaxSampleBytes(),
                "diffGrowthRatio=" + cfg.getDiffGrowthRatio());
        return SpecHasher.sha256(material);
    }
}
