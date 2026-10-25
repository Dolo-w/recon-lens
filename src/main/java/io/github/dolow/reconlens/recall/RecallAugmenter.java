package io.github.dolow.reconlens.recall;

import java.util.List;

/**
 * 把"同类案例"和"口径依据"追加到归因结论后面。
 *
 * <p>为什么单独一层而不是塞进 {@code ExplainService}：解释器回答的是"为什么会差"，
 * 这里回答的是"上次这类怎么处理的、手册上怎么规定的"。两件事的数据来源、可信度、
 * 过期速度都不一样，混在一起以后就没法单独替换其中一半。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li><b>检索失败不影响归因</b>：三条记忆都没有时返回空串，调用方就什么都不追加；</li>
 *   <li><b>案例只是参考，不自动套用</b>：输出里明确写"仅供参考"，避免把上一次的处理
 *       当成这一次的结论——差异长得很像，业务原因不一定相同。</li>
 * </ul>
 */
public class RecallAugmenter {

    private final CaseMemoryService cases;
    private final DocChunkService docs;

    public RecallAugmenter(CaseMemoryService cases, DocChunkService docs) {
        this.cases = cases;
        this.docs = docs;
    }

    /**
     * @param ruleCode 规则名，用于收窄案例；为空则跳过案例段
     * @param diffType 差异类型，用于收窄案例
     * @param question 用来查口径文档的原话（一般是业务键 + 差异类型）
     */
    public String augment(String ruleCode, String diffType, String question) {
        return augment(ruleCode, diffType, null, question);
    }

    /**
     * @param field 差异涉及的字段名（可为空）；用来把英文差异类型翻成手册里会出现的中文词
     */
    public String augment(String ruleCode, String diffType, String field, String bizKey) {
        return augment(ruleCode, diffType, field, bizKey, null);
    }

    /**
     * @param datasetVersion 这次差异所在任务的数据版本（ADR-0002）；知道就传，让"口径变过的旧案例"退场
     */
    public String augment(String ruleCode, String diffType, String field, String bizKey, String datasetVersion) {
        StringBuilder sb = new StringBuilder();
        if (ruleCode != null && !ruleCode.isBlank()) {
            String caseText = cases.render(ruleCode, diffType, datasetVersion, 3);
            if (!caseText.isEmpty()) {
                sb.append('\n').append(caseText);
            }
        }
        String manualQuery = manualQuery(diffType, field);
        DegradingDocSearch.Outcome outcome = manualQuery.isEmpty()
                ? null : docs.searchWithModes(manualQuery, null, 2);
        List<DocHit> hits = outcome == null ? List.of() : outcome.hits();
        if (hits.isEmpty() && bizKey != null && !bizKey.isBlank()) {
            // 第一种查法查不到时退回业务键：手册里偶尔会直接写某个客户/订单号的特殊约定
            outcome = docs.searchWithModes(bizKey, null, 2);
            hits = outcome.hits();
        }
        if (!hits.isEmpty() && outcome != null) {
            // 降级过就写清楚：同一个问题，只有关键词层和有向量层给出的可信度不一样
            sb.append("口径依据（来自口径文档，命中词已标出；检索方式 ")
                    .append(outcome.mode().name())
                    .append(outcome.degraded() ? "，已降级：" + outcome.reason() : "")
                    .append("）：\n");
            for (DocHit hit : hits) {
                sb.append("· 《").append(hit.chunk().title()).append("》第 ").append(hit.chunk().ordinal())
                        .append(" 段（命中 ").append(String.join("、", hit.matched())).append("）：")
                        .append(abridge(hit.chunk().content())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 把差异类型翻译成"手册里真会出现的词"。
     *
     * <p>为什么不能直接拿 diffType 去搜：`AMOUNT_MISMATCH` 是 DSL 里的英文类型，
     * 而口径手册全是中文，直接搜英文永远搜不到。这层映射是纯静态的、可测试的，
     * 不依赖模型——它只是"领域词表"，不是推理。
     */
    static String manualQuery(String diffType, String field) {
        if (diffType == null) {
            return field == null ? "" : field;
        }
        String t = diffType.toUpperCase(java.util.Locale.ROOT);
        String zh = switch (t) {
            case "AMOUNT_MISMATCH", "VALUE_MISMATCH" -> "金额 不一致";
            case "MISSING_RIGHT" -> "单边缺失 结算侧";
            case "MISSING_LEFT" -> "单边缺失 账单侧";
            case "DUPLICATE_KEY" -> "重复 幂等";
            default -> "";
        };
        if (field != null && !field.isBlank()) {
            return zh.isEmpty() ? field : zh;
        }
        return zh;
    }

    private static String abridge(String content) {
        if (content == null) {
            return "";
        }
        String one = content.replace("\n", " ").trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }
}
