package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.tool.ToolOutcome;

import java.util.Map;

/**
 * 把工具结果说成人话。
 *
 * <p>MCP 与会话共用同一批工具，但读者不同：宿主大模型需要「下一步：recon_trial_status(ruleCode=…)」，
 * 人不需要——他既没有那个函数可以调，看到一串内部名字也只会以为系统坏了。
 * 所以工具只产出事实，由这一层按读者组句：agent 读 {@code ToolOutcome.text}，人读这里。
 *
 * <p>这不是"润色"：事实必须一个不少（编码、版本、维度、比对字段、试算状态），
 * 去掉的只是路由信息（调哪个工具、走哪个接口）。
 */
public final class ChatSpeech {

    private ChatSpeech() {
    }

    /** {@code recon_define_rule} 的结果：优先用结构化事实重说，拿不到才退回工具文本并抹掉内部名。 */
    public static String defineRule(ToolOutcome outcome) {
        if (outcome == null) {
            return "这条规则没有处理成功。";
        }
        Map<String, Object> data = outcome.structured();
        if (data != null && data.get("code") instanceof String code && !code.isBlank()) {
            return draftReceipt(data);
        }
        return stripInternal(outcome.text());
    }

    /** 落草稿的回执：只讲「建了什么、按什么对、试算到哪一步、还要人确认一次」。 */
    static String draftReceipt(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("规则 ").append(data.get("code")).append(" 的草稿 v").append(data.get("version"))
                .append(" 已落库（操作人 ").append(data.getOrDefault("operator", "未署名")).append("）。");
        sb.append("对账按「").append(data.getOrDefault("dimension", "业务键")).append("」对齐")
                .append("，核对「").append(data.getOrDefault("compares", "")).append("」。");
        switch (String.valueOf(data.getOrDefault("trialState", "queued"))) {
            case "queued" -> sb.append("影子试算已排队，大约一分钟出结论，出结果了可以再来问我。");
            case "agent-disabled" -> sb.append("影子试算没跑：后台试算能力没开。");
            case "done" -> sb.append("影子试算结论：").append(data.getOrDefault("trialDetail", "已完成")).append("。");
            case "rejected" -> sb.append("影子试算没跑成：").append(data.getOrDefault("trialDetail", ""))
                    .append(" 规则还是草稿，改完可以再提交一版。");
            default -> sb.append("影子试算没排上：").append(data.getOrDefault("trialDetail", ""))
                    .append(" 规则已存成草稿，稍后可以再试。");
        }
        sb.append("要让它生效，还得你在审批里确认一次。");
        return sb.toString();
    }

    /**
     * 工具给宿主模型留的收尾（「下一步：recon_list_diffs 看差异明细，recon_explain_job 生成归因。」）
     * 不该出现在会话里：用户手里没有这些工具，看到只会以为系统在让他敲接口。
     * 只删"下一步："打头、且确实提到内部名的整行，其余行原样保留。
     */
    static String stripAgentTail(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text.replaceAll("(?m)^\\s*下一步：.*(recon_[a-z_]+|POST /api/).*\\n?", "")
                .stripTrailing();
    }

    /**
     * 规则清单（人话版）：按「这一版到哪一步」说，不出现任何工具名。
     *
     * <p>{@code draftsOnly} 来自用户原话（草稿/待审/未生效…）：要草稿就只答草稿；
     * 一条草稿都没有时明确说"没有"，而不是把四条生效规则端上来充数。
     */
    static String ruleList(java.util.List<io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary> all,
                           boolean draftsOnly) {
        StringBuilder sb = new StringBuilder(320);
        java.util.List<io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary> drafts = all.stream()
                .filter(r -> !r.active())
                .toList();
        if (draftsOnly && drafts.isEmpty()) {
            sb.append("现在没有草稿或待审的规则，").append(all.size()).append(" 条规则都是生效版");
            appendNames(sb, all);
            return sb.append("。").toString();
        }
        if (draftsOnly) {
            sb.append("现在有 ").append(drafts.size()).append(" 条还没生效的规则：");
            appendLines(sb, drafts);
            sb.append("\n其余 ").append(all.size() - drafts.size()).append(" 条是生效版。");
            return sb.toString();
        }
        sb.append("规则库现有 ").append(all.size()).append(" 条规则（生效 ")
                .append(all.size() - drafts.size()).append(" 条");
        if (!drafts.isEmpty()) {
            sb.append("，草稿/待审 ").append(drafts.size()).append(" 条");
        }
        sb.append("）：");
        appendLines(sb, all);
        return sb.toString();
    }

    private static void appendLines(StringBuilder sb,
                                    java.util.List<io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary> rules) {
        for (io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary r : rules) {
            sb.append("\n- ").append(r.code()).append(" v").append(r.version())
                    .append(r.active() ? "（生效）" : "（" + lifecycleWord(r.lifecycle()) + "）");
            if (r.name() != null && !r.name().isBlank()) {
                sb.append("：").append(r.name());
            }
        }
    }

    private static void appendNames(StringBuilder sb,
                                    java.util.List<io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary> rules) {
        if (!rules.isEmpty()) {
            sb.append("（").append(rules.stream()
                    .map(io.github.dolow.reconlens.rule.RuleCatalog.RuleSummary::code)
                    .collect(java.util.stream.Collectors.joining("、"))).append("）");
        }
    }

    private static String lifecycleWord(io.github.dolow.reconlens.domain.RuleLifecycle lifecycle) {
        return switch (lifecycle) {
            case ACTIVE -> "生效";
            case REVIEWING -> "审核中";
            case REJECTED -> "已驳回";
            case SUPERSEDED -> "已归档";
            case DRAFT -> "草稿";
        };
    }

    /**
     * 兜底：结构化事实拿不到时，抹掉写给宿主大模型的内部名。
     * 只替换已知形态（{@code recon_xxx}、{@code POST /api/…}），不做宽泛替换——那会把正常业务词也吃掉。
     */
    static String stripInternal(String text) {
        if (text == null || text.isBlank()) {
            return "这条规则没有处理成功。";
        }
        return text.replaceAll("recon_[a-z_]+", "对应操作")
                .replaceAll("POST /api/[A-Za-z0-9_/{}\\-]+", "审批接口")
                .replaceAll("[（(]来源 MCP[^）)]*[）)]", "")
                .trim();
    }
}
