package io.github.dolow.reconlens.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.agent.AgentGoal;
import io.github.dolow.reconlens.agent.TrialTaskRegistry;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.rule.ApprovalRelay;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.RuleRepository;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 能力的 MCP 工具：起草（{@code recon_draft_rule}）、试算查询（{@code recon_trial_status}）、
 * 动态注册表（{@code recon_register_table}）、文件导入（{@code recon_import_file}）、代办审批
 * （{@code recon_submit_approval}）共 5 个 —— 工具总数 9 → 14。
 *
 * <p><b>为什么单独一个类而不是塞进 {@code ReconMcpTools}</b>：{@code ReconMcpTools} 是常驻的
 * （Agent 关掉时对账照样要能跑），而这些工具依赖 {@code recon.agent.enabled=true} 才存在的 Bean。
 * 塞进去就得给它加 {@code ObjectProvider} 空判断 —— 那样"关掉 Agent 等于回到之前"这句话
 * 就变成了每个方法里的一次 if。
 *
 * <p><b>审批经 {@code recon_submit_approval} 转发（人是唯一决策者，Agent 不审批）</b>：这个工具
 * 只是把用户的决定转给 {@code RuleLifecycleService}，但<b>必须带上用户的一次性确认码</b>
 * （{@code confirmationCode}，由用户从 REST 领取，5 分钟过期、只能用一次）。
 * 没有码就发不出审批请求 —— 这条约束不靠提示词，靠数据库的条件 UPDATE。
 * {@code confirmedBy} 仍进审批审计：谁批准的、经谁的手，事后可追溯。未配置
 * {@code recon.review.api-key} 时用户领不到码，该通道等同 fail-closed。
 */
