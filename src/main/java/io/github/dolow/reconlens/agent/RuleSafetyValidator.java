package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 闸门 1：结构校验 + 业务安全。
 *
 * <p><b>它不重复实现 DSL 校验</b> —— 调用它之前规则必须已经过了 {@code RuleParser.parseMap}。
 * 这里只补 {@code RuleParser} 不该管、但 LLM 生成时必须拦住的东西：
 * <ol>
 *   <li><b>对象白名单</b>：表与列真实存在（防编造）；</li>
 *   <li><b>SQL 只读</b>：{@code sql:} 与两侧 where 是自由文本，是放大的注入面；</li>
 *   <li><b>口径同源</b>：两侧分片切分确定不一致 → 硬错误（唯一一处直接判死）；</li>
 *   <li><b>阈值防篡改</b>：容差放大 / 严重级降级 / 比对字段缩小。</li>
 * </ol>
 *
 * <p>第 4 条是本闸门存在的根本理由：一个"聪明"的模型完全可能为了让差异归零，把
 * {@code tolerancePercent} 从 0.1 调到 10 —— 结果对账"全绿"，而钱已经错了。
 * <b>闸门要防的是"合法但错误"的规则，不只是"非法"的规则。</b>
 *
 * <p>返回违规列表而不是抛第一个错：LLM 自我修正时一次拿到全部问题，比改一个再撞一个省好几轮。
 */
public class RuleSafetyValidator {

    private static final Logger log = LoggerFactory.getLogger(RuleSafetyValidator.class);

