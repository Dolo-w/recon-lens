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
    public static final String SUBMIT_APPROVAL = "recon_submit_approval";
    public static final String PENDING_APPROVALS = "recon_list_approvals";

    // ---- 会话内组合出来的"虚拟工具"：不是 MCP 工具，但会话会把它记进工具轨迹 ----
    public static final String RECALL = "recall";

    /**
     * Agent 专属工具的注册顺序。
     *
     * <p>存在的理由只有一条：<b>让「目录里有 12 个」和「宿主看到 18 个」这个差值变成断言</b>。
     * 这 6 个的实现还在 {@code mcp.AgentMcpTools}（受 {@code recon.agent.enabled} 控制），
     * 目录里暂时没有它们的 Schema；但名字在这里，谁多注册一个、谁少注册一个，测试立刻红。
     */
    public static final java.util.List<String> AGENT = java.util.List.of(
            DRAFT_RULE, TRIAL_STATUS, REGISTER_TABLE, IMPORT_FILE, SUBMIT_APPROVAL, PENDING_APPROVALS);

    /** MCP 常驻基础工具的注册顺序（与 {@code tools/list} 输出一致，测试据此断言）。 */
    public static final java.util.List<String> BASE = java.util.List.of(
            LIST_RULES, LIST_JOBS, LIST_TABLES, DESCRIBE_TABLE, DEFINE_RULE, BUILD_MODEL, RUN,
            JOB_SUMMARY, LIST_DIFFS, EXPLAIN_DIFF, EXPLAIN_JOB, DIAGNOSE);
}