@Component
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class AgentMcpTools {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpTools.class);

    private final TrialTaskRegistry registry;
    private final AgentRunRepository runs;
    private final RuleRepository rules;
    private final io.github.dolow.reconlens.agent.TableRegistrar registrar;
    private final io.github.dolow.reconlens.agent.FileImporter importer;
    private final io.github.dolow.reconlens.agent.ImportTaskRegistry importTasks;
    private final ApprovalRelay lifecycle;
    private final ReconProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public AgentMcpTools(TrialTaskRegistry registry, AgentRunRepository runs, RuleRepository rules,
                         io.github.dolow.reconlens.agent.TableRegistrar registrar,
                         io.github.dolow.reconlens.agent.FileImporter importer,
                         io.github.dolow.reconlens.agent.ImportTaskRegistry importTasks,
                         ApprovalRelay lifecycle, ReconProperties properties) {
        this.registry = registry;
        this.runs = runs;
        this.rules = rules;
        this.registrar = registrar;
        this.importer = importer;
        this.importTasks = importTasks;
        this.lifecycle = lifecycle;
        this.properties = properties;
    }

    /** 供 {@code McpServerRunner} 合并用：Agent 关闭时它一个工具也不贡献。 */
    public List<McpServerFeatures.SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        specs.add(McpToolFactory.tool(jsonMapper, "recon_draft_rule",
                "把一句业务需求起草成一条对账规则：立即返回会话号，起草 + 闸门 1 校验 + 影子试算在后台进行，"
                        + "用 recon_trial_status 轮询结果。这条工具【不会】让规则生效 —— 生效需要用户明确同意后"
                        + "由 recon_submit_approval 完成审批。\n"
                        + "【成功关键】businessDescription 必须结构化，逐项写明（口语化描述极易被闸门拦截、浪费多轮重试）：\n"
                        + "1. 键：用什么字段对齐（如\"按 order_no 逐笔\"或\"按商号 merchant_no + 下单日期 DATE(order_time) 聚合\"）；\n"
                        + "2. 字段与类型：比对哪些字段、什么类型（如\"比对 settle_amount（DECIMAL）和 status（STRING）\"）；\n"
                        + "3. 差异类型：金额不符用什么 diffType（如 AMOUNT_MISMATCH）、单边缺失（MISSING_LEFT/RIGHT）；\n"
                        + "4. 容差：绝对值用 tolerance=0.01，百分比用 tolerancePercent（小数比率，0.001 表示 0.1%，"
                        + "要 0.001% 就写 0.00001）；\n"
                        + "5. 表名：两侧的数据表名。",
                """
                {"type":"object","properties":{
                  "businessDescription":{"type":"string","description":"业务需求描述，例如\\"按订单号逐笔比对账单和结算的金额，容差 0.01 元\\""},
                  "ruleCode":{"type":"string","description":"可选：期望的规则编码（改现有规则时必须传，否则视为新建）"},
                  "operator":{"type":"string","description":"可选：发起人标识，记入会话与草稿"}},                 "required":["businessDescription"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "runId":{"type":"integer","description":"会话号，用它轮询 recon_trial_status"},
                  "status":{"type":"string","description":"QUEUED"},
                  "ruleCode":{"type":"string"},
                  "message":{"type":"string"}},
                 "required":["runId","status","message"],"additionalProperties":true}
                """,
                args -> draftRule(McpToolFactory.text(args, "businessDescription", true),
                        McpToolFactory.text(args, "ruleCode", false),
                        McpToolFactory.text(args, "operator", false))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_trial_status",
                "查询 Agent 会话 / 草稿的试算进度与结论（逐项检查、覆盖等级、降级理由、可批性）。"
                        + "传 runId，或传 ruleCode + version。结论出来之后，生效仍需要人工审批。",
                """
                {"type":"object","properties":{
                  "runId":{"type":"integer","description":"recon_draft_rule 返回的会话号"},
                  "ruleCode":{"type":"string","description":"规则编码（与 version 一起用，可替代 runId）"},
                  "version":{"type":"integer","description":"规则版本"}},
                 "additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "runId":{"type":"integer"},
                  "status":{"type":"string","description":"QUEUED/RUNNING/SUCCESS/BLOCKED/FAILED，或已落库的会话状态"},
                  "inFlight":{"type":"boolean","description":"还在后台跑：此时没有试算结论"},
                  "ruleCode":{"type":"string"},
                  "version":{"type":"integer"},
                  "state":{"type":"string","description":"NotRun/Skipped/Trialed"},
                  "verdict":{"type":"string","description":"PASS/FAIL/INCONCLUSIVE"},
                  "coverage":{"type":"string","description":"FULL/SAMPLE_PARTIAL/FIELDS_ONLY/NONE"},
                  "reasons":{"type":"array","items":{"type":"string"}},
                  "approvalMode":{"type":"string","description":"NO_SIGNOFF_REQUIRED/NEED_SIGN/BLOCK_SIGNABLE/BLOCK_HARD/NOT_APPROVABLE"},
                  "checks":{"type":"object","additionalProperties":{"type":"string"}},
                  "notes":{"type":"array","items":{"type":"string"}},
                  "draftReason":{"type":"string"},
                  "message":{"type":"string"}},
                 "required":["status","inFlight","message"],"additionalProperties":true}
                """,
                args -> trialStatus(McpToolFactory.optionalInteger(args, "runId"),
                        McpToolFactory.text(args, "ruleCode", false),
                        McpToolFactory.optionalInteger(args, "version"))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_register_table",
                "把一张业务表注册进闸门 1 白名单（给数据结构不同的接入方用，不改配置、不重启）。"
                        + "两段式：action=preview 只读表结构与行数（不改任何状态），把结果展示给人；"
                        + "人确认后 action=confirm 才真正注册并立即生效，之后 recon_draft_rule 就能用这张表。",
                """
                {"type":"object","properties":{
                  "table":{"type":"string","description":"要注册的表名，可带 schema 前缀（如 billing.orders）"},
                  "action":{"type":"string","enum":["preview","confirm"],"description":"preview=只读预览（缺省）；confirm=人工确认后注册"},
                  "operator":{"type":"string","description":"可选：确认人标识，记入注册审计"}},
                 "required":["table"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "table":{"type":"string"},
                  "action":{"type":"string"},
                  "registered":{"type":"boolean","description":"confirm 时是否注册成功"},
                  "columns":{"type":"array","items":{"type":"string"}},
                  "rows":{"type":"integer","description":"预览时的行数（拿不到为空）"},
                  "message":{"type":"string"}},
                 "required":["table","action","message"],"additionalProperties":true}
                """,
                args -> registerTable(McpToolFactory.text(args, "table", true),
                        McpToolFactory.text(args, "action", false),
                        McpToolFactory.text(args, "operator", false))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_import_file",
                "把导入目录里的 CSV/Excel 文件导入成一张数据库表并注册进闸门 1 白名单"
                        + "（给只有原始数据文件、没有库表的接入方用）。文件必须放在服务端导入目录"
                        + "（recon.agent.file-import.dir，默认 ./data/import）内，只接受该目录内的相对路径。"
                        + "两段式：action=preview 只读解析（列名、推断的列类型、行数），把结果展示给人；"
                        + "人确认后 action=confirm（须传 table 指定目标表名）才建表灌数并注册。"
                        + "目标表已存在时拒绝 —— 导入绝不覆盖已有的表。",
                """
                {"type":"object","properties":{
                  "file":{"type":"string","description":"导入目录内的相对路径，如 orders.csv 或 2026/账单.xlsx"},
                  "action":{"type":"string","enum":["preview","confirm"],"description":"preview=只读解析（缺省）；confirm=人工确认后建表灌数"},
                  "table":{"type":"string","description":"confirm 必填：目标表名（[schema.]标识符）；已存在的表会被拒绝"},
                  "operator":{"type":"string","description":"可选：确认人标识，记入注册审计"},
                  "sheet":{"type":"string","description":"可选：Excel 的 Sheet 名或从 0 开始的序号（缺省第一张）；CSV 忽略"},
                  "async":{"type":"boolean","description":"confirm 时是否后台执行（大文件建议 true）；preview 忽略"}},
                 "required":["file"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "file":{"type":"string"},
                  "table":{"type":"string"},
                  "action":{"type":"string"},
                  "imported":{"type":"boolean","description":"confirm 时是否导入成功（async 时为 null）"},
                  "taskId":{"type":"integer","description":"async=true 时返回的后台任务号"},
                  "status":{"type":"string","description":"async=true 时的任务状态：QUEUED"},
                  "columns":{"type":"array","items":{"type":"string"},"description":"规范化后的列名（进 DDL 的）"},
                  "originalHeaders":{"type":"array","items":{"type":"string"},"description":"文件里的原始表头"},
                  "columnTypes":{"type":"array","items":{"type":"string"},"description":"推断的列类型"},
                  "rows":{"type":"integer","description":"数据行数"},
                  "message":{"type":"string"}},
                 "required":["file","action","message"],"additionalProperties":true}
                """,
                args -> importFile(McpToolFactory.text(args, "file", true),
                        McpToolFactory.text(args, "action", false),
                        McpToolFactory.text(args, "table", false),
                        McpToolFactory.text(args, "operator", false),
                        McpToolFactory.text(args, "sheet", false),
                        flag(args, "async"))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_import_status",
                "查询 recon_import_file(action=confirm, async=true) 的后台导入进度。"
                        + "状态：QUEUED / RUNNING / SUCCESS（rows 为导入行数）/ FAILED（error 为原因）。"
                        + "任务表在内存里，进程重启后旧任务号查不到 —— 那时直接查库确认表是否建成。",
                """
                {"type":"object","properties":{
                  "taskId":{"type":"integer","description":"recon_import_file 返回的任务号"}},
                 "required":["taskId"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "taskId":{"type":"integer"},
                  "status":{"type":"string","enum":["QUEUED","RUNNING","SUCCESS","FAILED","UNKNOWN"]},
                  "file":{"type":"string"},
                  "table":{"type":"string"},
                  "rows":{"type":"integer"},
                  "error":{"type":"string"},
                  "message":{"type":"string"}},
                 "required":["taskId","status","message"],"additionalProperties":true}
                """,
                args -> importStatus(McpToolFactory.integer(args, "taskId", 0L, true))));

        specs.add(McpToolFactory.tool(jsonMapper, "recon_submit_approval",
                "把一条已通过试算的规则草稿推过审批闸门 —— **只转发用户的决定，不自己审批**。\n"
                        + "【必须】先向用户展示试算结论（verdict/coverage/可批性）并请用户确认；"
                        + "用户确认后，用户会在 REST 上领取一串一次性确认码（confirmationCode，5 分钟过期、只能用一次），"
                        + "把这串码原样传给本工具。没有码就发不出审批请求 —— 你不能替用户确认，也不能编一个码。\n"
                        + "未配置 recon.review.api-key 时用户根本领不到码，本通道等同关闭（fail-closed）。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码"},
                  "version":{"type":"integer","description":"规则版本"},
                  "decision":{"type":"string","enum":["APPROVED","REJECTED"],"description":"用户明确表态的结果"},
                  "confirmedBy":{"type":"string","description":"做出决策的用户标识（必填，进审批审计）"},
                  "confirmationCode":{"type":"string","description":"用户从 REST 领取的一次性确认码（必填，8 位）"},
                  "note":{"type":"string","description":"可选：审批意见"}},
                 "required":["ruleCode","version","decision","confirmedBy","confirmationCode"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string"},
                  "version":{"type":"integer"},
                  "decision":{"type":"string"},
                  "submitted":{"type":"boolean","description":"是否完成审批提交"},
                  "confirmationConsumed":{"type":"boolean","description":"是否核销了用户的确认码"},
                  "lifecycle":{"type":"string","description":"提交后的生命周期状态（ACTIVE/REJECTED）"},
                  "message":{"type":"string"}},
                 "required":["ruleCode","version","decision","submitted","message"],"additionalProperties":true}
                """,
                args -> submitApproval(McpToolFactory.text(args, "ruleCode", true),
                        McpToolFactory.optionalInteger(args, "version"),
                        McpToolFactory.text(args, "decision", true),
                        McpToolFactory.text(args, "confirmedBy", true),
                        McpToolFactory.text(args, "note", false),
                        McpToolFactory.text(args, "confirmationCode", true))));

        return specs;
    }

    // ------------------------------------------------------------------

    /** 注册工具：preview 不改任何状态；confirm 只在人工确认后由调用方再次发起。 */
    McpToolFactory.ToolResult registerTable(String table, String action, String operator) {
        boolean confirm = "confirm".equalsIgnoreCase(action == null ? "" : action.trim());
        io.github.dolow.reconlens.agent.TableRegistrar.Preview p =
                confirm ? registrar.confirm(table, operator) : registrar.preview(table);

        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "table", p.table());
        McpToolFactory.put(structured, "action", confirm ? "confirm" : "preview");
        McpToolFactory.put(structured, "registered", confirm && p.error() == null);
        McpToolFactory.put(structured, "columns", p.columns());
        McpToolFactory.put(structured, "rows", p.rows());
        McpToolFactory.put(structured, "change", p.changeText());
        McpToolFactory.put(structured, "addedColumns", p.added());
        McpToolFactory.put(structured, "removedColumns", p.removed());

        String message;
        if (p.error() != null) {
            message = p.table() + "：" + p.error();
        } else if (confirm) {
            message = "表 " + p.table() + " 已注册进闸门 1 白名单（" + p.columns().size() + " 列，登记人 "
                    + (operator == null || operator.isBlank() ? "api" : operator.trim())
                    + "），立即生效。\n" + p.changeText()
                    + "。\n现在可以用 recon_draft_rule 针对这张表起草规则了。";
        } else {
            message = "表 " + p.table() + " 预览：" + p.columns().size() + " 列"
                    + (p.rows() != null ? "，约 " + p.rows() + " 行" : "")
                    + "。\n列清单：" + String.join(", ", p.columns())
                    + "\n" + p.changeText()
                    + "\n确认无误后再次调用本工具（action=confirm）完成注册 —— "
                    + "注册会放宽安全边界，必须先经用户确认。";
        }
        McpToolFactory.put(structured, "message", message);
        return McpToolFactory.ToolResult.of(message, structured);
    }

    /** 导入工具：preview 只读文件；confirm 建表灌数 + 注册白名单，只在人工确认后发起。 */
    McpToolFactory.ToolResult importFile(String file, String action, String table, String operator,
                                         String sheet, boolean async) {
        boolean confirm = "confirm".equalsIgnoreCase(action == null ? "" : action.trim());
        if (confirm && async) {
            long taskId = importTasks.submit(file, table, operator, sheet);
            Map<String, Object> structured = new LinkedHashMap<>();
            McpToolFactory.put(structured, "file", file);
            McpToolFactory.put(structured, "table", table);
            McpToolFactory.put(structured, "action", "confirm");
            McpToolFactory.put(structured, "taskId", taskId);
            McpToolFactory.put(structured, "status", "QUEUED");
            String message = "已排队导入（任务号 #" + taskId + "）：后台建表 + 灌数 + 注册白名单。"
                    + "用 recon_import_status(taskId=" + taskId + ") 轮询；62 万行 × 20 列约 26 秒，"
                    + "期间不要重复提交同一张表（表已存在会被拒绝）。";
            McpToolFactory.put(structured, "message", message);
            return McpToolFactory.ToolResult.of(message, structured);
        }
        io.github.dolow.reconlens.agent.FileImporter.ImportPreview p = confirm
                ? importer.confirm(file, table, operator, sheet)
                : importer.preview(file, sheet);

        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "file", p.file());
        McpToolFactory.put(structured, "table", p.table());
        McpToolFactory.put(structured, "action", confirm ? "confirm" : "preview");
        McpToolFactory.put(structured, "imported", confirm && p.error() == null);
        McpToolFactory.put(structured, "columns", p.columns());
        McpToolFactory.put(structured, "originalHeaders", p.originalHeaders());
        McpToolFactory.put(structured, "columnTypes", p.columnTypes());
        McpToolFactory.put(structured, "rows", p.rows());

        StringBuilder sb = new StringBuilder();
        if (p.error() != null) {
            sb.append(p.file()).append("：").append(p.error());
        } else if (confirm) {
            sb.append("文件 ").append(p.file()).append(" 已导入成表 ").append(p.table())
                    .append("（").append(p.rows()).append(" 行 × ").append(p.columns().size())
                    .append(" 列），并注册进闸门 1 白名单，立即生效。\n")
                    .append("列类型：");
            for (int i = 0; i < p.columns().size(); i++) {
                sb.append(p.columns().get(i)).append(' ').append(p.columnTypes().get(i));
                if (i < p.columns().size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("\n现在可以用 recon_draft_rule 针对这张表起草规则了。");
        } else {
            sb.append("文件 ").append(p.file()).append(" 预览：")
                    .append(p.rows()).append(" 行数据 × ").append(p.columns().size()).append(" 列。\n");
            for (int i = 0; i < p.columns().size(); i++) {
                sb.append("- ").append(p.columns().get(i)).append(' ').append(p.columnTypes().get(i));
                if (!p.columns().get(i).equals(p.originalHeaders().get(i))) {
                    sb.append("（原表头: ").append(p.originalHeaders().get(i)).append('）');
                }
                sb.append('\n');
            }
            sb.append("确认无误后再次调用本工具（action=confirm，并传 table 指定目标表名）完成导入 —— ")
                    .append("导入会建新表并放宽安全边界，必须先经用户确认。");
        }
        McpToolFactory.put(structured, "message", sb.toString());
        return McpToolFactory.ToolResult.of(sb.toString(), structured);
    }

    /** 查后台导入进度（{@code recon_import_file(action=confirm, async=true)} 的任务号）。 */
    McpToolFactory.ToolResult importStatus(long taskId) {
        Optional<io.github.dolow.reconlens.agent.ImportTaskRegistry.Task> found = importTasks.find(taskId);
        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "taskId", taskId);
        if (found.isEmpty()) {
            String message = "查不到导入任务 #" + taskId + "：任务表在内存里，进程重启后旧任务号会失效。"
                    + "表建没建成请直接查数据库（recon_registered_table 或 SELECT COUNT(*) FROM 表名）。";
            McpToolFactory.put(structured, "status", "UNKNOWN");
            McpToolFactory.put(structured, "message", message);
            return McpToolFactory.ToolResult.of(message, structured);
        }
        var task = found.get();
        McpToolFactory.put(structured, "status", task.status().name());
        McpToolFactory.put(structured, "file", task.file());
        McpToolFactory.put(structured, "table", task.table());
        McpToolFactory.put(structured, "rows", task.rows());
        McpToolFactory.put(structured, "error", task.error());
        // 这里刻意不写 switch(status)：枚举 switch 会生成 AgentMcpTools$N 匿名类，
        // ArchUnit 的"非 agent 包不得依赖 agent 包"会把它当成新类（类名不再等于 AgentMcpTools）而报错。
        String message = importStatusMessage(taskId, task);
        McpToolFactory.put(structured, "message", message);
        return McpToolFactory.ToolResult.of(message, structured);
    }

    private static String importStatusMessage(
            long taskId, io.github.dolow.reconlens.agent.ImportTaskRegistry.Task task) {
        return switch (task.status().name()) {
            case "QUEUED" -> "导入任务 #" + taskId + " 排队中（同一时刻只跑一个导入），稍后再查。";
            case "RUNNING" -> "导入任务 #" + taskId + " 进行中：" + task.file() + " → " + task.table()
                    + "。62 万行约 26 秒，别重复提交。";
            case "SUCCESS" -> "导入任务 #" + taskId + " 完成：" + task.file() + " → " + task.table()
                    + "（" + task.rows() + " 行），已注册进闸门 1 白名单。";
            case "FAILED" -> "导入任务 #" + taskId + " 失败：" + task.error()
                    + "（表是否残留：失败时会尝试 DROP；若仍存在请手动清理后再试）";
            default -> "导入任务 #" + taskId + " 状态 " + task.status().name() + "。";
        };
    }

    /** MCP 客户端可能把布尔参数传成 true 或 "true"，两种都认。 */
    private static boolean flag(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        return raw instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(raw));
    }

    McpToolFactory.ToolResult draftRule(String businessDescription, String ruleCode, String operator) {
        AgentGoal goal = new AgentGoal(businessDescription, ruleCode, null, null, java.util.Set.of(),
                AgentGoal.GoalTrigger.API);
        long runId = registry.submit(goal, operator);

        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "runId", runId);
        McpToolFactory.put(structured, "status", TrialTaskRegistry.Status.QUEUED.name());
        McpToolFactory.put(structured, "ruleCode", ruleCode);
        String message = "已创建 Agent 会话 #" + runId + "，起草与影子试算在后台进行"
                + "（一次试算约 1 分钟，期间不要重复提交同一份需求）。\n"
                + "下一步：recon_trial_status runId=" + runId + " 轮询进度。\n"
                + "注意：通过试算 ≠ 生效。试算结论出来后，先把 verdict/coverage/可批性讲给用户听；"
                + "用户明确表态\"通过\"或\"拒绝\"后，请用户去 REST 领取一次性确认码"
                + "（POST /api/rule-reviews/" + ruleCode + "/版本/confirmation），"
                + "再带 confirmationCode 调 recon_submit_approval 完成审批 —— 你不能自己批。";
        McpToolFactory.put(structured, "message", message);
        return McpToolFactory.ToolResult.of(message, structured);
    }

    /**
     * 审批代办：把用户的决定原样转发给 {@code RuleLifecycleService}。
     *
     * <p><b>Agent 只转发，不审批</b>。所以这个方法必须收到 {@code confirmationCode} ——
     * 用户从 REST（{@code POST /api/rule-reviews/{code}/{version}/confirmation}）领取的一次性确认码。
     * 服务端用条件 UPDATE 消费它，模型编不出码，也就无法自批；只靠 {@code confirmedBy} 自述"用户已同意"
     * 曾经能 47ms 批掉一条规则，这正是本参数存在的理由。
     */
    McpToolFactory.ToolResult submitApproval(String ruleCode, Integer version, String decision,
                                             String confirmedBy, String note, String confirmationCode) {
        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "ruleCode", ruleCode);
        McpToolFactory.put(structured, "version", version);
        McpToolFactory.put(structured, "decision", decision);

        if (version == null) {
            throw new IllegalArgumentException("version 必填（审批对象是具体版本，不是规则）");
        }
        if (confirmationCode == null || confirmationCode.isBlank()) {
            throw new IllegalArgumentException("confirmationCode 必填：审批必须由用户从 REST 领取一次性确认码后转给你"
                    + "（POST /api/rule-reviews/" + ruleCode + "/" + version + "/confirmation），"
                    + "你不能替用户确认，也不能自己编一个码");
        }
        boolean approved = "APPROVED".equalsIgnoreCase(decision) || "APPROVE".equalsIgnoreCase(decision);
        if (!approved && !"REJECTED".equalsIgnoreCase(decision) && !"REJECT".equalsIgnoreCase(decision)) {
            throw new IllegalArgumentException("decision 只能是 APPROVED 或 REJECTED，当前: " + decision);
        }
        if (confirmedBy == null || confirmedBy.isBlank()) {
            throw new IllegalArgumentException("confirmedBy 必填：审计链上没有决策人的审批等于没有审批");
        }

        // 试算状态前置检查：给 agent 一句能读懂的拒绝理由，而不是让 lifecycle 的异常往上传
        java.util.Optional<RuleRepository.TrialRecord> record = rules.findTrial(ruleCode, version);
        if (record.isEmpty()) {
            throw new IllegalStateException("规则 " + ruleCode + " v" + version + " 还没有试算结论，不能提交审批"
                    + "（先等 recon_trial_status 出结论）");
        }
        ApprovalMode mode = record.get().state().approval();
        if (mode == ApprovalMode.BLOCK_HARD || mode == ApprovalMode.NOT_APPROVABLE) {
            throw new IllegalStateException("可批性为 " + mode + "，任何身份都不能提交审批："
                    + describeMode(mode));
        }

        String operator = confirmedBy.trim();
        lifecycle.confirmApproval(ruleCode, version, decision, operator, confirmationCode, note);
        log.info("审批代办完成（凭用户确认码）：{} v{} decision={} confirmedBy={}",
                ruleCode, version, decision, operator);

        String message = "规则 " + ruleCode + " v" + version + " 审批完成（决策人 " + operator
                + "，已核销确认码）："
                + (approved ? "已批准，状态 ACTIVE，可以用 recon_run 实跑" : "已拒绝，状态 REJECTED");
        McpToolFactory.put(structured, "submitted", true);
        McpToolFactory.put(structured, "confirmationConsumed", true);
        McpToolFactory.put(structured, "lifecycle", approved ? "ACTIVE" : "REJECTED");
        McpToolFactory.put(structured, "message", message);
        return McpToolFactory.ToolResult.of(message, structured);
    }

    McpToolFactory.ToolResult trialStatus(Integer runId, String ruleCode, Integer version) {
        TrialTaskRegistry.Task task = null;
        AgentRunRepository.AgentRun run = null;
        String code = ruleCode;
        Integer resolvedVersion = version;

        if (runId != null) {
            task = registry.find(runId).orElse(null);
            if (task == null) {
                run = runs.findRun(runId)
                        .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + runId));
                code = code != null ? code : run.ruleCode();
                resolvedVersion = resolvedVersion != null ? resolvedVersion : run.draftVersion();
            } else {
                code = code != null ? code : task.code();
                resolvedVersion = resolvedVersion != null ? resolvedVersion : task.version();
            }
        } else if (code == null || resolvedVersion == null) {
            throw new IllegalArgumentException("需要 runId，或者 ruleCode + version 一起传");
        }

        boolean inFlight = task != null && task.status().inFlight();
        String status = task != null ? task.status().name()
                : run != null ? run.status()
                : registry.findByDraft(code, resolvedVersion).map(t -> t.status().name()).orElse("UNKNOWN");

        Map<String, Object> structured = new LinkedHashMap<>();
        McpToolFactory.put(structured, "runId", runId);
        McpToolFactory.put(structured, "status", status);
        McpToolFactory.put(structured, "inFlight", inFlight);
        McpToolFactory.put(structured, "ruleCode", code);
        McpToolFactory.put(structured, "version", resolvedVersion);

        StringBuilder sb = new StringBuilder();
        sb.append("会话 #").append(runId == null ? "-" : runId).append("｜").append(status);
        if (code != null) {
            sb.append("｜规则 ").append(code);
            if (resolvedVersion != null) {
                sb.append(" v").append(resolvedVersion);
            }
        }
        sb.append('\n');

        if (inFlight) {
            String message = "仍在后台执行（" + (task == null ? status : task.status().name()) + "）："
                    + "起草可能已过闸门 1，试算还在跑。稍后再查，不要重复提交。";
            McpToolFactory.put(structured, "message", message);
            sb.append(message);
            return McpToolFactory.ToolResult.of(sb.toString(), structured);
        }

        String completionMessage = task != null ? task.message() : null;
        if (task != null && (task.status() == TrialTaskRegistry.Status.BLOCKED
                || task.status() == TrialTaskRegistry.Status.FAILED)) {
            String message = completionMessage == null ? "会话未产出草稿" : completionMessage;
            McpToolFactory.put(structured, "message", message);
            sb.append(message);
            return McpToolFactory.ToolResult.of(sb.toString(), structured);
        }

        if (code == null || resolvedVersion == null) {
            String message = "会话已结束，但没有产出草稿："
                    + (completionMessage == null ? "（无更多信息）" : completionMessage);
            McpToolFactory.put(structured, "message", message);
            sb.append(message);
            return McpToolFactory.ToolResult.of(sb.toString(), structured);
        }

        appendTrial(sb, structured, code, resolvedVersion, completionMessage);
        return McpToolFactory.ToolResult.of(sb.toString(), structured);
    }

    /** 从 {@code recon_rule.trial_*} 读结论并摊平成"人话 + 结构化"。 */
    private void appendTrial(StringBuilder sb, Map<String, Object> structured, String code, int version,
                             String completionMessage) {
        Optional<RuleRepository.TrialRecord> record = rules.findTrial(code, version);
        if (record.isEmpty()) {
            String message = "还没有试算结论（草稿可能刚落库）。稍后再查。";
            McpToolFactory.put(structured, "state", TrialState.NotRun.class.getSimpleName());
            McpToolFactory.put(structured, "message", message);
            sb.append(message);
            return;
        }
        TrialState state = record.get().state();
        McpToolFactory.put(structured, "state", state.getClass().getSimpleName());
        ApprovalMode mode = state.approval();
        McpToolFactory.put(structured, "approvalMode", mode.name());

        if (state instanceof TrialState.Trialed trialed) {
            McpToolFactory.put(structured, "verdict", trialed.verdict().name());
            McpToolFactory.put(structured, "coverage", trialed.coverage().name());
            McpToolFactory.put(structured, "reasons", trialed.reasons().stream().map(CoverageReason::name).toList());
            sb.append("试算：").append(trialed.verdict()).append(" × ").append(trialed.coverage());
            if (!trialed.reasons().isEmpty()) {
                sb.append("（").append(String.join("，",
                        trialed.reasons().stream().map(CoverageReason::name).toList())).append("）");
            }
            sb.append('\n');
        } else if (state instanceof TrialState.Skipped skipped) {
            McpToolFactory.put(structured, "skipReason", skipped.skipReason());
            sb.append("跳过试算：").append(skipped.skipReason()).append('\n');
        }

        sb.append("可批性：").append(mode.name()).append(describeMode(mode)).append('\n');

        // 报告里的逐项检查与备注才是"凭什么"—— 只给三个枚举值等于让审核人盲签
        Map<String, Object> report = parseReport(record.get().report());
        Object checks = report.get("checks");
        if (checks instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, String> flat = new LinkedHashMap<>();
            map.forEach((key, value) -> flat.put(String.valueOf(key), String.valueOf(value)));
            McpToolFactory.put(structured, "checks", flat);
            sb.append("检查项：").append(flat.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "；" + b).orElse("")).append('\n');
        }
        Object notes = report.get("notes");
        if (notes instanceof List<?> list && !list.isEmpty()) {
            List<String> flat = list.stream().map(String::valueOf).toList();
            McpToolFactory.put(structured, "notes", flat);
            sb.append("备注：").append(String.join("；", flat)).append('\n');
        }

        rules.findByVersion(code, version).ifPresent(row -> {
            if (row.draftReason() != null && !row.draftReason().isBlank()) {
                McpToolFactory.put(structured, "draftReason", row.draftReason());
                sb.append("起草说明：").append(row.draftReason()).append('\n');
            }
        });
        // outputSchema 把 message 声明成了必填：按 ruleCode + version 查询时没有会话消息，
        // 少了这一句客户端会直接报 "未找到所需属性 message"（实测踩到过），所以这里兜底。
        String message = completionMessage != null ? completionMessage : "试算结论见上；生效仍需人工审批";
        McpToolFactory.put(structured, "message", message);
        sb.append(message).append('\n');
        if (mode != ApprovalMode.NO_SIGNOFF_REQUIRED) {
            sb.append("要生效：先把上述结论讲给用户听；用户明确同意后，由用户领取确认码"
                    + "（POST /api/rule-reviews/" + code + "/" + version + "/confirmation），"
                    + "再调 recon_submit_approval（ruleCode=").append(code).append(", version=").append(version)
                    .append(", decision=APPROVED, confirmedBy=用户标识, confirmationCode=用户给你的码）");
        } else {
            sb.append("该结论不需要额外签字，但仍**要用户点确认**：请用户领取确认码"
                    + "（POST /api/rule-reviews/" + code + "/" + version + "/confirmation），"
                    + "再调 recon_submit_approval（ruleCode=").append(code).append(", version=").append(version)
                    .append(", decision=APPROVED, confirmedBy=用户标识, confirmationCode=用户给你的码）");
        }
    }

    private static String describeMode(ApprovalMode mode) {
        return switch (mode) {
            case NO_SIGNOFF_REQUIRED -> "（不需要额外签字，但仍需用户确认）";
            case NEED_SIGN -> "（样本不完整，需签字）";
            case BLOCK_SIGNABLE -> "（没验成，需人签字接受降级验证）";
            case BLOCK_HARD -> "（硬拦：任何身份都不能批准）";
            case NOT_APPROVABLE -> "（还没跑试算）";
        };
    }

    private Map<String, Object> parseReport(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(reportJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("试算报告不是合法 JSON，按无报告处理：{}", e.toString());
            return Map.of();
        }
    }
}
