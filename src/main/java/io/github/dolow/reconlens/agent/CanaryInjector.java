package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T0 对照样本（canary）：给判定链做一次<b>变异测试</b>。
 *
 * <p>T1–T7 有一个共同的弱点：它们都是"没有报出问题就算通过"。
 * 而闸门失效的典型形态恰恰是"什么都没发现"—— 比如 {@code compare} 被悄悄少读一个字段，
 * 试算当然不报错。一个只会说"没事"的闸门，无法自证它还在工作。
 *
 * <h2>为什么顺序必须是"先跑一次正式试算，再挑注入点"</h2>
 * {@code LIMIT 1} 取到的键<b>可能本来就有一条差异</b>。改值后会出现 2 条，"恰好 1 条"的断言失败；
 * 删行时若该键本来就有字段差异，会同时产生两条不同类型的差异，断言一样失败。
 * 于是 canary 会误报"闸门坏了"—— 而按 §5.2 的处理方式，误报的代价是<b>整条规则被强制降级到
 * {@code coverage=NONE}、任何人不能批准</b>。判定变严的同时，误报面必须收窄，
 * 否则闸门会自己把路堵死。
 *
 * <p>所以注入点只在"本次差异集之外"的键里选；干净键不足 2 个时不是"闸门坏了"，而是
 * {@code CANARY_NO_CLEAN_ROW}：这说明这条规则差异率异常高，信号本身有价值，且<b>不置 gate_trusted=false</b>。
 */
public class CanaryInjector {

    private static final Logger log = LoggerFactory.getLogger(CanaryInjector.class);

    /** 找干净键时最多扫多少行样本。样本本身有 windowRows 上限，这里是第二道闸。 */
    private static final int CLEAN_SCAN_LIMIT = 5000;

    private final JdbcTemplate jdbc;

    public CanaryInjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一次注入的记录。<b>必须写进 trial_report</b>：否则事后无法复现"当时把哪个键的哪个字段改成了什么"。
     */
    public record Injection(String kind, String table, String key, String field, String before, String after) {
    }

    public record Plan(boolean runnable, String reason, List<Injection> injections,
                       String changeKey, String changeField, String removeKey) {

        static Plan notRunnable(String reason) {
            return new Plan(false, reason, List.of(), null, null, null);
        }
    }

    /**
     * 选点并执行注入（直接改样本表 —— 这也是"先物化"的第二个收益：
     * 有了可以随便改的副本，才敢做注入式自检。拿生产表做 canary 是绝对不行的）。
     *
     * @param dirtyKeys 本次正式试算已经报出差异的键，必须全部排除
     */
    public Plan inject(SampleMaterializer.Materialized tables, RuleSpec trialRule, Set<String> dirtyKeys) {
        SideSpec right = trialRule.right();
        String keyExpr = right.keyExpression();
        List<FieldRule> comparable = trialRule.compare();
        List<Candidate> candidates = candidates(tables.tableRight(), keyExpr, comparable, dirtyKeys);
        if (candidates.size() < 2) {
            return Plan.notRunnable("样本里只剩 " + candidates.size() + " 个干净键（需要 2 个），"
                    + "说明这条规则差异率异常高 —— 这不是闸门故障，是规则可信度问题");
        }
        List<Injection> injections = new ArrayList<>();
        String changeKey = null;
        String changeField = null;

        // ① 改一个值：只在有 compare 字段时做。没有可比字段的规则（纯存在性核对）只做删除注入。
        if (!comparable.isEmpty()) {
            FieldRule field = comparable.get(0);
            for (Candidate candidate : candidates) {
                String before = candidate.values().get(field.field());
                if (before == null) {
                    continue;
                }
                String after = mutate(before, field.type());
                if (after == null || after.equals(before)) {
                    continue;
                }
                int updated = jdbc.update("UPDATE " + tables.tableRight() + " SET " + field.field()
                                + " = " + literal(after, field.type()) + " WHERE " + keyExpr + " = " + literal(candidate.key(), right.keyType()));
                if (updated == 1) {
                    changeKey = candidate.key();
                    changeField = field.field();
                    injections.add(new Injection("CHANGE_VALUE", tables.tableRight(), candidate.key(),
                            field.field(), before, after));
                    break;
                }
            }
            if (changeKey == null) {
                return Plan.notRunnable("没有任何干净键能改出有效差异（值都改不动或改完仍相等）");
            }
        }

        // ② 删一行：必须与改值用不同的键，否则两条注入互相掩盖
        String removeKey = null;
        for (Candidate candidate : candidates) {
            if (candidate.key().equals(changeKey)) {
                continue;
            }
            int deleted = jdbc.update("DELETE FROM " + tables.tableRight()
                    + " WHERE " + keyExpr + " = " + literal(candidate.key(), right.keyType()));
            if (deleted == 1) {
                removeKey = candidate.key();
                injections.add(new Injection("DELETE_ROW", tables.tableRight(), candidate.key(), null, null, null));
                break;
            }
        }
        if (removeKey == null) {
            return Plan.notRunnable("找不到第二个干净键来删行（改值与删行必须用不同的键）");
        }
        return new Plan(true, null, injections, changeKey, changeField, removeKey);
    }

