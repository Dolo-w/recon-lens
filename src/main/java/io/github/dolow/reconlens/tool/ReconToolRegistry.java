package io.github.dolow.reconlens.tool;

import io.github.dolow.reconlens.mcp.ReconMcpTools;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.store.ReconRepository;
import io.github.dolow.reconlens.store.RegisteredTableRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基础工具的<b>唯一</b>实现与目录。
 *
 * <p>这里的每个 handler 都只是把参数翻成 {@link ReconMcpTools} 的一次调用——
 * 真正的判断（白名单、闸门、幂等、分页）留在原来的地方，没有搬到这里，也没有复制一份。
 * 这一层存在的理由只有一个：<b>名字、描述、Schema 与实现只有一处</b>，
 * 三个入口都从这里拿，谁也别再手抄一遍。
 *
 * <p>不挂 {@code @ConditionalOnProperty}：基础工具在 web 与 stdio 两种形态下都该存在，
 * 与 {@code recon.agent.enabled} 无关（Agent 专属工具在 {@code mcp.AgentMcpTools}）。
 */
@Component
public class ReconToolRegistry implements ToolRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReconToolRegistry.class);

    private final ReconMcpTools tools;
    private final ObjectProvider<RegisteredTableRepository> tables;
    private final RuleCatalog catalog;
    private final ObjectProvider<TableAllowList> allowList;
    private final io.github.dolow.reconlens.store.TableInspector inspector;
    private final ReconRepository repository;
    private final List<ToolSpec> specs;

    /**
     * {@code tables} 用 {@link ObjectProvider} 而不是直接注入：表注册仓储的 Bean 定义在
     * {@code agent.AgentConfiguration} 里、跟着 {@code recon.agent.enabled} 开关，
     * 而这套基础工具在不带 Agent 的 profile 里也必须存在。用 ObjectProvider 可以让"没有那张表"
     * 变成一句人话，而不是启动时 NoSuchBeanDefinition。
     */
    public ReconToolRegistry(ReconMcpTools tools, ObjectProvider<RegisteredTableRepository> tables,
                             RuleCatalog catalog, ObjectProvider<TableAllowList> allowList,
                             io.github.dolow.reconlens.store.TableInspector inspector,
                             ReconRepository repository) {
        this.tools = tools;
        this.tables = tables;
        this.catalog = catalog;
        this.allowList = allowList;
        this.inspector = inspector;
        this.repository = repository;
        this.specs = List.of(
                listRules(), listJobs(), listTables(), describeTable(), defineRule(), buildModel(), run(),
                jobSummary(), listDiffs(), explainDiff(), explainJob(), diagnose());
    }

    @Override
    public List<ToolSpec> specs() {
        return specs;
    }

    @Override
    public Optional<ToolSpec> find(String name) {
        return specs.stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    // ------------------------------------------------------------------ 目录

    private ToolSpec listRules() {
        return new ToolSpec(ToolNames.LIST_RULES,
                "列出规则库里的对账规则（版本、两侧数据源、对账维度、比对字段、分片与重试配置）。",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                null,
                a -> ToolOutcome.text(tools.listRules()));
    }

    /**
     * 执行历史：哪些规则真的跑过。
     *
     * <p><b>为什么必须和 {@link ToolNames#LIST_RULES} 分开</b>：规则目录回答"库里有什么"，
     * 这个问题回答"实际跑过什么"。两者在演示库里看起来一样（都只有那三条），
     * 但在生产上差别很大——规则库有 40 条、真正在跑的只有 6 条，答错了会把"没跑过的规则"
     * 当成"跑过的"，用户接下来那个"为什么昨天没有差异"就问错了对象。
     *
     * <p>只读 {@code kind='PROD'}：试算任务的样本是被裁剪过的，混进来会让"跑过几次"虚高。
     */
    private ToolSpec listJobs() {
        return new ToolSpec(ToolNames.LIST_JOBS,
                "列出最近的生产对账任务（按 id 倒序），并按规则汇总「哪条规则跑过几次、最近一次什么时候、差异多少」。"
                        + "问「之前执行过哪些规则」「最近跑过什么」时用它，不要用 recon_list_rules（那只答规则目录）。",
                """
                        {"type":"object","properties":{"limit":{"type":"integer","description":"返回多少条任务，默认 10，最多 50"}},"additionalProperties":false}
                        """.strip(),
                """
                        {"type":"object","properties":{"jobCount":{"type":"integer"},"jobs":{"type":"array","items":{"type":"object","properties":{"jobId":{"type":"integer"},"ruleCode":{"type":"string"},"status":{"type":"string"},"diffRows":{"type":"integer"},"createdAt":{"type":"string"}}}},"rules":{"type":"array","items":{"type":"object","properties":{"ruleCode":{"type":"string"},"runs":{"type":"integer"},"lastJobId":{"type":"integer"},"lastStatus":{"type":"string"},"lastDiffRows":{"type":"integer"},"lastAt":{"type":"string"}}}}}}
                        """.strip(),
                args -> {
                    int limit = ToolArgs.optionalInteger(args, "limit", 10);
                    limit = Math.max(1, Math.min(limit, 50));
                    List<ReconJob> jobs = repository.listJobs(limit);
                    String text = renderJobs(jobs);
                    long count = jobs.size();
                    Map<String, Object> view = new java.util.LinkedHashMap<>();
                    view.put("jobCount", count);
                    view.put("jobs", jobs.stream().map(job -> Map.of(
                            "jobId", job.id(),
                            "ruleCode", job.ruleCode(),
                            "status", job.status() == null ? "" : job.status().name(),
                            "diffRows", job.diffRows(),
                            "createdAt", stamp(job.createdAt()))).toList());
                    view.put("rules", summarizeByRule(jobs));
                    return ToolOutcome.of(text, view);
                });
    }

    /**
     * 把任务列表压成"人问的那句话"。
     *
     * <p>回答分两段：**逐条**（最近几次，看得出时间与结果）+ **按规则汇总**（跑过几次、最近一次）。
     * 只给逐条，"哪些规则"还得自己在长列表里做归并；只给汇总，用户想确认"昨天那次"就无从下手。
     */
    private static String renderJobs(List<ReconJob> jobs) {
        if (jobs.isEmpty()) {
            return "还没有跑过任何生产对账任务（试算任务不算）。跑一次之后这里会有记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近 ").append(jobs.size()).append(" 次生产对账任务（按 id 倒序，试算不在此列）：\n");
        for (ReconJob job : jobs) {
            sb.append("  #").append(job.id()).append(' ').append(job.ruleCode())
                    .append(" · ").append(job.status() == null ? "-" : job.status().name())
                    .append(" · 差异 ").append(job.diffRows()).append('/').append(job.matchedRows())
                    .append(" · ").append(stamp(job.createdAt())).append('\n');
        }
        sb.append("\n执行过的规则（按最近一次）：\n");
        for (Map<String, Object> rule : summarizeByRule(jobs)) {
            sb.append("  - ").append(rule.get("ruleCode")).append("：").append(rule.get("runs")).append(" 次，最近 #")
                    .append(rule.get("lastJobId")).append("（").append(rule.get("lastStatus"))
                    .append("，差异 ").append(rule.get("lastDiffRows")).append("）")
                    .append(" · ").append(rule.get("lastAt")).append('\n');
        }
        sb.append("\n规则库里还有哪些从没跑过、配置长什么样，用 recon_list_rules。");
        return sb.toString();
    }

    private static List<Map<String, Object>> summarizeByRule(List<ReconJob> jobs) {
        Map<String, List<ReconJob>> grouped = new java.util.LinkedHashMap<>();
        for (ReconJob job : jobs) {
            grouped.computeIfAbsent(job.ruleCode(), key -> new java.util.ArrayList<>()).add(job);
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        grouped.forEach((code, list) -> {
            ReconJob latest = list.get(0);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("ruleCode", code);
            row.put("runs", list.size());
            row.put("lastJobId", latest.id());
            row.put("lastStatus", latest.status() == null ? "-" : latest.status().name());
            row.put("lastDiffRows", latest.diffRows());
            row.put("lastAt", stamp(latest.createdAt()));
            rows.add(row);
        });
        return rows;
    }

    private static String stamp(java.time.Instant instant) {
        if (instant == null) {
            return "-";
        }
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant);
    }

    private ToolSpec listTables() {
        return new ToolSpec(ToolNames.LIST_TABLES,
                "列出已经注册进系统（也就是过了闸门 1 白名单）的数据表：列、行数、结构哈希与登记人。"
                        + "对账规则里的 left/right 只能引用这里或配置白名单里的表；"
                        + "行数与结构哈希是注册那一刻的快照，用来判断「这份数据换过没有」。",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "tables":{"type":"array","items":{"type":"object","properties":{
                      "table":{"type":"string"},"columns":{"type":"array","items":{"type":"string"}},
                      "rowCount":{"type":"integer"},"schemaHash":{"type":"string"},
                      "registeredBy":{"type":"string"}},
                      "required":["table"],"additionalProperties":true}}},
                 "required":["tables"],"additionalProperties":true}
                """,
                a -> {
                    RegisteredTableRepository repo = tables.getIfAvailable();
                    var registered = repo == null ? java.util.List.<RegisteredTableRepository.Registration>of() : repo.list();

                    // 规则正在用的表：这才是"现在能拿什么对账"的第二种答案，
                    // 而且它不会因为白名单里多登记了一张暂时没用的表而变长。
                    java.util.LinkedHashMap<String, String> used = new java.util.LinkedHashMap<>();
                    for (var spec : catalog.all()) {
                        for (var side : java.util.List.of(spec.left(), spec.right())) {
                            if (side.customSql()) {
                                used.putIfAbsent("（自定义 SQL）", "规则 " + spec.code());
                            } else if (side.table() != null) {
                                used.putIfAbsent(side.table(), "规则 " + spec.code());
                            }
                        }
                    }

                    StringBuilder sb = new StringBuilder("这个实例能对账的数据，分两个来源看：\n");

                    sb.append("\n【规则正在用的表】");
                    if (used.isEmpty()) {
                        sb.append("\n  没有生效规则，或者规则全部走自定义 SQL。\n");
                    } else {
                        sb.append("\n");
                        // 行数也带上：问"数据有多少行"的人要的就是这个数，
                        // 快照优先、没有才现算（TableInspector 里带超时），不为了报个数把库拖住。
                        used.forEach((table, rule) -> {
                            sb.append("  · ").append(table);
                            String count = rowCountText(table);
                            if (count != null) {
                                sb.append("　行数 ").append(count);
                            }
                            sb.append("　（").append(rule).append("）\n");
                        });
                    }

                    sb.append("\n【通过对话注册过的表】");
                    if (repo == null) {
                        sb.append("\n  表注册只在 Agent 模式可用（--recon.agent.enabled=true）。");
                    } else if (registered.isEmpty()) {
                        sb.append("\n  还没有注册过表。注册 = 把一张表放进闸门 1 白名单，")
                                .append("并记下列快照与那一刻的行数。");
                    } else {
                        sb.append("\n");
                        for (var r : registered) {
                            sb.append("  · ").append(r.tableName())
                                    .append("　列 ").append(r.columns().size()).append(" 个")
                                    .append("，行数 ").append(r.rowCount() == null ? "未知" : r.rowCount());
                            if (r.schemaHash() != null && r.schemaHash().length() >= 8) {
                                sb.append("，结构哈希 ").append(r.schemaHash(), 0, 8);
                            }
                            if (r.registeredBy() != null) {
                                sb.append("，登记人 ").append(r.registeredBy());
                            }
                            sb.append("\n");
                        }
                    }

                    sb.append("\n闸门 1 认的表 = 配置文件 recon.agent.schema.tables ∪ 已注册表（取并集，且是 fail-closed：")
                            .append("两边都没有的表一律拒绝）。配置那一半要改 yml 并重启，会话里改不了。");
                    return ToolOutcome.text(sb.toString());
                });
    }

    /**
     * 看一张表的结构：列名/类型/可空/长度、主键、行数。
     *
     * <p>为什么单独一个工具而不是塞进 {@code recon_list_tables}：那个回答"有哪些表"，
     * 一次能回十几张；这个回答"这张表长什么样"，一张就够一屏。混在一起会让两边的输出
     * 都变成"半屏有用半屏无关"。
     *
     * <p>为什么先看白名单：表结构本身就是信息（列名能暴露业务甚至个人信息）。
     * 不在闸门 1 白名单里的表，这里连"存不存在"都不回答 —— 和会话接口的 404 口径一致。
     */
    private ToolSpec describeTable() {
        return new ToolSpec(ToolNames.DESCRIBE_TABLE,
                "看一张表的结构：每一列的名字、类型、可空、长度，主键，以及行数（优先用注册快照，缺失才现算）。"
                        + "起草规则之前先看字段，比先猜维度再试错快。"
                        + "只回结构，不回数据行 —— 样例值另有开关，且默认脱敏。",
                """
                {"type":"object","properties":{
                  "table":{"type":"string","description":"表名（可带 schema 前缀）；必须是闸门 1 白名单里的表"},
                  "maxColumns":{"type":"integer","description":"最多返回多少列，默认 80；宽表会按这个截断并标记 truncated"},
                  "sample":{"type":"boolean","description":"是否返回样例值（默认 false）。开启后会在有限窗口上算空值率/不同值个数，样例默认脱敏"},
                  "window":{"type":"integer","description":"采样窗口行数，默认 500，上限 2000；所有比例都是这个窗口上的估算"},
                  "advice":{"type":"boolean","description":"是否给出起草建议（维度候选 / 比对字段候选 / 最小规则骨架），默认 false"},
                  "against":{"type":"string","description":"另一侧表名，给了就求同名数值列的交集，骨架里的 right.table 也会填上"}},
                 "required":["table"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "table":{"type":"string"},
                  "rowCount":{"type":["integer","null"]},
                  "rowSource":{"type":"string","description":"REGISTERED=注册快照 / LIVE=现算 / UNKNOWN=没取到"},
                  "truncated":{"type":"boolean"},
                  "primaryKey":{"type":"array","items":{"type":"string"}},
                  "columns":{"type":"array","items":{"type":"object","properties":{
                      "name":{"type":"string"},"type":{"type":"string"},"nullable":{"type":"boolean"},
                      "size":{"type":["integer","null"]},"scale":{"type":["integer","null"]},
                      "primaryKey":{"type":"boolean"}},"required":["name"],"additionalProperties":true}}},
                 "required":["table","columns"],"additionalProperties":true}
                """,
                a -> {
                    String table = ToolArgs.text(a, "table", true).trim();
                    TableAllowList list = allowList.getIfAvailable();
                    if (list == null) {
                        return ToolOutcome.text("看表结构只在 Agent 模式可用（白名单由 recon.agent.schema.tables 决定，"
                                + "当前 --recon.agent.enabled=false）。");
                    }
                    if (!list.allows(table)) {
                        return ToolOutcome.text("表 " + table + " 不在白名单里，不能看。"
                                + "白名单 = 配置 recon.agent.schema.tables ∪ 已注册表（fail-closed，两边都没有的一律拒绝）。"
                                + "要接入新表，先用 recon_register_table 注册（需人工确认），或者改配置并重启。");
                    }
                    int maxColumns = (int) ToolArgs.integer(a, "maxColumns", io.github.dolow.reconlens.store.TableInspector.DEFAULT_MAX_COLUMNS, false);
                    boolean sample = Boolean.TRUE.equals(a.get("sample"));
                    int window = (int) ToolArgs.integer(a, "window", io.github.dolow.reconlens.store.TableInspector.DEFAULT_WINDOW, false);
                    io.github.dolow.reconlens.store.TableInspector.Detail detail;
                    io.github.dolow.reconlens.store.TableInspector.Profile profile = null;
                    try {
                        detail = inspector.describe(table, maxColumns);
                        if (sample) {
                            // 采样单独一次查询：不加 sample 时一个数据行都不读，这是"看结构"的承诺
                            profile = inspector.profile(table, window, 3);
                        }
                    } catch (IllegalArgumentException e) {
                        return ToolOutcome.text("看不了这张表：" + e.getMessage());
                    }
                    boolean wantAdvice = Boolean.TRUE.equals(a.get("advice"));
                    Object againstRaw = a.get("against");
                    String against = againstRaw == null ? null : String.valueOf(againstRaw).trim();
                    io.github.dolow.reconlens.store.TableInspector.Advice advice = null;
                    if (wantAdvice) {
                        try {
                            advice = inspector.advice(table, against);
                        } catch (IllegalArgumentException e) {
                            // 建议算不出来不该让"看结构"一起失败：结构已经拿到了
                            log.warn("起草建议生成失败（table={}）：{}", table, e.getMessage());
                        }
                    }
                    Map<String, Object> structured = structuredDetail(detail);
                    if (profile != null) {
                        structured.put("profile", structuredProfile(profile));
                    }
                    if (advice != null) {
                        structured.put("advice", structuredAdvice(advice));
                    }
                    return ToolOutcome.of(renderDetail(detail) + renderProfile(profile) + renderAdvice(advice), structured);
                });
    }

    /**
     * 一张表的行数（给列表用）。取不到就返回 null —— 列表里宁可少一列，也不要一个"0"假装是真的。
     *
     * <p>表名来自规则 / 注册表，都已过闸门；这里只做展示，不做权限判断。
     */
    private String rowCountText(String table) {
        if (table == null || table.startsWith("（")) {
            return null;
        }
        try {
            io.github.dolow.reconlens.store.TableInspector.Detail detail =
                    inspector.describe(table, 1);
            if (detail.rowCount() == null) {
                return "未取到";
            }
            return String.valueOf(detail.rowCount());
        } catch (RuntimeException e) {
            log.debug("列表里取表 {} 的行数失败：{}", table, e.getMessage());
            return null;
        }
    }

    /** 人看的排版：宽表按名字/类型对齐，主键标在行尾。 */
    private static String renderDetail(io.github.dolow.reconlens.store.TableInspector.Detail d) {
        int nameWidth = Math.min(28, d.columns().stream().mapToInt(c -> c.name().length()).max().orElse(8));
        int typeWidth = Math.min(28, d.columns().stream().mapToInt(c -> c.type().length()).max().orElse(8));
        StringBuilder sb = new StringBuilder();
        sb.append("表 ").append(d.table()).append("（").append(d.columns().size()).append(" 列")
                .append(d.truncated() ? "，已截断" : "").append("）\n");
        sb.append("行数：").append(d.rowCount() == null ? "没取到" : String.valueOf(d.rowCount()))
                .append("（").append("REGISTERED".equals(d.rowSource()) ? "注册那一刻的快照，可能已过时"
                        : "LIVE".equals(d.rowSource()) ? "现算" : "COUNT 超时或无权限").append("）\n");
        sb.append("主键：").append(d.primaryKey().isEmpty() ? "未识别" : String.join("、", d.primaryKey()))
                .append("\n\n");
        int index = 1;
        for (var c : d.columns()) {
            sb.append(String.format("%3d. %-" + nameWidth + "s  %-" + typeWidth + "s  %-8s%s%n",
                    index++, c.name(), c.type(), c.nullable() ? "可空" : "NOT NULL",
                    c.primaryKey() ? "  ← 主键" : ""));
        }
        if (d.truncated()) {
            sb.append("\n列太多，只显示了前 ").append(d.columns().size()).append(" 列。");
        }
        return sb.toString();
    }

    /** 人看的采样段：空值率 + 不同值 + 样例。窗口大小必须写出来，否则数字会被当成全表结论。 */
    private static String renderProfile(io.github.dolow.reconlens.store.TableInspector.Profile p) {
        if (p == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n采样窗口：前 ").append(p.window()).append(" 行")
                .append("（空值率与不同值个数都是这个窗口上的估算，不是全表统计）\n");
        for (var stat : p.stats()) {
            if (stat.samples().isEmpty() && stat.nullRate() == null) {
                continue;
            }
            sb.append("\n  ").append(stat.name()).append("：");
            if (stat.nullRate() != null) {
                sb.append("空值 ").append(Math.round(stat.nullRate() * 100)).append("%");
            }
            if (stat.distinct() != null) {
                sb.append("，不同值约 ").append(stat.distinct());
                if (stat.distinctCapped()) {
                    sb.append("+");
                }
            }
            if (stat.samples().isEmpty()) {
                sb.append("（无样例）");
            } else {
                sb.append("；样例 ").append(String.join(" / ", stat.samples()));
                if (stat.masked()) {
                    sb.append("（已脱敏）");
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> structuredProfile(io.github.dolow.reconlens.store.TableInspector.Profile p) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("table", p.table());
        out.put("window", p.window());
        out.put("estimated", true);
        java.util.List<Map<String, Object>> stats = new java.util.ArrayList<>();
        for (var stat : p.stats()) {
            java.util.LinkedHashMap<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("name", stat.name());
            one.put("nullRate", stat.nullRate());
            one.put("distinct", stat.distinct());
            one.put("distinctCapped", stat.distinctCapped());
            one.put("min", stat.min());
            one.put("max", stat.max());
            one.put("samples", stat.samples());
            one.put("masked", stat.masked());
            stats.add(one);
        }
        out.put("columns", stats);
        return out;
    }

    /** 建议的结构化形态：三条并列 + 骨架字符串，方便宿主直接塞进草稿请求。 */
    private static Map<String, Object> structuredAdvice(
            io.github.dolow.reconlens.store.TableInspector.Advice advice) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("against", advice.against());
        out.put("dimensions", advice.dimensions().stream()
                .map(item -> Map.of("column", item.column(), "why", item.why())).toList());
        out.put("compares", advice.compares().stream()
                .map(item -> Map.of("column", item.column(), "why", item.why())).toList());
        out.put("notes", advice.notes());
        out.put("snippet", advice.snippet());
        return out;
    }

    /**
     * 人看的建议段。理由必须并列写出来：只给列名等于让人猜，而猜错的代价是跑一遍全量对账。
     */
    private static String renderAdvice(io.github.dolow.reconlens.store.TableInspector.Advice advice) {
        if (advice == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n起草建议：\n");
        sb.append("  维度候选：");
        if (advice.dimensions().isEmpty()) {
            sb.append("（没找到像业务键的列，可能要组合，例如 客户 + 订单号）\n");
        } else {
            sb.append("\n");
            for (io.github.dolow.reconlens.store.TableInspector.Suggestion item : advice.dimensions()) {
                sb.append("    · ").append(item.column()).append(" — ").append(item.why()).append("\n");
            }
        }
        sb.append("  比对字段候选：");
        if (advice.compares().isEmpty()) {
            sb.append("（没有数值列，只能比状态/文本是否相等）\n");
        } else {
            sb.append("\n");
            for (io.github.dolow.reconlens.store.TableInspector.Suggestion item : advice.compares()) {
                sb.append("    · ").append(item.column()).append(" — ").append(item.why()).append("\n");
            }
        }
        if (!advice.notes().isEmpty()) {
            sb.append("  注意：\n");
            for (String note : advice.notes()) {
                sb.append("    · ").append(note).append("\n");
            }
        }
        sb.append("  可直接改的骨架（把 code/name 改掉就能提交）：\n");
        for (String line : advice.snippet().split("\n")) {
            sb.append("    ").append(line).append("\n");
        }
        return sb.toString();
    }

    private static Map<String, Object> structuredDetail(io.github.dolow.reconlens.store.TableInspector.Detail d) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("table", d.table());
        out.put("rowCount", d.rowCount());
        out.put("rowSource", d.rowSource());
        out.put("truncated", d.truncated());
        out.put("primaryKey", d.primaryKey());
        java.util.List<Map<String, Object>> columns = new java.util.ArrayList<>();
        for (var c : d.columns()) {
            java.util.LinkedHashMap<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("name", c.name());
            one.put("type", c.type());
            one.put("nullable", c.nullable());
            one.put("size", c.size());
            one.put("scale", c.scale());
            one.put("primaryKey", c.primaryKey());
            columns.add(one);
        }
        out.put("columns", columns);
        return out;
    }

    private ToolSpec defineRule() {
        return new ToolSpec(ToolNames.DEFINE_RULE,
                "定义或修改一条对账规则：提交规则体（JSON 或 YAML，结构与规则文件一致），落为草稿并自动跑影子试算。"
                        + "草稿不生效 —— 必须把试算结论讲给用户，用户明确同意后调 recon_submit_approval 审批，通过后才能 recon_run。",
                """
                {"type":"object","properties":{
                  "ruleBody":{"type":"string","description":"规则体：JSON 或 YAML 文本，字段见 recon_list_rules 的输出（code/left/right/compare/missing/shard/options）"},
                  "operator":{"type":"string","description":"可选：操作人标识，记入规则版本历史"}},
                 "required":["ruleBody"],"additionalProperties":false}
                """,
                null,
                a -> {
                    ReconMcpTools.DraftReceipt receipt = tools.draft(
                            ToolArgs.text(a, "ruleBody", true),
                            ToolArgs.text(a, "operator", false));
                    // 文本给宿主大模型（含 recon_trial_status 这类下一步指令），结构化事实给会话/审计。
                    // 会话侧只读 structured，不读 text —— 给 agent 的指引不能出现在人话回执里。
                    return ToolOutcome.of(receipt.agentText(), receipt.asMap());
                });
    }

    private ToolSpec buildModel() {
        return new ToolSpec(ToolNames.BUILD_MODEL,
                "预处理物化：按规则声明的粒度和过滤条件把两侧数据提前汇总/窄化成模型表（带 biz_key 索引，不建主键 —— 重复业务键要留给引擎报 DUPLICATE_KEY），并注册一条读模型表的模型规则（规则 code 加后缀 __MODEL）。"
                        + "适合源数据变化慢、对账跑得频繁的场景（1000 万行实测每轮 21.5s → 之后 7.8~15.3ms，见 docs/benchmark.md §3.10）；"
                        + "代价是模型表与源表可能不同步，源数据变了要重建。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码，取自 recon_list_rules"},
                  "shards":{"type":"integer","description":"可选：模型表分片数（1~1024），默认沿用规则的分片数。两侧用同一个桶函数，保证同一个业务键落在同一片"},
                  "operator":{"type":"string","description":"可选：操作人标识，记入模型表元信息"}},
                 "required":["ruleCode"],"additionalProperties":false}
                """,
                null,
                a -> {
                    long shards = ToolArgs.integer(a, "shards", 0, false);
                    return ToolOutcome.text(tools.buildModel(
                            ToolArgs.text(a, "ruleCode", true), shards <= 0 ? null : (int) shards,
                            ToolArgs.text(a, "operator", false)));
                });
    }

    private ToolSpec run() {
        return new ToolSpec(ToolNames.RUN,
                "按规则跑一次对账（同步等待完成），返回任务摘要与差异分类；任务号可用于后续查明细和归因。",
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string","description":"规则编码，取自 recon_list_rules（与 ruleJson 二选一）"},
                  "ruleJson":{"type":"string","description":"可选：内联一份规则体（JSON 或 YAML）直接跑，不落规则库，适合临时换维度试算"},
                  "batchId":{"type":"string","description":"可选：业务批次号，便于检索与重跑对账"},
                  "remark":{"type":"string","description":"可选：备注"}},
                 "additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "ruleCode":{"type":"string"},
                  "ruleVersion":{"type":"integer","description":"内联规则时没有版本"},
                  "jobId":{"type":"integer"},
                  "batchId":{"type":"string"},
                  "status":{"type":"string"},
                  "leftRows":{"type":"integer"},
                  "rightRows":{"type":"integer"},
                  "matchedRows":{"type":"integer"},
                  "diffRows":{"type":"integer"},
                  "diffByType":{"type":"object","additionalProperties":{"type":"integer"}},
                  "diffBySeverity":{"type":"object","additionalProperties":{"type":"integer"}},
                  "errorMsg":{"type":"string"}},
                 "required":["ruleCode","jobId","status"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.RunOutcome outcome = tools.runWithView(
                            ToolArgs.text(a, "ruleCode", false),
                            ToolArgs.text(a, "ruleJson", false),
                            ToolArgs.text(a, "batchId", false),
                            ToolArgs.text(a, "remark", false));
                    return ToolOutcome.of(outcome.text(), outcome.view());
                });
    }

    private ToolSpec jobSummary() {
        return new ToolSpec(ToolNames.JOB_SUMMARY,
                "查看某个对账任务的状态、扫描量、差异分类与分片明细。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"recon_run 返回的任务号"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer"},
                  "ruleCode":{"type":"string"},
                  "batchId":{"type":"string"},
                  "status":{"type":"string"},
                  "shardCount":{"type":"integer"},
                  "shardDone":{"type":"integer"},
                  "leftRows":{"type":"integer"},
                  "rightRows":{"type":"integer"},
                  "matchedRows":{"type":"integer"},
                  "diffRows":{"type":"integer"},
                  "diffByType":{"type":"object","additionalProperties":{"type":"integer"}},
                  "diffBySeverity":{"type":"object","additionalProperties":{"type":"integer"}},
                  "failedShards":{"type":"array","items":{"type":"integer"}},
                  "durationMs":{"type":"integer"},
                  "errorMsg":{"type":"string"}},
                 "required":["jobId","status"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.RunOutcome outcome =
                            tools.jobSummaryWithView(ToolArgs.integer(a, "jobId", 0, true));
                    return ToolOutcome.of(outcome.text(), outcome.view());
                });
    }

    private ToolSpec listDiffs() {
        return new ToolSpec(ToolNames.LIST_DIFFS,
                "分页查看某个任务的差异明细（业务键、字段、左右值、相对偏差、已有归因）。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "diffType":{"type":"string","description":"可选：只看某一类差异，如 AMOUNT_MISMATCH / MISSING_RIGHT / DUPLICATE_KEY"},
                  "offset":{"type":"integer","description":"可选：跳过多少条，默认 0"},
                  "limit":{"type":"integer","description":"可选：返回条数，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                null,
                a -> ToolOutcome.text(tools.listDiffs(
                        ToolArgs.integer(a, "jobId", 0, true),
                        ToolArgs.text(a, "diffType", false),
                        (int) ToolArgs.integer(a, "offset", 0, false),
                        (int) ToolArgs.integer(a, "limit", 50, false))));
    }

    private ToolSpec explainDiff() {
        return new ToolSpec(ToolNames.EXPLAIN_DIFF,
                "给单条差异生成归因说明（LLM 可用时用 LLM，不可用时自动降级为规则模板）。",
                """
                {"type":"object","properties":{
                  "diffId":{"type":"integer","description":"差异编号，来自 recon_list_diffs"}},
                 "required":["diffId"],"additionalProperties":false}
                """,
                null,
                a -> ToolOutcome.text(tools.explainDiff(ToolArgs.integer(a, "diffId", 0, true))));
    }

    private ToolSpec explainJob() {
        return new ToolSpec(ToolNames.EXPLAIN_JOB,
                "给整个任务的差异批量补归因，并回显前几条结果。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"任务号"},
                  "limit":{"type":"integer","description":"可选：最多处理多少条，默认 50，上限 500"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                null,
                a -> ToolOutcome.text(tools.explainJob(
                        ToolArgs.integer(a, "jobId", 0, true),
                        (int) ToolArgs.integer(a, "limit", 50, false))));
    }

    private ToolSpec diagnose() {
        return new ToolSpec(ToolNames.DIAGNOSE,
                "任务级诊断：一次返回「哪里不对 + 疑似原因 + 建议动作」。比让模型自己串 recon_job_summary → "
                        + "recon_list_diffs → recon_explain_job 四次调用更省、更稳定 —— 判断留在服务端，结论可复现。"
                        + "归因抽样走知识库/模板，不消耗大模型。",
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer","description":"recon_run 返回的任务号"}},
                 "required":["jobId"],"additionalProperties":false}
                """,
                """
                {"type":"object","properties":{
                  "jobId":{"type":"integer"},
                  "ruleCode":{"type":"string"},
                  "status":{"type":"string"},
                  "verdict":{"type":"string","description":"HEALTHY / ATTENTION / BROKEN"},
                  "summary":{"type":"string"},
                  "facts":{"type":"array","items":{"type":"object","properties":{
                      "kind":{"type":"string"},"detail":{"type":"string"}},
                      "required":["kind","detail"],"additionalProperties":false}},
                  "suggestions":{"type":"array","items":{"type":"object","properties":{
                      "action":{"type":"string"},"reason":{"type":"string"}},
                      "required":["action","reason"],"additionalProperties":false}},
                  "samples":{"type":"array","items":{"type":"object","properties":{
                      "diffId":{"type":"integer"},"diffType":{"type":"string"},"severity":{"type":"string"},
                      "bizKey":{"type":"string"},"explanation":{"type":"string"}},
                      "required":["diffId","diffType","severity","bizKey","explanation"],
                      "additionalProperties":false}}},
                 "required":["jobId","status","verdict","summary"],"additionalProperties":true}
                """,
                a -> {
                    ReconMcpTools.DiagnosisView view = tools.diagnose(ToolArgs.integer(a, "jobId", 0, true));
                    return ToolOutcome.of(view.text(), view.view());
                });
    }

    /** 供会话侧直接取结构化视图（不解析文本）。 */
    public Map<String, Object> structuredOf(ToolOutcome outcome) {
        return outcome.structured() == null ? Map.of() : outcome.structured();
    }
}
