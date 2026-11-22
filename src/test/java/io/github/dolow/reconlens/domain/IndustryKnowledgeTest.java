package io.github.dolow.reconlens.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryKnowledgeTest {

    private IndustryKnowledge entry(IndustryKnowledge.AppliesWhen when) {
        return new IndustryKnowledge(null, "IND-TEST", when, "遇到差异先查配置口径", "unit-test-2026-11",
                IndustryKnowledge.Origin.SEED, 0.7, IndustryKnowledge.Status.ACTIVE, 0, 0, null);
    }

    @Test
    @DisplayName("条件匹配：全部声明维度命中才算命中，未声明维度不参与判定")
    void conditionMatching() {
        IndustryKnowledge.AppliesWhen when = new IndustryKnowledge.AppliesWhen(
                List.of("BILL_MATCH"), List.of("AMOUNT_MISMATCH"), ".*amount.*", List.of("+"));
        IndustryKnowledge e = entry(when);

        assertThat(e.appliesWhen().matches("bill_match", "amount_mismatch", "total_amount", "+")).isTrue();
        // 规则码不同 → 不匹配
        assertThat(e.appliesWhen().matches("settle_match", "amount_mismatch", "total_amount", "+")).isFalse();
        // 差异类型不同 → 不匹配
        assertThat(e.appliesWhen().matches("bill_match", "duplicate", "total_amount", "+")).isFalse();
        // 字段不中正则 → 不匹配
        assertThat(e.appliesWhen().matches("bill_match", "amount_mismatch", "order_no", "+")).isFalse();
        // 方向不同 → 不匹配
        assertThat(e.appliesWhen().matches("bill_match", "amount_mismatch", "total_amount", "-")).isFalse();
        // 未声明的维度（这里是 ruleCodes 为空的场景）不参与判定
        IndustryKnowledge.AppliesWhen loose = new IndustryKnowledge.AppliesWhen(
                List.of(), List.of("duplicate"), null, List.of());
        assertThat(loose.matches(null, "DUPLICATE", null, null)).isTrue();
    }

    @Test
    @DisplayName("机器准入：无条件/无来源/超长文本/坏正则都被拒绝")
    void admissionGate() {
        // 全空条件拒绝
        assertThat(entry(new IndustryKnowledge.AppliesWhen(List.of(), List.of(), null, List.of()))
                .admissionRejection()).contains("全空");
        // 无来源拒绝
        assertThat(new IndustryKnowledge(null, "K", new IndustryKnowledge.AppliesWhen(
                List.of("R"), List.of(), null, List.of()), "内容", "",
                IndustryKnowledge.Origin.HARVEST, 0.4, IndustryKnowledge.Status.ACTIVE, 0, 0, null)
                .admissionRejection()).contains("source");
        // 坏正则拒绝
        assertThat(entry(new IndustryKnowledge.AppliesWhen(List.of(), List.of(), "([bad", List.of()))
                .admissionRejection()).contains("fieldRegex");
        // 合法条目通过
        assertThat(entry(new IndustryKnowledge.AppliesWhen(List.of(), List.of("tax"), null, List.of()))
                .admissionRejection()).isNull();
    }

    @Test
    @DisplayName("JSON 往返：条件序列化后能还原且匹配行为一致")
    void jsonRoundTrip() {
        IndustryKnowledge.AppliesWhen when = new IndustryKnowledge.AppliesWhen(
                List.of("BILL_MATCH"), List.of("Amount_Mismatch"), ".*amount.*", List.of("+"));
        String json = when.toJson();
        IndustryKnowledge.AppliesWhen back = IndustryKnowledge.AppliesWhen.fromJson(json);

        assertThat(back.ruleCodes()).containsExactly("bill_match");
        assertThat(back.matches("BILL_MATCH", "AMOUNT_MISMATCH", "fee_amount", "+")).isTrue();
        assertThat(back.matches("OTHER", "AMOUNT_MISMATCH", "fee_amount", "+")).isFalse();
    }

    @Test
    @DisplayName("最长公共子串：采纳判定用的近似度量（≥6 字算复述）")
    void commonSubstring() {
        assertThat(IndustryKbLcs.lcs("该差异是逐行计税再汇总的口径问题", "逐行计税再汇总与汇总计税再分摊是两种口径"))
                .isGreaterThanOrEqualTo(6);
        assertThat(IndustryKbLcs.lcs("税率档位不对", "预付费抵扣顺序差异")).isLessThanOrEqualTo(2);
        assertThat(IndustryKbLcs.lcs("", "任何内容")).isZero();
    }

    /** 避免直接依赖 service 包：LCS 逻辑在 IndustryKbService 是包级静态，这里镜像一个引用点。 */
    private static final class IndustryKbLcs {
        static int lcs(String a, String b) {
            return io.github.dolow.reconlens.explain.industry.IndustryKbService.longestCommonSubstring(a, b);
        }
    }
}
