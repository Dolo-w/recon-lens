package io.github.dolow.reconlens.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K4 厂商语义摘录的契约测试：摘录不是随手注释，是有版本、有出口的正式资产。
 * 规则 —— 有摘录必须有版本；摘录写进 profile 就必须能从 digest 出口取出来；
 * 不存在的 profile / 没摘录的 profile 返回空串，调用方据此跳过，不硬塞段落。
 */
class ColumnProfilesSemanticsTest {

    @Test
    void lago两个发票profile都有摘录且带版本() {
        for (ColumnProfile profile : new ColumnProfile[]{
                ColumnProfiles.LAGO_INVOICE_FEES, ColumnProfiles.LAGO_INVOICES}) {
            assertThat(profile.billingSemantics())
                    .as("%s 的语义摘录不应为空", profile.id())
                    .isNotEmpty();
            assertThat(profile.semanticsVersion())
                    .as("%s 的语义版本不应为空", profile.id())
                    .isEqualTo("lago-api-2026-11");
        }
    }

    @Test
    void orb发票profile有摘录且带版本() {
        assertThat(ColumnProfiles.ORB_INVOICES.billingSemantics()).isNotEmpty();
        assertThat(ColumnProfiles.ORB_INVOICES.semanticsVersion()).isEqualTo("orb-api-2026-11");
    }

    @Test
    void 没摘录的profile返回空且版本为空() {
        // orb-subscriptions 目前没写语义摘录：默认实现必须是"空 = 没有"，不能抛异常
        assertThat(ColumnProfiles.resolve("orb-subscriptions").billingSemantics()).isEmpty();
        assertThat(ColumnProfiles.resolve("orb-subscriptions").semanticsVersion()).isEmpty();
    }

    @Test
    void digest出口与profile内容一致() {
        String digest = ColumnProfiles.semanticsDigest("lago-invoices");
        assertThat(digest).contains("lago-invoices").contains("lago-api-2026-11");
        for (String line : ColumnProfiles.LAGO_INVOICES.billingSemantics()) {
            assertThat(digest).as("digest 必须完整带出摘录：%s", line).contains(line);
        }
    }

    @Test
    void digest对未知或无摘录profile返回空串() {
        assertThat(ColumnProfiles.semanticsDigest("no-such-profile")).isEmpty();
        assertThat(ColumnProfiles.semanticsDigest("orb-subscriptions")).isEmpty();
        assertThat(ColumnProfiles.semanticsDigest(null)).isEmpty();
        assertThat(ColumnProfiles.semanticsDigest("  ")).isEmpty();
    }

    @Test
    void 摘录短句宁缺毋滥_单条不超两百字() {
        for (ColumnProfile profile : ColumnProfiles.all()) {
            for (String line : profile.billingSemantics()) {
                assertThat(line.length())
                        .as("%s 的摘录条目要短句：%s", profile.id(), line)
                        .isLessThanOrEqualTo(200);
            }
        }
    }
}
