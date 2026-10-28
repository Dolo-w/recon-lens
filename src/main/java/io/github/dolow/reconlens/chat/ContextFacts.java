package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给模型看的"系统事实卡片"：**只有元数据，没有一行数据**。
 *
 * <p>为什么要给：模型看不到你的库，所以「之前都执行过哪些规则」「状态比什么」这类问题，
 * 它只能按 prompt 里的动作表猜；猜出来的东西再合法也可能是错的。把"有哪几条规则、
 * 各比什么、最近跑过哪些任务、有哪些表"这几行事实喂给它，它才有材料做判断。
 *
 * <p>为什么只给元数据：行数据是对账客户的业务秘密，而且给了也没用——模型不需要看 137 条差异
 * 才能说"该看明细了"。这条边界写在提示词里，也写在这个类里：这里没有任何一条 SQL 会 SELECT 数据列。
 *
 * <p>为什么空集合要单独说明：{@code ruleCodes} 为空有两种含义——"库里真没有规则"和
 * "事实没取到"。后者不能当作否证使用（否则一次数据库抖动就会把所有规则判成不存在），
 * 所以校验方必须先看 {@link #knowsRules()}。
 */
public record ContextFacts(String text, Set<String> ruleCodes, Set<Long> jobIds, Set<String> tables) {

    private static final Logger log = LoggerFactory.getLogger(ContextFacts.class);

    /** 上限：事实卡片是"索引"不是"全量"，撑大 prompt 得不偿失。 */
    private static final int MAX_RULES = 6;
    private static final int MAX_JOBS = 4;
    private static final int MAX_TABLES = 12;

    public static final ContextFacts EMPTY = new ContextFacts("", Set.of(), Set.of(), Set.of());

    public ContextFacts {
        ruleCodes = ruleCodes == null ? Set.of() : Set.copyOf(ruleCodes);
        jobIds = jobIds == null ? Set.of() : Set.copyOf(jobIds);
        tables = tables == null ? Set.of() : Set.copyOf(tables);
    }

    /** 取到了规则清单。空集合 ≠ 没有规则，见类注释。 */
    public boolean knowsRules() {
        return !ruleCodes.isEmpty();
    }

    public boolean knowsJobs() {
        return !jobIds.isEmpty();
    }

    public boolean knowsTables() {
        return !tables.isEmpty();
    }

    /**
     * 从规则目录与任务表拼出这张卡片。
     *
     * <p>任何一步失败都退化成 {@link #EMPTY}：事实是"锦上添花"，取不到就不注入，
     * 会话按原来的词表/槽位逻辑继续跑。
     *
     * <p>但退化不等于放行：{@link #EMPTY} 里 knowsRules/knowsJobs/knowsTables 全为 false，
     * 拿它去核实，任何带规则编码、任务号、表名的动作都会判成"没核实"（见
     * LlmPlanner#evidenceOf 的兜底分支），运行时据此先问一句，而不是拿着用户的话去执行。
     */
    public static ContextFacts build(RuleCatalog catalog, ReconRepository repository) {
        try {
            Set<String> codes = new LinkedHashSet<>();
            Set<String> tables = new LinkedHashSet<>();
            List<String> ruleLines = new ArrayList<>();
            // 生效版之外也带上最新一版的草稿/审核中规则：否则"某条草稿的试算怎么样"会被判成
            // "库里没找到"。草稿在清单里标明状态，但模型仍能引用它的编码。
            Set<String> activeCodes = new LinkedHashSet<>();
            java.util.Map<String, Integer> activeVersions = new java.util.HashMap<>();
            for (RuleCatalog.Resolved resolved : catalog.allResolved()) {
                activeCodes.add(resolved.spec().code());
                activeVersions.put(resolved.spec().code(), resolved.version());
            }
            for (RuleCatalog.Resolved resolved : catalog.latestAnyState()) {
                RuleSpec spec = resolved.spec();
                codes.add(spec.code());
                collectTable(spec.left(), tables);
                collectTable(spec.right(), tables);
                boolean active = activeCodes.contains(spec.code())
                        && java.util.Objects.equals(activeVersions.get(spec.code()), resolved.version());
                if (ruleLines.size() < MAX_RULES) {
                    ruleLines.add(ruleLine(spec, active ? "" : "（草稿）"));
                }
            }

            Set<Long> jobIds = new LinkedHashSet<>();
            List<String> jobLines = new ArrayList<>();
            List<ReconJob> jobs = repository.listJobs(MAX_JOBS);
            for (ReconJob job : jobs) {
                jobIds.add(job.id());
                jobLines.add("#" + job.id() + " " + job.ruleCode() + " "
                        + (job.status() == null ? "-" : job.status().name())
                        + " 差异" + job.diffRows());
            }

            StringBuilder sb = new StringBuilder();
            if (!ruleLines.isEmpty()) {
                sb.append("规则（").append(codes.size()).append(" 条")
                        .append(codes.size() > ruleLines.size() ? "，只列前 " + ruleLines.size() : "")
                        .append("）：\n");
                ruleLines.forEach(line -> sb.append(line).append('\n'));
            }
            if (!tables.isEmpty()) {
                List<String> limited = tables.stream().limit(MAX_TABLES).toList();
                sb.append("可对账的表：").append(String.join("、", limited)).append('\n');
            }
            if (!jobLines.isEmpty()) {
                sb.append("最近任务：").append(String.join("；", jobLines)).append('\n');
            }
            sb.append("（以上只是目录与状态；你没有数据行的访问权限，不要编造任何金额、行数或差异内容。）");
            return new ContextFacts(sb.toString(), codes, jobIds, tables);
        } catch (RuntimeException e) {
            log.debug("事实卡片构建失败，本次不注入也不校验", e);
            return EMPTY;
        }
    }

    private static void collectTable(io.github.dolow.reconlens.dsl.SideSpec side, Set<String> tables) {
        if (side != null && side.table() != null && !side.table().isBlank()) {
            tables.add(side.table());
        }
    }

    private static String ruleLine(RuleSpec spec, String state) {
        String left = tableOf(spec.left());
        String right = tableOf(spec.right());
        String compare = spec.compare().stream().map(FieldRule::field).collect(Collectors.joining("/"));
        String key = spec.left() == null || spec.left().keyField() == null ? "" : "，键 " + spec.left().keyField();
        return "- " + spec.code() + state + "：" + (spec.name() == null ? "" : spec.name())
                + "；左 " + left + "、右 " + right + "，比 " + compare + key;
    }

    private static String tableOf(io.github.dolow.reconlens.dsl.SideSpec side) {
        if (side == null) {
            return "-";
        }
        return side.table() != null ? side.table() : "（自定义 SQL）";
    }
}
