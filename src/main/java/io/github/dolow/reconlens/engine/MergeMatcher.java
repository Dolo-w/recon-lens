package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 归并式对账的核心：两侧游标都按业务键升序，双指针推进，只保留"当前同键组"在内存里。
 * <p>四种差异的判定顺序：
 * <ol>
 *   <li>业务键相等 → 取同键组；组内任一侧多于一条 → 只报重复键差异，<b>不再配对</b>。
 *       组内的行序取决于数据库返回顺序（排序键相同时不是确定性的），按位凑对等于把
 *       "哪行和哪行比"交给数据库的脾气 —— 同一份数据两次跑可能给出不同的字段差异集。
 *       重复键本身就是数据问题，先修数据再谈字段比对。</li>
 *   <li>组内两侧各恰好一条 → 逐字段比对 → 字段值不一致</li>
 *   <li>左侧业务键更小 → 这条只有左表有 → 右侧缺失</li>
 *   <li>右侧业务键更小 → 这条只有右表有 → 左侧缺失</li>
 * </ol>
 * 内存占用 = 同键组大小 + 差异批次大小，与数据总量无关。
 */
public final class MergeMatcher {

    private static final int MAX_IDS_IN_SUMMARY = 5;

    /** 单侧凭证 JSON 的字符上限：凭证是给人复盘的，宽行截断比把库顶爆重要。 */
    static final int EVIDENCE_MAX_CHARS = 4000;

    /** 水位未就绪时缺失差异的类型后缀：语义是"可能尚未到达"，不是终判。 */
    static final String PENDING_SUFFIX = "_PENDING";

    private final RuleSpec rule;
    private final long jobId;
    private final int shardId;
    private final String leftKeyField;
    private final String rightKeyField;
    private final List<String> describeColumns;
    private final ReconEngine.Readiness readiness;

    public MergeMatcher(RuleSpec rule, long jobId, int shardId) {
        this(rule, jobId, shardId, ReconEngine.Readiness.allReady());
    }

    public MergeMatcher(RuleSpec rule, long jobId, int shardId, ReconEngine.Readiness readiness) {
        this.rule = rule;
        this.jobId = jobId;
        this.shardId = shardId;
        this.readiness = readiness;
        this.leftKeyField = rule.left().keyField();
        this.rightKeyField = rule.right().keyField();
        this.describeColumns = describeColumns();
    }

    public MatchStats match(RowCursor left, RowCursor right, DiffSink sink) {
        long matchedRows = 0;
        long diffRows = 0;

        Row leftRow = left.peek();
        Row rightRow = right.peek();
        while (leftRow != null && rightRow != null) {
            int cmp = ValueComparator.compareKeys(rule.left().keyType(),
                    leftRow.get(leftKeyField), rightRow.get(rightKeyField));
            if (cmp < 0) {
                diffRows += emitMissingRight(left.next(), sink);
            } else if (cmp > 0) {
                diffRows += emitMissingLeft(right.next(), sink);
            } else {
                List<Row> leftGroup = drainSameKey(left, leftRow, rule.left().keyType(), leftKeyField);
                List<Row> rightGroup = drainSameKey(right, rightRow, rule.right().keyType(), rightKeyField);
                if (leftGroup.size() > 1 || rightGroup.size() > 1) {
                    // 重复键只报差异不配对：组内行序依赖数据库返回顺序，按位凑对会让
                    // 同一份数据两次跑出不同的字段差异集（幂等被打破）。修完数据重跑即可。
                    sink.accept(duplicateDiff(leftGroup, rightGroup),
                            duplicateEvidence(leftGroup, rightGroup));
                    diffRows++;
                } else {
                    matchedRows++;
                    diffRows += compareFields(leftGroup.get(0), rightGroup.get(0), sink);
                }
            }
            leftRow = left.peek();
            rightRow = right.peek();
        }

        // 一侧已经走完，另一侧剩下的行全是单边缺失
        while (leftRow != null) {
            diffRows += emitMissingRight(left.next(), sink);
            leftRow = left.peek();
        }
        while (rightRow != null) {
            diffRows += emitMissingLeft(right.next(), sink);
            rightRow = right.peek();
        }

        sink.flush();
        return new MatchStats(left.scanned(), right.scanned(), matchedRows, diffRows);
    }

    private List<Row> drainSameKey(RowCursor cursor, Row firstRow, FieldType keyType, String keyField) {
        List<Row> group = new ArrayList<>(2);
        Object key = firstRow.get(keyField);
        group.add(cursor.next());
        while (true) {
            Row next = cursor.peek();
            if (next == null || ValueComparator.compareKeys(keyType, next.get(keyField), key) != 0) {
                return group;
            }
            group.add(cursor.next());
        }
    }

    private long compareFields(Row leftRow, Row rightRow, DiffSink sink) {
        long diffs = 0;
        String bizKey = bizKeyOf(leftRow, rightRow);
        for (FieldRule fieldRule : rule.compare()) {
            Object leftValue = leftRow.get(fieldRule.field());
            Object rightValue = rightRow.get(fieldRule.field());
            CompareResult result = ValueComparator.compare(fieldRule, leftValue, rightValue);
            if (result.different()) {
                sink.accept(DiffRecord.of(jobId, shardId, fieldRule.diffType(), fieldRule.severity(), bizKey,
                        fieldRule.field(), Row.render(leftValue), Row.render(rightValue),
                        result.delta(), result.ratio()),
                        List.of(evidence(DiffEvidence.LEFT, bizKey, leftRow),
                                evidence(DiffEvidence.RIGHT, bizKey, rightRow)));
                diffs++;
            }
        }
        return diffs;
    }

