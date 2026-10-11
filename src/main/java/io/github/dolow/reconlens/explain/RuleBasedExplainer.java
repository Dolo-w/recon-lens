package io.github.dolow.reconlens.explain;

import io.github.dolow.reconlens.domain.DiffKind;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 规则模板归因：完全离线、零成本、结果可预期。
 * <p>绝大多数差异的业务原因其实是有限几种，模板能把它们讲清楚；
 * 大模型只用在"模板没覆盖、需要更贴近上下文"的场景，并且失败时落回模板。
 */
@Component
public class RuleBasedExplainer implements DiffExplainer {

    @Override
    public String name() {
        return "rule-template";
    }

    @Override
    public String explain(RuleSpec rule, DiffRecord diff) {
        DiffKind kind = rule.classify(diff.diffType());
        return switch (kind) {
            case VALUE_MISMATCH -> valueMismatch(rule, diff);
            case MISSING_LEFT -> String.format(
                    "业务键 %s 在%s侧没有记录，只有%s侧有（%s）。常见原因：两侧对账时间窗不一致、我方补单/同步任务漏推、对方提前入账。",
                    diff.bizKey(), rule.left().label(), rule.right().label(), nullToDash(diff.rightValue()));
            case MISSING_RIGHT -> String.format(
                    "业务键 %s 在%s侧没有记录，只有%s侧有（%s）。常见原因：对方延迟入账或推送失败、我方已受理但未落库、批次边界跨天。",
                    diff.bizKey(), rule.right().label(), rule.left().label(), nullToDash(diff.leftValue()));
            case DUPLICATE_KEY -> String.format(
                    "业务键 %s 出现重复记录（%s / %s）。常见原因：重复入账或消息重放，需要按幂等键去重后重跑本分片。",
                    diff.bizKey(), nullToDash(diff.leftValue()), nullToDash(diff.rightValue()));
        };
    }

    private String valueMismatch(RuleSpec rule, DiffRecord diff) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(rule.left().label()).append(" 与 ").append(rule.right().label())
                .append(" 的 ").append(diff.field()).append(" 不一致：左侧 ")
                .append(nullToDash(diff.leftValue())).append("，右侧 ").append(nullToDash(diff.rightValue()));
        if (diff.deltaValue() != null) {
            sb.append("，相差 ").append(plain(diff.deltaValue()));
            if (diff.deltaRatio() != null) {
                sb.append("（相对 ").append(percent(diff.deltaRatio())).append("）");
            }
        }
        sb.append("。常见原因：入账时点不同、手续费/汇率进位、撤单退单未同步、数据被人工修改。");
        return sb.toString();
    }

    private static String percent(BigDecimal ratio) {
        return ratio.abs().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