    /**
     * 检出断言：注入的两处是否都被<b>恰好</b>检出。
     *
     * <p>"恰好 1 条"比"至少 1 条"严格得多，这是有意的：多出来的那条说明判定链把别的东西也算了进来，
     * 或者注入写到了别处。少了那条说明对应的判定分支压根没生效。
     */
    public TrialOutcome.Canary verify(Plan plan, RuleSpec trialRule, List<DiffRecord> canaryDiffs) {
        if (!plan.runnable()) {
            return new TrialOutcome.Canary(false, false, plan.reason());
        }
        StringBuilder detail = new StringBuilder();
        boolean passed = true;

        if (plan.changeKey() != null) {
            long hits = canaryDiffs.stream()
                    .filter(d -> plan.changeKey().equals(d.bizKey()))
                    .filter(d -> plan.changeField().equalsIgnoreCase(nullToEmpty(d.field())))
                    .count();
            boolean ok = hits == 1;
            passed &= ok;
            detail.append("[改值] key=").append(plan.changeKey()).append(" field=").append(plan.changeField())
                    .append(" 期望 1 条，实际 ").append(hits).append(" 条 → ").append(ok ? "检出" : "未按预期检出");
        } else {
            detail.append("[改值] 本规则没有可比字段，跳过");
        }

        long removals = canaryDiffs.stream()
                .filter(d -> plan.removeKey().equals(d.bizKey()))
                .filter(d -> isRecordLevel(d.field()))
                .filter(d -> trialRule.missing().rightMissingDiffType().equals(d.diffType()))
                .count();
        boolean removalOk = removals == 1;
        passed &= removalOk;
        detail.append("；[删行] key=").append(plan.removeKey())
                .append(" 期望 1 条 ").append(trialRule.missing().rightMissingDiffType())
                .append("，实际 ").append(removals).append(" 条 → ").append(removalOk ? "检出" : "未按预期检出");
        if (passed) {
            log.info("T0 canary 通过: {}", detail);
        } else {
            log.warn("T0 canary 未通过: {}（整个 gate-version 将被标记为不可信）", detail);
        }
        return new TrialOutcome.Canary(true, passed, detail.toString());
    }

    /**
     * 生成一个"一定与原名不同"的值。
     *
     * <p>数值不能只加 1：容差 0.01、甚至 0.1 的规则下加 1 会落在容差内，canary 就白做了。
     * 用 {@code +1000 + |原值|} 保证远超任何合理容差。
     */
    static String mutate(String before, FieldType type) {
        if (before == null) {
            return null;
        }
        return switch (type) {
            case LONG, DECIMAL, BOOLEAN -> {
                if (type == FieldType.BOOLEAN) {
                    yield "TRUE".equalsIgnoreCase(before) ? "FALSE" : "TRUE";
                }
                try {
                    BigDecimal value = new BigDecimal(before.trim().replace(",", ""));
                    yield value.abs().add(new BigDecimal("1000")).add(BigDecimal.ONE).toPlainString();
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            // 字符串：翻转首尾字符而<b>不是</b>改大小写 —— ignoreCase 的规则会把大小写变化吃掉
            case STRING -> flip(before);
        };
    }

    private static String flip(String value) {
        if (value.isEmpty()) {
            return "X";
        }
        char first = value.charAt(0);
        char replacement = first == 'X' ? 'Y' : 'X';
        return replacement + value.substring(1);
    }

    private static String literal(String value, FieldType type) {
        if (type == FieldType.LONG || type == FieldType.DECIMAL || type == FieldType.BOOLEAN) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** 候选行：一条键 + 该行上所有 compare 字段的原值。 */
    private record Candidate(String key, Map<String, String> values) {
    }

    private List<Candidate> candidates(String table, String keyExpr, List<FieldRule> comparable, Set<String> dirty) {
        Set<String> dirtySet = dirty == null ? Set.of() : new HashSet<>(dirty);
        String projection = comparable.isEmpty()
                ? keyExpr + " AS k"
                : keyExpr + " AS k, " + comparable.stream()
                        .map(field -> field.field()).reduce((a, b) -> a + ", " + b).orElse("") ;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + projection + " FROM " + table + " ORDER BY " + keyExpr + " LIMIT " + CLEAN_SCAN_LIMIT);
        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("k"));
            if (dirtySet.contains(key)) {
                continue;
            }
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (FieldRule field : comparable) {
                Object value = row.get(field.field().toLowerCase(java.util.Locale.ROOT));
                if (value == null) {
                    // 不同驱动对列名大小写的处理不一致：退回不区分大小写的查找
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(field.field())) {
                            value = entry.getValue();
                            break;
                        }
                    }
                }
                values.put(field.field(), value == null ? null : String.valueOf(value));
            }
            candidates.add(new Candidate(key, values));
        }
        return candidates;
    }

    private static boolean isRecordLevel(String field) {
        return field == null || field.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