    /**
     * 只有左表有 → 右侧（结算）缺失。
     * 右侧水位未就绪时标 {@code *_PENDING}：行可能还没投到右表，这是"未到达"不是"真缺失"。
     */
    private long emitMissingRight(Row leftRow, DiffSink sink) {
        boolean pending = readiness.missingRightPending();
        String type = rule.missing().rightMissingDiffType() + (pending ? PENDING_SUFFIX : "");
        sink.accept(DiffRecord.of(jobId, shardId, type, rule.missing().severity(),
                Row.render(leftRow.get(leftKeyField)), "", describe(leftRow), null, null, null)
                .withExplanation(pending ? pendingNote("右侧", readiness.rightWatermark(), readiness.deadline()) : null),
                List.of(evidence(DiffEvidence.LEFT, Row.render(leftRow.get(leftKeyField)), leftRow)));
        return 1;
    }

    /**
     * 只有右表有 → 左侧（账单）缺失。左侧水位未就绪时同理标 PENDING。
     */
    private long emitMissingLeft(Row rightRow, DiffSink sink) {
        boolean pending = readiness.missingLeftPending();
        String type = rule.missing().leftMissingDiffType() + (pending ? PENDING_SUFFIX : "");
        sink.accept(DiffRecord.of(jobId, shardId, type, rule.missing().severity(),
                Row.render(rightRow.get(rightKeyField)), "", null, describe(rightRow), null, null)
                .withExplanation(pending ? pendingNote("左侧", readiness.leftWatermark(), readiness.deadline()) : null),
                List.of(evidence(DiffEvidence.RIGHT, Row.render(rightRow.get(rightKeyField)), rightRow)));
        return 1;
    }

    private static String pendingNote(String side, java.time.Instant watermark, java.time.Instant deadline) {
        return side + "数据水位未就绪（最新水位 " + (watermark == null ? "无" : watermark)
                + (deadline == null ? "" : "，就绪线 " + deadline)
                + "），该缺失可能是'尚未到达'而非'真缺失'，属于待定判定 —— 等水位就绪后重跑即可出终判。";
    }

    private DiffRecord duplicateDiff(List<Row> leftGroup, List<Row> rightGroup) {
        String bizKey = firstNonNull(Row.render(leftGroup.get(0).get(leftKeyField)),
                Row.render(rightGroup.get(0).get(rightKeyField)));
        return DiffRecord.of(jobId, shardId, rule.duplicate().diffType(), rule.duplicate().severity(), bizKey, "",
                groupSummary(rule.left().label(), leftGroup, leftKeyField),
                groupSummary(rule.right().label(), rightGroup, rightKeyField),
                null, null);
    }

    private String groupSummary(String label, List<Row> rows, String keyField) {
        List<String> ids = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (ids.size() >= MAX_IDS_IN_SUMMARY) {
                ids.add("…");
                break;
            }
            Object id = row.get("id");
            ids.add(Row.render(id != null ? id : row.get(keyField)));
        }
        return label + " " + rows.size() + " 条(id=" + String.join("/", ids) + ")";
    }

    private String describe(Row row) {
        StringBuilder sb = new StringBuilder(64);
        for (String column : describeColumns) {
            Object value = row.get(column);
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(column).append('=').append(Row.render(value));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private List<String> describeColumns() {
        List<String> columns = new ArrayList<>();
        columns.add(leftKeyField);
        if (!columns.contains(rightKeyField)) {
            columns.add(rightKeyField);
        }
        for (FieldRule fieldRule : rule.compare()) {
            if (!columns.contains(fieldRule.field())) {
                columns.add(fieldRule.field());
            }
        }
        return List.copyOf(columns);
    }

    /** 原始行凭证：整行 JSON 快照（超长截断），来源描述用 SideSpec.describe() 的同一口径。 */
    private DiffEvidence evidence(String side, String bizKey, Row row) {
        return new DiffEvidence(jobId, shardId, side, bizKey,
                side.equals(DiffEvidence.LEFT) ? rule.left().describe() : rule.right().describe(),
                row.toJson(EVIDENCE_MAX_CHARS));
    }

    /** 重复键差异的凭证：两侧各取组内第一行做代表 —— 重复键本身就是要人去修的数据问题。 */
    private List<DiffEvidence> duplicateEvidence(List<Row> leftGroup, List<Row> rightGroup) {
        String bizKey = firstNonNull(Row.render(leftGroup.get(0).get(leftKeyField)),
                Row.render(rightGroup.get(0).get(rightKeyField)));
        List<DiffEvidence> evidence = new ArrayList<>(2);
        evidence.add(evidence(DiffEvidence.LEFT, bizKey, leftGroup.get(0)));
        evidence.add(evidence(DiffEvidence.RIGHT, bizKey, rightGroup.get(0)));
        return evidence;
    }

    private String bizKeyOf(Row leftRow, Row rightRow) {
        return firstNonNull(Row.render(leftRow.get(leftKeyField)), Row.render(rightRow.get(rightKeyField)));
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
