package io.github.dolow.reconlens.tool;

/**
 * 工具名常量。<b>只此一份</b>：MCP 注册、会话工具列表、控制台接口都从这里取。
 *
 * <p>写成常量而不是散落的字面量，是为了让"名字写错"变成编译错误而不是线上才发现——
 * 之前会话侧把 {@code recon_explain_job} 写成过 {@code recon_explain_jobs}，
 * 表现是「工具调用成功但返回空」，排查了两轮。
 */
public final class ToolNames {

    private ToolNames() {
    }

    // ---- 基础工具（MCP 常驻 12 个；控制台与会话用同一套实现）----
    public static final String LIST_RULES = "recon_list_rules";
    public static final String LIST_TABLES = "recon_list_tables";
    public static final String DESCRIBE_TABLE = "recon_describe_table";
    public static final String DEFINE_RULE = "recon_define_rule";
    public static final String BUILD_MODEL = "recon_build_model";
    public static final String RUN = "recon_run";
    public static final String JOB_SUMMARY = "recon_job_summary";
    /**
     * 执行历史。和 {@link #LIST_RULES} 是两件事：
     * 一个答"库里有哪几条规则"，一个答"这些规则里哪些真的跑过、跑了几次、最近一次怎么样"。
     * 用户说「之前执行过哪些规则」问的是后者——只给规则目录等于答非所问。
     */
    public static final String LIST_JOBS = "recon_list_jobs";
    public static final String LIST_DIFFS = "recon_list_diffs";
    public static final String EXPLAIN_DIFF = "recon_explain_diff";
    public static final String EXPLAIN_JOB = "recon_explain_job";
    public static final String DIAGNOSE = "recon_diagnose";

    // ---- Agent 专属（只在 recon.agent.enabled=true 时注册，实现在 mcp.AgentMcpTools）----
    public static final String DRAFT_RULE = "recon_draft_rule";
    public static final String TRIAL_STATUS = "recon_trial_status";
    public static final String REGISTER_TABLE = "recon_register_table";
    public static final String IMPORT_FILE = "recon_import_file";
    public static final String IMPORT_STATUS = "recon_import_status";
    public static final String SUBMIT_APPROVAL = "recon_submit_approval";

    /**
     * 待办审批列表。<b>不是 MCP 工具</b>：它有意只暴露在 REST（{@code GET /api/approvals}）——
     * 审批入口必须是人看得见、点得到的地方，不该给模型一个"列出待批规则"的工具。
     * 历史上它被误算进 Agent 工具清单，导致「目录说 6 个、实际注册 6 个」却对不上号。
     */
    public static final String PENDING_APPROVALS = "recon_list_approvals";

    // ---- 会话内组合出来的"虚拟工具"：不是 MCP 工具，但会话会把它记进工具轨迹 ----
    public static final String RECALL = "recall";

    /**
     * 会改状态的工具：执行对账、建模型表、注册表、导入文件、起草规则、提审批。
     *
     * <p>放在这里而不是每条 spec 上加一个参数，是为了只有一份清单 —— 之前"哪些是写动作"记在
     * {@code ChatIntent#isWrite()} 里，加一个新写工具要同时改枚举和工具定义，两处一漏就成了
     * "会话直接执行了本该先确认的动作"。{@code ToolSpec#write()} 就查这张表，
     * {@code ChatIntentTest} 再断言两边一致。
     */
    public static final java.util.Set<String> WRITES = java.util.Set.of(
            DEFINE_RULE, BUILD_MODEL, RUN, DRAFT_RULE, REGISTER_TABLE, IMPORT_FILE, SUBMIT_APPROVAL);

    /**
     * 工具跑完之后的"人话下一步"。以前直接拼在工具返回文本里（{@code "\n下一步：recon_list_diffs …"}），
     * 于是模型会把它当事实复述、会话还得专门擦掉（{@code ChatSpeech#stripAgentTail}）。
     * 现在它是结构化字段：给宿主和建议用，但不混进结果正文。
     */
    public static final java.util.Map<String, String> SUGGESTIONS = java.util.Map.of(
            RUN, "recon_list_diffs 看差异明细，recon_explain_job 生成归因",
            JOB_SUMMARY, "recon_explain_job 生成归因，recon_list_diffs 翻差异明细",
            DEFINE_RULE, "recon_trial_status 看试算结论（草稿要过试算 + 人工审批才生效）",
            DRAFT_RULE, "recon_trial_status 轮询起草与试算进度；审批只能由人做");

    /**
     * Agent 专属工具的注册顺序。
     *
     * <p>存在的理由只有一条：<b>让「目录里有几个」这个数字变成断言</b>。
     * 2026-10-26 之前这里少了 {@link #IMPORT_STATUS}、多了 {@link #PENDING_APPROVALS}（它不是 MCP 工具），
     * 于是「应该 6 个」的清单和「实际注册 6 个」的代码刚好对不上却谁也没发现 —— 名字在这里，
     * 谁多注册一个、谁少注册一个，{@code ToolCatalogTest} 立刻红。
     */
    public static final java.util.List<String> AGENT = java.util.List.of(
            DRAFT_RULE, TRIAL_STATUS, REGISTER_TABLE, IMPORT_FILE, IMPORT_STATUS, SUBMIT_APPROVAL);

    /** MCP 常驻基础工具的注册顺序（与 {@code tools/list} 输出一致，测试据此断言）。 */
    public static final java.util.List<String> BASE = java.util.List.of(
            LIST_RULES, LIST_JOBS, LIST_TABLES, DESCRIBE_TABLE, DEFINE_RULE, BUILD_MODEL, RUN,
            JOB_SUMMARY, LIST_DIFFS, EXPLAIN_DIFF, EXPLAIN_JOB, DIAGNOSE);
}
