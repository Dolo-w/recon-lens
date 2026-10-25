package io.github.dolow.reconlens.domain;

/**
 * 规则<b>版本级</b>的状态。与 {@code recon_rule.enabled}（<b>code 级</b>）是两个维度，不要合并：
 * <ul>
 *   <li>{@code enabled=false} 表示"这条规则整条下线"，一次 UPDATE 作用于该 code 的所有版本；</li>
 *   <li>{@code lifecycle} 描述"这一版眼下处在什么状态"。</li>
 * </ul>
 * 所以 "enabled=false + 某版 ACTIVE" 不矛盾，它的含义是"整条下线了，但这一版仍是当年生效的那一版"，
 * 这正是历史任务回放需要的信息。
 */
public enum RuleLifecycle {

    /** 草稿：已落库留档，但 {@code resolve} 不认。 */
    DRAFT,

    /** 已提交审核：等待人工签字；{@code resolve} 同样不认。 */
    REVIEWING,

    /** 生效：{@code RuleCatalog.resolve} 唯一会返回的状态。 */
    ACTIVE,

    /** 驳回：留档供复盘与 few-shot，不参与运行。 */
    REJECTED,

    /**
     * 归档：被同 code 的新 ACTIVE 版本取代。
     *
     * <p>为什么不是"删除"：审计要能回答"这条规则被改过几版、每版差在哪"。
     * 归档只是<b>折叠</b>（列表默认不显示），行还在，{@code superseded_by} 指向取代它的那一版。
     */
    SUPERSEDED;

    public static RuleLifecycle from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的规则生命周期状态: " + raw);
        }
    }
}
