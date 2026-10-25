package io.github.dolow.reconlens.chat;

/**
 * 内置会话能做的动作 —— 与 L0 的「八个固定动作」一一对应，外加一个兜底的 {@link #HELP}。
 *
 * <p>每个动作背后都是<b>同一份</b>工具实现（{@code mcp.ReconMcpTools} / 引擎），不是第二套代码：
 * 会话里说「跑一下」和 MCP 客户端调 {@code recon_run}，走的必须是同一条路径，
 * 否则"用对话框得到的结论"和"用接口得到的结论"会在某天开始不一致。
 *
 * <p><b>刻意不在这里的动作</b>：审批（{@link #APPROVE} 只解释流程，不执行）。会话里说「我同意」
 * 永远不等于签字——审批只认 {@code confirmationCode} + 内容哈希（ADR-0001 N5 / D5）。
 */
public enum ChatIntent {

    /** 列出规则库。 */
    /** 执行历史：跑过哪些规则、各自最近一次是什么时候。与规则目录分开，见 {@code recon_list_jobs}。 */
    LIST_JOBS("历史任务", "之前执行过哪些规则 / 最近跑过什么"),
    LIST_RULES("看规则", "有哪些规则 / 规则列表"),
    /** 列出已注册的数据表（闸门白名单、行数、结构哈希）。 */
    LIST_DATA("看数据", "有哪些数据 / 有哪些表 / 数据源在哪儿"),
    /** 看某张表的结构（列名、类型、可空、主键、行数）。起草规则前先看字段。 */
    DESCRIBE_TABLE("看字段", "看一下 bill_left 的字段 / 这张表有哪些列 / 表结构"),
    /** 定义规则（贴 YAML/JSON）。 */
    DEFINE_RULE("定义规则", "贴一份 YAML 规则 / 起草一条规则"),
    /** 跑一次对账。 */
    RUN("跑一次", "跑一下 BILL_VS_SETTLE / 开始对账"),
    /** 任务总览。 */
    JOB_SUMMARY("任务总览", "123 号任务怎么样"),
    /** 看差异明细。 */
    LIST_DIFFS("看差异", "看差异 / 值不等的有多少"),
    /** 整单归因。 */
    EXPLAIN_JOB("整单归因", "为什么差这么多"),
    /** 单条差异归因。 */
    EXPLAIN_DIFF("单条归因", "第 137 条差异为什么"),
    /** 重跑失败分片。 */
    RETRY("重跑", "重跑 123 号任务"),
    /** 物化模型表。 */
    BUILD_MODEL("建模型", "给 BILL_VS_SETTLE 建模型"),
    /** 试算状态。 */
    TRIAL_STATUS("看试算", "草案试算通过了吗"),
    /** 审批流程说明（不执行审批）。 */
    APPROVE("审批怎么走", "我要批准这条规则"),
    /** 兜底：列出上面这些动作。 */
    HELP("能做什么", "帮助");

    private final String form;
    private final String example;

    ChatIntent(String form, String example) {
        this.form = form;
        this.example = example;
    }

    /** 表单上的名字（L0 界面直接用）。 */
    public String form() {
        return form;
    }

    /** 这句话可以怎么说——写进帮助文本，让用户知道不必记关键字。 */
    public String example() {
        return example;
    }

    /**
     * 是不是"会改东西"的动作。
     *
     * <p>这条区分是上下文协议的地基：<b>读操作猜错了只影响这一屏，写操作猜错了会改数据</b>。
     * 所以读可以默认"最近一次"并加回执，写必须有确定参数、否则先问。
     * {@link #APPROVE} 不算写——它只解释流程，真正的签字走确认码（ADR-0001 D5）。
     */
    public boolean isWrite() {
        return this == RUN || this == RETRY || this == BUILD_MODEL || this == DEFINE_RULE;
    }
}
