package io.github.dolow.reconlens.domain;

/**
 * 规则草稿是谁起草的。
 *
 * <p><b>它不能单独决定"要不要签字"</b>（第四轮 9.1-③）：人工把 {@code tolerancePercent} 从 0.1 调到 10，
 * 恰是本方案最想防的那件事。真正的轴是<b>变更类别</b>（见 {@code RuleChange}），
 * {@code origin} 只是第二维：它决定"这一版是谁的责任"，不决定"能不能少验一次"。
 */
public enum RuleOrigin {

    /** 人工起草（REST / 种子导入 / 人自己写的脚本）。 */
    HUMAN,

    /** Agent 编排器起草（{@code recon_draft_rule}）。 */
    AGENT,

    /**
     * MCP 客户端（宿主大模型）通过 {@code recon_define_rule} 提交。
     *
     * <p>单列一个值是为了让审计能回答"这条规则是模型写的还是人写的"：
     * 之前统一记成 {@code HUMAN}，事后从 {@code recon_rule} 里分不出来。
     */
    MCP;

    public static RuleOrigin from(String raw) {
        if (raw == null || raw.isBlank()) {
            return HUMAN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的规则来源: " + raw);
        }
    }
}
