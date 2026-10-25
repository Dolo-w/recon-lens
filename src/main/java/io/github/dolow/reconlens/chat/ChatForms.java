package io.github.dolow.reconlens.chat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 14 个动作的表单定义（与 {@link ChatIntent} 一一对应）。
 *
 * <p>这份清单是**无 key 用户的主界面**：打开页面先拉 {@code GET /api/chat/forms}，
 * 按 label 画按钮，按 fields 画输入框，提交到 {@code POST /api/chat/actions}。
 * 全程不需要模型，也不需要 MCP 宿主——这就是 ADR-0001 P0 出口条件里"只用浏览器能走完"的实现。
 *
 * <p>字段名刻意和 {@link ChatIntentRouter.Call} 的组件同名：{@code ruleCode} / {@code jobId} /
 * {@code diffId} / {@code diffType} / {@code ruleBody} / {@code shards}。少一层映射就少一处写错的机会。
 */
public final class ChatForms {

    private static final List<ChatForm> ALL = List.of(
            new ChatForm(ChatIntent.LIST_JOBS, "历史任务", "列出最近跑过的对账任务，并按规则汇总跑了多少次", List.of()),
            new ChatForm(ChatIntent.LIST_RULES, "看规则", "列出规则库里生效的规则版本", List.of()),
            new ChatForm(ChatIntent.LIST_DATA, "看数据", "列出已注册进系统的表：列、行数、结构哈希", List.of()),
            new ChatForm(ChatIntent.DESCRIBE_TABLE, "看字段",
                    "看一张表的列名/类型/可空/主键/行数（起草前先看字段）",
                    List.of(ChatForm.Field.requiredText("table", "表名", "bill_left",
                                    "必须是白名单里的表；不确定就先「看数据」"))),
            new ChatForm(ChatIntent.RUN, "跑一次对账", "按规则跑一次；省略规则名时用会话里记着的那条",
                    List.of(ChatForm.Field.text("ruleCode", "规则编码", "BILL_VS_SETTLE"))),
            new ChatForm(ChatIntent.JOB_SUMMARY, "任务总览", "看某个任务的扫描量、匹配数、差异分类",
                    List.of(ChatForm.Field.number("jobId", "任务号", "省略则用会话里最近一次任务"))),
            new ChatForm(ChatIntent.LIST_DIFFS, "看差异", "分页看差异明细，可按类型过滤",
                    List.of(ChatForm.Field.number("jobId", "任务号", "省略则用会话里最近一次任务"),
                            ChatForm.Field.text("diffType", "差异类型", "MISSING_RIGHT / VALUE_MISMATCH"))),
            new ChatForm(ChatIntent.EXPLAIN_JOB, "整单归因", "这个任务为什么会差，逐条给原因",
                    List.of(ChatForm.Field.number("jobId", "任务号", "省略则用会话里最近一次任务"))),
            new ChatForm(ChatIntent.EXPLAIN_DIFF, "单条归因", "某一条差异的原因与建议动作",
                    List.of(ChatForm.Field.number("diffId", "差异号", "省略则用会话里最近一次差异"))),
            new ChatForm(ChatIntent.RETRY, "重跑失败分片", "只重跑失败的分片，跑过的不会重算",
                    List.of(ChatForm.Field.number("jobId", "任务号", "省略则用会话里最近一次任务"))),
            new ChatForm(ChatIntent.BUILD_MODEL, "建预处理模型", "把规则里的派生维度物化成索引列（分片数沿用规则）",
                    List.of(ChatForm.Field.text("ruleCode", "规则编码", "BILL_VS_SETTLE"))),
            new ChatForm(ChatIntent.TRIAL_STATUS, "看试算状态", "规则各版本的试算结论与可批性",
                    List.of(ChatForm.Field.text("ruleCode", "规则编码", "BILL_VS_SETTLE"))),
            new ChatForm(ChatIntent.DEFINE_RULE, "起草规则", "贴 YAML 或 JSON，落库为草稿并自动跑影子试算",
                    List.of(ChatForm.Field.requiredTextArea("ruleBody", "规则正文",
                            "code: BILL_VS_SETTLE\nleft:  {table: bill_left}\nright: {table: bill_right}"))),
            new ChatForm(ChatIntent.APPROVE, "审批要怎么做", "解释审批流程（会话与表单都不代签）", List.of()),
            new ChatForm(ChatIntent.HELP, "能做什么", "列出全部动作与说法", List.of()));

    private ChatForms() {
    }

    public static List<ChatForm> all() {
        return ALL;
    }

    /** 按动作名找表单，大小写不敏感（{@code run} / {@code RUN} 都行）。 */
    public static Optional<ChatForm> of(String action) {
        if (action == null || action.isBlank()) {
            return Optional.empty();
        }
        String key = action.trim().toUpperCase(Locale.ROOT);
        return ALL.stream().filter(form -> form.action().name().equals(key)).findFirst();
    }
}