    /** 不以 SELECT / WITH 开头的 sql: 一律拒绝（CTE 也是只读，没理由禁）。 */
    private static final Pattern SELECT_START = Pattern.compile("^(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 只读闸门的关键字。{@code INTO OUTFILE/DUMPFILE} 与 {@code FOR UPDATE} 单独列：
     * 它们以 SELECT 开头却能写文件 / 加锁，只检查"第一个词"会漏掉。
     * 包内可见：{@link SchemaCatalog#probeKeyExpression} 对维度表达式做同一套把关。
     */
    static final Pattern WRITE_KEYWORD = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|CREATE|REPLACE|MERGE|CALL|EXEC|EXECUTE"
                    + "|LOCK|UNLOCK|RENAME|HANDLER|LOAD\\s+DATA)\\b"
                    + "|\\bINTO\\s+(OUTFILE|DUMPFILE)\\b"
                    + "|\\bFOR\\s+UPDATE\\b"
                    + "|\\bON\\s+DUPLICATE\\s+KEY\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM_JOIN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(`?[A-Za-z_][\\w$]*`?(?:\\s*\\.\\s*`?[A-Za-z_][\\w$]*`?)?)",
            Pattern.CASE_INSENSITIVE);

    private final SchemaCatalog schema;
    private final ShardSplitAnalyzer shardAnalyzer;
    private final BigDecimal toleranceMultiplier;
    private final BigDecimal maxTolerancePercent;
    private final int maxShardCount;
    /** 测试开关：每条候选规则都追加一条 {@code TEST_FORCED} 违规（默认 false，见 AgentProperties.Safety）。 */
    private final boolean forceDraftViolation;

    /** 不传绝对上限时用这个值：与 {@code AgentProperties.Safety} 的默认值保持一致。 */
    static final BigDecimal DEFAULT_MAX_TOLERANCE_PERCENT = new BigDecimal("5.0");

    /** 不传分片上界时用这个值：与 {@code AgentProperties.Safety#maxShardCount} 的默认值保持一致。 */
    static final int DEFAULT_MAX_SHARD_COUNT = 256;

    public RuleSafetyValidator(SchemaCatalog schema, ShardSplitAnalyzer shardAnalyzer, BigDecimal toleranceMultiplier) {
        this(schema, shardAnalyzer, toleranceMultiplier, DEFAULT_MAX_TOLERANCE_PERCENT, DEFAULT_MAX_SHARD_COUNT);
    }

    public RuleSafetyValidator(SchemaCatalog schema, ShardSplitAnalyzer shardAnalyzer, BigDecimal toleranceMultiplier,
                               BigDecimal maxTolerancePercent) {
        this(schema, shardAnalyzer, toleranceMultiplier, maxTolerancePercent, DEFAULT_MAX_SHARD_COUNT);
    }

    public RuleSafetyValidator(SchemaCatalog schema, ShardSplitAnalyzer shardAnalyzer, BigDecimal toleranceMultiplier,
                               BigDecimal maxTolerancePercent, int maxShardCount) {
        this(schema, shardAnalyzer, toleranceMultiplier, maxTolerancePercent, maxShardCount, false);
    }

    /**
     * @param forceDraftViolation 只给测试/演示用：每条候选规则额外追加一条 {@code TEST_FORCED} 违规，
     *                            用来在真实服务上验证"被闸门 1 拒绝之后"的 re-plan 链路
     */
    public RuleSafetyValidator(SchemaCatalog schema, ShardSplitAnalyzer shardAnalyzer, BigDecimal toleranceMultiplier,
                               BigDecimal maxTolerancePercent, int maxShardCount, boolean forceDraftViolation) {
        this.schema = schema;
        this.shardAnalyzer = shardAnalyzer == null ? new ShardSplitAnalyzer() : shardAnalyzer;
        if (toleranceMultiplier == null || toleranceMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("容差倍数不能小于 1");
        }
        if (maxTolerancePercent == null || maxTolerancePercent.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("容差绝对上限必须 > 0");
        }
        if (maxShardCount < 1) {
            throw new IllegalArgumentException("分片数上限必须 ≥ 1");
        }
        this.toleranceMultiplier = toleranceMultiplier;
        this.maxTolerancePercent = maxTolerancePercent;
        this.maxShardCount = maxShardCount;
        this.forceDraftViolation = forceDraftViolation;
        if (forceDraftViolation) {
            log.warn("闸门 1 的测试开关已打开（recon.agent.safety.force-draft-violation=true）："
                    + "任何草稿都会被判违规 —— 只用于验证 re-plan 链路，不要在生产开");
        }
    }

    /**
     * 人工豁免：只能由闸门 3 的审核人写入，<b>不能由草稿自己声明</b>（v0.1 的"除草稿中显式声明"
     * 等于给 LLM 开了一个自我豁免后门，已删除）。
     */
    public record Exemptions(Set<String> approvedFieldRemovals) {

        public Exemptions {
            approvedFieldRemovals = approvedFieldRemovals == null ? Set.of() : Set.copyOf(approvedFieldRemovals);
        }

        public static Exemptions none() {
            return new Exemptions(Set.of());
        }

        public boolean allows(String field) {
            for (String approved : approvedFieldRemovals) {
                if (approved.equalsIgnoreCase(field)) {
                    return true;
                }
            }
            return false;
        }
    }

    public List<SafetyViolation> validate(RuleSpec candidate, RuleSpec baseline) {
        return validate(candidate, baseline, Exemptions.none());
    }

    /**
     * @param candidate 待落库的规则（已过 {@code RuleParser}）
     * @param baseline  当前生效版本；新规则传 {@code null}（此时跳过"放大/缩小"判定）
     */
    public List<SafetyViolation> validate(RuleSpec candidate, RuleSpec baseline, Exemptions exemptions) {
        List<SafetyViolation> violations = new ArrayList<>();
        if (forceDraftViolation) {
            // 测试注入：下面照样跑完所有真实校验，这样"被拒之后"的 payload 里既有真实结论也有人为的这条
            violations.add(new SafetyViolation(SafetyViolation.ViolationCode.TEST_FORCED, "test.forced",
                    "测试开关 recon.agent.safety.force-draft-violation=true：这条违规是人为塞的，"
                            + "用来验证被拒之后的 re-plan 链路",
                    "关掉开关（默认 false）后重跑即可通过"));
        }
        checkSide("left", candidate.left(), candidate, violations);
        checkSide("right", candidate.right(), candidate, violations);
        checkShard(candidate, violations);
        // 绝对上限与基线无关，必须放在 if 外面：Agent 的首版规则 baseline == null，
        // 只做倍数校验等于对"从零起草的规则"完全不设限。
        checkAbsoluteTolerance(candidate, violations);
        if (baseline != null) {
            checkTampering(candidate, baseline, exemptions == null ? Exemptions.none() : exemptions, violations);
        }
        if (!violations.isEmpty()) {
            log.warn("规则 {} 未通过闸门 1，共 {} 条违规: {}", candidate.code(), violations.size(), violations);
        }
        return violations;
    }

    private void checkSide(String sideKey, SideSpec side, RuleSpec rule, List<SafetyViolation> out) {
        if (side.customSql()) {
            checkSql(sideKey + ".sql", side.sql(), true, out);
            Set<String> tables = tablesIn(side.sql());
            if (tables.isEmpty()) {
                out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_TABLE, sideKey + ".sql",
                        sideKey + ".sql 里识别不出任何表名，无法做对象白名单校验",
                        "用最简单的 FROM <表>/JOIN <表> 写 SQL，不要在 FROM 后面直接跟子查询却不写表名"));
            }
            for (String table : tables) {
                checkTableAllowed(sideKey + ".sql", table, out);
            }
            // sql: 的输出列由表达式与别名决定，静态拿不到 —— 这里只校验能静态确认的对象。
        } else {
            Optional<SchemaCatalog.TableSchema> table = schema.find(side.table());
            if (table.isEmpty()) {
                out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_TABLE, sideKey + ".table",
                        sideKey + ".table = " + side.table() + " 不在白名单内，或数据源里没有这张表",
                        "只能用 recon.agent.schema.tables 里列出的表；拼写与 schema 前缀都要对"));
            } else {
                checkColumns(sideKey, side, rule.compare(), table.get(), out);
            }
        }
        if (side.where() != null && !side.where().isBlank()) {
            checkSql(sideKey + ".where", side.where(), false, out);
        }
    }

    private void checkColumns(String sideKey, SideSpec side, List<FieldRule> compare,
                              SchemaCatalog.TableSchema table, List<SafetyViolation> out) {
        if (!side.derivedKey() && !table.hasColumn(side.keyField())) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_COLUMN, sideKey + ".keyField",
                    sideKey + ".keyField = " + side.keyField() + " 不是 " + table.name() + " 的列",
                    "对照表结构改成真实列名，或改用 dimension 声明对账维度"));
        }
        for (String column : side.dimensionColumns()) {
            if (!table.hasColumn(column)) {
                // 表达式混进数组是最常见的踩法：dimension 数组只收裸列名，函数表达式必须写成
                // dimension 字符串（引擎按表达式编译）。提示里点名这一点，模型一轮就能自纠正。
                boolean looksLikeExpression = column.contains("(") || column.contains(" ")
                        || column.toUpperCase(java.util.Locale.ROOT).matches(".*\\b(CONCAT|DATE|SUBSTR|SUBSTRING|COALESCE|IFNULL|CAST|TRIM|LOWER|UPPER)\\b.*");
                out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_COLUMN, sideKey + ".dimension",
                        sideKey + ".dimension 引用了不存在的列 " + column,
                        looksLikeExpression
                                ? "dimension 数组只能放裸列名；" + column + " 是函数表达式，请改写成字符串形式："
                                        + "\"dimension\": \"" + column + "\""
                                : "对照表结构改成真实列名"));
            }
        }
        // 表达式维度：dimensionColumns 是空的，静态列核对不适用 —— 但模型在表达式里编造列名
        // （CONCAT(customer_id, '#', region) 而表里没有 region）是真实会发生的事，漏到试算阶段
        // 只会以数据库原生 "Unknown column" 报错，晚且不友好。用 WHERE 1=0 探针让数据库判定，
        // 把可用列清单直接写进违规建议，自纠正一轮就能修好。
        // （聚合维度同样要探：measures 表达式外层的 dimension 表达式引用的仍是表列）
        if (side.derivedKey() && side.dimensionColumns().isEmpty() && side.dimension() != null) {
            schema.probeKeyExpression(table.name(), side.dimension()).ifPresent(error ->
                    out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_COLUMN, sideKey + ".dimension",
                            sideKey + ".dimension 表达式在 " + table.name() + " 上编译失败：" + error,
                            "对照表结构修正表达式里的列名；可用列: " + table.columns())));
        }
        // 聚合维度的比对字段是 measures 的别名，不是表列 —— 查列会把正常规则判成违规。
        if (side.aggregated()) {
            return;
        }
        for (int i = 0; i < compare.size(); i++) {
            FieldRule field = compare.get(i);
            if (!table.hasColumn(field.field())) {
                out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_COLUMN,
                        "compare[" + i + "].field",
                        "比对字段 " + field.field() + " 不是 " + table.name() + " 的列",
                        "改成该侧真实存在的列名；不同名的两列请用 sql: 或 dimension 做别名"));
            }
        }
    }

    private void checkTableAllowed(String location, String table, List<SafetyViolation> out) {
        if (!schema.allows(table) || schema.find(table).isEmpty()) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.UNKNOWN_TABLE, location,
                    "SQL 引用的表 " + table + " 不在白名单内，或数据源里没有这张表",
                    "只能查询 recon.agent.schema.tables 里列出的表"));
        }
    }

    private void checkShard(RuleSpec rule, List<SafetyViolation> out) {
        int count = rule.shard().count();
        if (count > maxShardCount) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SHARD_COUNT, "shard.count",
                    "分片数 " + count + " 超过上限 " + maxShardCount,
                    "分片是重跑单位：一个分片失败只重跑它，切到 " + count + " 片时一次失败要重跑 " + count
                            + " 个分片。先降到 " + maxShardCount + " 以内；真要更细，先把重跑成本算清楚"));
        }
        ShardSplitAnalyzer.Result result = shardAnalyzer.analyze(rule.shard());
        if (result.verdict() == ShardSplitAnalyzer.Verdict.DIFFERENT) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SHARD_SPLIT_KEY, "shard.whereLeft/whereRight",
                    result.reason(),
                    "让两侧按同一个业务键维度切分（例如都用 MOD(order_no, {shards})）；"
                            + "按各自代理主键切分会把同一笔业务分到不同分片，凭空产出单边缺失差异"));
        }
        // 不可移植函数：HASH() 是最容易犯的一个（MySQL 没有，语义上又"恰好该在这里"）。
        // 不拦的话要等试算阶段的 SQLSyntaxErrorException 才暴露，白白烧掉一轮自纠正。
        checkPortableFunction("shard.whereLeft", rule.shard().whereLeft(), out);
        checkPortableFunction("shard.whereRight", rule.shard().whereRight(), out);
        checkPortableFunction("left.where", rule.left().where(), out);
        checkPortableFunction("right.where", rule.right().where(), out);
        checkPortableFunction("left.dimension", rule.left().dimension(), out);
        checkPortableFunction("right.dimension", rule.right().dimension(), out);
        // UNKNOWN 不拒绝：抽不出切分表达式不等于错，按业务键做哈希/区间切分都是合法写法。
    }

    /** 逐个黑名单函数扫表达式： MySQL 没有而模型爱用的哈希/窗口函数在这里挡下，报错文案直接给出替身。 */
    private static void checkPortableFunction(String location, String expression, List<SafetyViolation> out) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String upper = expression.toUpperCase(java.util.Locale.ROOT);
        if (upper.matches(".*\\bHASH\\s*\\(.*")) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.NON_PORTABLE_FUNCTION, location,
                    location + " 使用了 HASH() —— MySQL（含 H2 兼容模式）没有这个函数，运行时必然语法报错",
                    "字符串列改用 MOD(CRC32(<表达式>), {shards}) = {index}；数值列直接 MOD(<列>, {shards}) = {index}；"
                            + "两侧都有同语义数值主键时优先配 shard.rangeKey 走范围分片"));
        }
    }

    /**
     * 容差的绝对上限。<b>不看基线</b>，因为"基线"对首版规则并不存在。
     *
     * <p>它拦的不是"模型写了个非法值"（非法值 {@code RuleParser} 就会拒），而是
     * "模型写了一个完全合法、但会让差异全部消失的值" —— 闸门 1 存在的根本理由。
     *
     * <p>绝对金额（{@code tolerance}）不设上限：它和币种、业务量级绑死，没有通用的合理值。
     * 能通用约束的是百分比，所以这里只查 {@code tolerancePercent}。
     */
    private void checkAbsoluteTolerance(RuleSpec rule, List<SafetyViolation> out) {
        for (int i = 0; i < rule.compare().size(); i++) {
            FieldRule field = rule.compare().get(i);
            BigDecimal percent = field.tolerancePercent();
            if (percent == null || percent.compareTo(maxTolerancePercent) <= 0) {
                continue;
            }
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.TOLERANCE_ABSOLUTE_LIMIT,
                    "compare[" + i + "].tolerancePercent",
                    "比对字段 " + field.field() + " 的容差 " + percent + "% 超过绝对上限 "
                            + maxTolerancePercent + "%",
                    "容差超过这个比例，对账已经看不出问题了；确实需要更宽的口径，"
                            + "改 recon.agent.safety.max-tolerance-percent 并由人确认，不要写在草稿里"));
        }
    }

    private void checkTampering(RuleSpec candidate, RuleSpec baseline, Exemptions exemptions,
                                List<SafetyViolation> out) {
        Map<String, FieldRule> baseFields = new LinkedHashMap<>();
        for (FieldRule rule : baseline.compare()) {
            baseFields.put(rule.field().toUpperCase(Locale.ROOT), rule);
        }

        for (int i = 0; i < candidate.compare().size(); i++) {
            FieldRule field = candidate.compare().get(i);
            FieldRule base = baseFields.get(field.field().toUpperCase(Locale.ROOT));
            if (base == null) {
                continue;   // 新字段是收紧而不是放宽
            }
            checkTolerance("compare[" + i + "].tolerance", field.field(),
                    base.tolerance(), field.tolerance(), out);
            checkTolerance("compare[" + i + "].tolerancePercent", field.field(),
                    base.tolerancePercent(), field.tolerancePercent(), out);
            if (field.severity().ordinal() < base.severity().ordinal()) {
                out.add(new SafetyViolation(SafetyViolation.ViolationCode.SEVERITY_DOWNGRADED,
                        "compare[" + i + "].severity",
                        "比对字段 " + field.field() + " 的严重级从 " + base.severity() + " 降到 " + field.severity(),
                        "严重级只能维持或上调；降级会让真实差异在下游告警里被淹没"));
            }
        }

        Set<String> candidateFields = new LinkedHashSet<>();
        for (FieldRule rule : candidate.compare()) {
            candidateFields.add(rule.field().toUpperCase(Locale.ROOT));
        }
        for (FieldRule base : baseline.compare()) {
            if (candidateFields.contains(base.field().toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (exemptions.allows(base.field())) {
                continue;
            }
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.FIELD_REMOVED,
                    "compare." + base.field(),
                    "比对字段 " + base.field() + " 被删掉了：这一列从此不再对账，差异会直接消失",
                    "把字段加回来；确实不该比的，需要审核人写人工豁免（{field, reason, approved_by}），"
                            + "草稿自己声明不算"));
        }

        if (candidate.missing().severity().ordinal() < baseline.missing().severity().ordinal()) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SEVERITY_DOWNGRADED, "missing.severity",
                    "单边缺失的严重级从 " + baseline.missing().severity() + " 降到 " + candidate.missing().severity(),
                    "严重级只能维持或上调"));
        }
        if (candidate.duplicate().severity().ordinal() < baseline.duplicate().severity().ordinal()) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SEVERITY_DOWNGRADED, "duplicate.severity",
                    "重复键的严重级从 " + baseline.duplicate().severity() + " 降到 " + candidate.duplicate().severity(),
                    "严重级只能维持或上调"));
        }
    }

    /** 基线没有容差 = 精确比对 = 0，所以"从精确比对放宽成有容差"同样会被这条拦住。 */
    private void checkTolerance(String location, String field, BigDecimal base, BigDecimal candidate,
                                List<SafetyViolation> out) {
        BigDecimal before = base == null ? BigDecimal.ZERO : base;
        BigDecimal after = candidate == null ? BigDecimal.ZERO : candidate;
        if (after.compareTo(before) <= 0) {
            return;
        }
        BigDecimal ceiling = before.multiply(toleranceMultiplier);
        if (after.compareTo(ceiling) <= 0) {
            return;
        }
        out.add(new SafetyViolation(SafetyViolation.ViolationCode.TOLERANCE_INFLATED, location,
                "字段 " + field + " 的容差从 " + before.toPlainString() + " 放宽到 " + after.toPlainString()
                        + "，超过基线 " + toleranceMultiplier.toPlainString() + " 倍（上限 "
                        + ceiling.toPlainString() + "）",
                "容差只能小幅调整（≤ 基线 × " + toleranceMultiplier.toPlainString()
                        + "）；为了让差异消失而放宽容差正是本闸门要拦的事"));
    }

    /**
     * 只读校验。{@code requireSelect=true} 用于 {@code sql:}（必须是一条查询），
     * {@code false} 用于 where 片段（纯条件，不需要也不该以 SELECT 开头）。
     */
    private void checkSql(String location, String raw, boolean requireSelect, List<SafetyViolation> out) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        String withoutTrailing = text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text;
        if (withoutTrailing.contains(";")) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.MULTI_STATEMENT, location,
                    location + " 里出现多个语句（分号）。驱动允许一次执行多条语句时，第二条可以是任意写操作",
                    "只写一条语句，结尾的分号可以省略"));
        }
        String stripped = SqlText.stripCommentsAndLiterals(withoutTrailing);
        if (requireSelect && !SELECT_START.matcher(stripped).find()) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SQL_NOT_READ_ONLY, location,
                    location + " 必须以 SELECT 或 WITH 开头",
                    "sql: 只能是一条只读查询"));
        }
        Matcher write = WRITE_KEYWORD.matcher(stripped);
        if (write.find()) {
            out.add(new SafetyViolation(SafetyViolation.ViolationCode.SQL_NOT_READ_ONLY, location,
                    location + " 含写操作关键字 " + write.group().toUpperCase(Locale.ROOT) + "（只检查字面量之外的文本）",
                    "去掉写操作；对账只读数据源，任何 DML/DDL 都不该出现在规则里"));
        }
    }

    private static Set<String> tablesIn(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = FROM_JOIN.matcher(SqlText.stripCommentsAndLiterals(sql));
        while (matcher.find()) {
            String table = matcher.group(1).replace("`", "").replace("\"", "")
                    .replace("[", "").replace("]", "").replace(" ", "");
            if (!table.isEmpty()) {
                tables.add(table);
            }
        }
        return tables;
    }
}
