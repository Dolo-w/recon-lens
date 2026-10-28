package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobKind;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.ReconShard;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.domain.TrialPhase;
import io.github.dolow.reconlens.engine.DiffWriter;
import io.github.dolow.reconlens.engine.FieldType;
import io.github.dolow.reconlens.engine.MatchStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 任务 / 分片 / 差异的持久化。全部走 JdbcTemplate，SQL 保持 H2 与 MySQL 都可执行。 */
@Repository
public class ReconRepository implements DiffWriter {

    /** 范围分片键的合法性：纯标识符，进 SQL 前必须过这道闸（表名同样只允许标识符与点）。 */
    private static final String COLUMN_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";


    private static final String DIFF_COLUMNS =
            "id, job_id, shard_id, diff_type, severity, biz_key, field_name, left_value, right_value, delta_value, delta_ratio, explanation";

    private static final String JOB_COLUMNS =
            "id, rule_code, batch_id, status, shard_count, shard_done, left_rows, right_rows, matched_rows, diff_rows, "
                    + "remark, error_msg, created_at, started_at, finished_at";

    private static final String SHARD_COLUMNS =
            "job_id, shard_id, status, attempts, scanned_left, scanned_right, matched_rows, diff_rows, error_msg, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 任务 ----------------

    /**
     * 生产任务的既有入口（保持引入试算之前的行为）。
     *
     * <p>试算走下面那个带 {@code kind} 的重载 —— <b>不是</b>在调用方拼 SQL，也不是给所有查询都加 {@code kind}：
     * per-job 查询天然按 jobId 隔离，要过滤的是"跨任务看趋势"的那些地方（见 {@link JobKind} 的注释）。
     */
    public long createJob(String ruleCode, Integer ruleVersion, String ruleSnapshot, String batchId, String remark, int shardCount) {
        return createJob(ruleCode, ruleVersion, ruleSnapshot, batchId, remark, shardCount, JobKind.PROD, null, null);
    }

    public long createJob(String ruleCode, Integer ruleVersion, String ruleSnapshot, String batchId, String remark,
                          int shardCount, JobKind kind, TrialPhase phase) {
        return createJob(ruleCode, ruleVersion, ruleSnapshot, batchId, remark, shardCount, kind, phase, null);
    }

    /**
     * 建任务，并把"这次读的是哪一份数据"写进 {@code data_snapshot}（ADR-0002 D4）。
     *
     * <p>为什么快照在<b>建任务</b>时写死、而不是查询时现算：任务是一个历史事实，
     * 数据明天被重灌后，任务仍然应该答得出它当时读的是什么。现算等于让历史查询跟着数据变。
     */
    public long createJob(String ruleCode, Integer ruleVersion, String ruleSnapshot, String batchId, String remark,
                          int shardCount, JobKind kind, TrialPhase phase, String dataSnapshot) {
        jdbc.update("INSERT INTO recon_job (rule_code, rule_version, rule_snapshot, data_snapshot, batch_id, status,"
                        + " shard_count, remark, kind, phase) VALUES (?,?,?,?,?,?,?,?,?,?)",
                ruleCode, ruleVersion, ruleSnapshot, dataSnapshot, batchId, JobStatus.PENDING.name(), shardCount,
                remark, kind.name(), phase == null ? null : phase.name());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM recon_job", Long.class);
        return id == null ? -1L : id;
    }

    /** 任务生效的规则版本（内联规则或历史任务可能没有）。 */
    public Integer ruleVersion(long jobId) {
        List<Integer> found = jdbc.queryForList("SELECT rule_version FROM recon_job WHERE id = ?", Integer.class, jobId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 任务创建时的数据快照。老任务（加列之前建的）返回空 —— 调用方必须把"未记录"和
     * "记录为空"分开说，不能混成一句"没有"（ADR-0002 N5）。
     */
    public Optional<String> dataSnapshot(long jobId) {
        List<String> found = jdbc.queryForList("SELECT data_snapshot FROM recon_job WHERE id = ?", String.class, jobId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    /**
     * 比较两个任务时要用的事实（含规则版本与数据快照）。
     *
     * <p>单独一个 record 而不是塞进 {@code ReconJob}：{@code ReconJob} 是"任务本身"，
     * 被十几个地方构造；为了两个只读字段去改它的形状，等于让所有构造点陪跑。
     */
    /**
     * @param datasetVersion ADR-0002 的数据版本号；老任务可能为 null（那时还没有这一列），
     *                       所以案例检索遇到 null 时宁可多召回，也不假装"没有同源案例"
     */
    public record JobFacts(long id, String ruleCode, Integer ruleVersion, String status,
                           long leftRows, long rightRows, long matchedRows, long diffRows,
                           String dataSnapshot, String datasetVersion, java.time.Instant createdAt) {
    }

    public Optional<JobFacts> jobFacts(long jobId) {
        List<JobFacts> rows = jdbc.query(
                "SELECT id, rule_code, rule_version, status, left_rows, right_rows, matched_rows, diff_rows,"
                        + " data_snapshot, dataset_version, created_at FROM recon_job WHERE id = ?",
                (rs, i) -> new JobFacts(rs.getLong("id"), rs.getString("rule_code"),
                        rs.getObject("rule_version") == null ? null : rs.getInt("rule_version"),
                        rs.getString("status"), rs.getLong("left_rows"), rs.getLong("right_rows"),
                        rs.getLong("matched_rows"), rs.getLong("diff_rows"), rs.getString("data_snapshot"),
                        rs.getString("dataset_version"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                jobId);
        return rows.stream().findFirst();
    }

    /**
     * 任务创建时的规则快照：重跑照着它执行，规则后来被改过也不会让历史任务换语义。
     */
    public Optional<String> ruleSnapshot(long jobId) {
        List<String> found = jdbc.queryForList("SELECT rule_snapshot FROM recon_job WHERE id = ?", String.class, jobId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public void createShards(long jobId, int shardCount) {
        for (int shardId = 0; shardId < shardCount; shardId++) {
            jdbc.update("INSERT INTO recon_shard (job_id, shard_id, status) VALUES (?,?,?)",
                    jobId, shardId, ShardStatus.PENDING.name());
        }
    }

    public void markJobRunning(long jobId) {
        jdbc.update("UPDATE recon_job SET status=?, started_at=COALESCE(started_at, CURRENT_TIMESTAMP), error_msg=NULL WHERE id=?",
                JobStatus.RUNNING.name(), jobId);
    }

    /** 重跑前重置状态：把 failed 分片重新排进队列。 */
    public void markJobForRetry(long jobId) {
        jdbc.update("UPDATE recon_job SET status=?, finished_at=NULL, error_msg=NULL WHERE id=?", JobStatus.RUNNING.name(), jobId);
    }

    /** 汇总分片统计并落最终状态。 */
    public void finishJob(long jobId, JobStatus status, String errorMsg) {
        jdbc.update("""
                UPDATE recon_job SET
                  status = ?,
                  shard_done = (SELECT COUNT(*) FROM recon_shard s WHERE s.job_id = recon_job.id AND s.status = 'SUCCESS'),
                  left_rows = (SELECT COALESCE(SUM(s.scanned_left),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  right_rows = (SELECT COALESCE(SUM(s.scanned_right),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  matched_rows = (SELECT COALESCE(SUM(s.matched_rows),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  diff_rows = (SELECT COALESCE(SUM(s.diff_rows),0) FROM recon_shard s WHERE s.job_id = recon_job.id),
                  error_msg = ?,
                  finished_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status.name(), truncate(errorMsg, 1000), jobId);
    }

    public Optional<ReconJob> findJob(long jobId) {
        List<ReconJob> rows = jdbc.query("SELECT " + JOB_COLUMNS + " FROM recon_job WHERE id = ?", jobMapper(), jobId);
        return rows.stream().findFirst();
    }

    /**
     * 任务列表。<b>只列生产任务</b>：试算与 canary 是闸门的内部动作，
     * 混进来会让"最近跑过什么"这个视图变成噪音，也会让人误以为某条规则在生产上跑过。
     */
    public List<ReconJob> listJobs(int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM recon_job WHERE kind = 'PROD'"
                + " ORDER BY id DESC LIMIT ?", jobMapper(), limit);
    }

    /**
     * 任务表总行数（含试算）。给系统事实（{@code ContextFacts}）做廉价失效键用：
     * 每跑一次对账都会多一行，行数变化就说明"最近任务"这张卡片已经旧了。
     *
     * <p>为什么不用 {@code latestProdJobId()}：它会漏掉试算任务，而卡片里最近任务是混着列的。
     */
    public int jobCounter() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_job", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 巡检用：最近若干次<b>生产</b>任务，跨规则、按 id 倒序。
     *
     * <p>为什么不按规则分别查：规则数量是个位数而任务数量是三位数，一次查询 + 本地分组，
     * 比"先查规则有哪几条、再对每条查一次"少一整轮往返，也避免"某条规则没有任何任务时查不到"。
     * {@code kind='PROD'} 是硬要求 —— 试算任务的样本被裁剪过，差异率天然不同，
     * 混进来会直接给环比制造一个假峰（评审 R7）。{@code limit} 由调用方按"窗口 × 规则数"给足。
     */
    public List<ReconJob> recentProdJobs(int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM recon_job WHERE kind = 'PROD'"
                + " ORDER BY id DESC LIMIT ?", jobMapper(), limit);
    }

    /**
     * 跨规则最近一次生产任务。
     *
     * <p>给会话用：用户说「看差异」「跑完了吗」但没给任务号时，默认取最近一次成功/部分成功的生产任务。
     * 只认 {@code kind='PROD'} —— 试算任务是裁剪过的样本，拿来当"最近一次对账"会误导。
     */
    public Optional<Long> latestProdJobId() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM recon_job WHERE kind = 'PROD'"
                        + " AND status IN ('SUCCESS','PARTIAL_FAILED') ORDER BY id DESC LIMIT 1",
                Long.class);
        return ids.stream().findFirst();
    }

    /** 最近一次生产任务（T5-c 的基线分母、T3/T4 的基线差异集都从这里取）。 */
    public Optional<Long> latestProdJobId(String ruleCode) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM recon_job WHERE rule_code = ? AND kind = 'PROD'"
                        + " AND status IN ('SUCCESS','PARTIAL_FAILED') ORDER BY id DESC LIMIT 1",
                Long.class, ruleCode);
        return ids.stream().findFirst();
    }

    /** T5-c 的分母：某次任务里"真的出了数"的分片数（生产也不跨片时，这不构成真空）。 */
    public int shardsWithData(long jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_shard WHERE job_id = ? AND (scanned_left > 0 OR scanned_right > 0)",
                Integer.class, jobId);
        return count == null ? 0 : count;
    }

    /**
     * 某个任务的差异里，业务键落在 {@code [lo, hi]} 范围内的那部分。
     *
     * <p><b>为什么必须限定窗口</b>：基线差异是全量生产数据跑出来的，而新差异跑的是采样窗口 ——
     * 不限定窗口的话，绝大多数基线差异会因为"这次根本没扫到那个键"而看起来"消失了"，
     * T3 会把采样本身的裁剪判成"覆盖损失"。用交集比，才是同一片键空间的对比。
     */
    public List<DiffRecord> diffsInWindow(long jobId, String lo, String hi) {
        return jdbc.query("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE job_id = ? AND biz_key >= ? AND biz_key <= ?",
                diffMapper(), jobId, lo, hi);
    }

    /**
     * 同 {@link #diffsInWindow(long, String, String)}，但按<b>业务键的真实类型</b>比较。
     *
     * <p>{@code biz_key} 是 VARCHAR，而采样窗口的边界来自 {@code SampleMaterializer.literal}：
     * 数值键生成的是不加引号的字面量（{@code 9}），字符串键是加引号的。
     * 如果这里直接在 VARCHAR 上比，{@code '9' >= '10'} 按字典序为真 ——
     * 数值型业务键的窗口会筛出一批<b>和采样窗口不是同一片键空间</b>的行：
     * 基线差异被整批过滤掉，T3 看到的"消失差异"是空集，"消失必须可解释"就自动通过了。
     *
     * <p>所以数值键必须把两边都转型后比较。字符串键保持原样（转成数字会因非数字键而报错）。
     */
    public List<DiffRecord> diffsInWindow(long jobId, String lo, String hi, FieldType keyType) {
        if (keyType != FieldType.LONG && keyType != FieldType.DECIMAL) {
            return diffsInWindow(jobId, lo, hi);
        }
        return jdbc.query("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE job_id = ?"
                        + " AND CAST(biz_key AS DECIMAL(38,8)) BETWEEN CAST(? AS DECIMAL(38,8)) AND CAST(? AS DECIMAL(38,8))",
                diffMapper(), jobId, lo, hi);
    }

    /**
     * 执行一段由调用方拼好的<b>单值</b> SQL（试算侧用它算样本行数 / 交集 / 键集合）。
     *
     * <p>为什么把 SQL 交给仓储执行而不是在 agent 包里直接用 JdbcTemplate：agent 包不该知道表结构，
     * 也不该有能力写库 —— 它只在这里做只读查询，越界的写法一眼能看出来。
     */
    public Integer countRows(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    public List<String> queryKeys(String sql) {
        return jdbc.queryForList(sql, String.class);
    }

    /** 超过保留期的 TRIAL 任务。时间比较用参数传，避免 {@code DATE_SUB}（MySQL）/ {@code DATEADD}（H2）方言分裂。 */
    public List<Long> expiredTrialJobs(Instant cutoff) {
        return jdbc.queryForList("SELECT id FROM recon_job WHERE kind = 'TRIAL' AND created_at < ?",
                Long.class, Timestamp.from(cutoff));
    }

    /** 删除一个任务及其分片、差异。试算数据清理用；生产任务不走这条路。 */
    public void deleteJob(long jobId) {
        jdbc.update("DELETE FROM recon_diff WHERE job_id = ?", jobId);
        jdbc.update("DELETE FROM recon_shard WHERE job_id = ?", jobId);
        jdbc.update("DELETE FROM recon_job WHERE id = ?", jobId);
    }

    // ---------------- 范围分片 ----------------

    /**
     * 探两侧 {@code rangeKey} 列的 MIN/MAX，合并成同一组边界并均分成 {@code count} 段。
     * <b>两侧必须共用同一组边界</b>：各自探各自的，同一业务键就会落进不同分片 —— 那是 SHARD_SPLIT_KEY
     * 想拦的问题在引擎层复活，所以这里取并集（min 取小、max 取大）一次算死。
     *
     * @return {@code bounds[count][2]}，每行 {@code {lo, hi}}，闭区间且互不重叠
     * @throws IllegalStateException 列不是数值 / 表为空 / 表名或列名不合法 —— 范围分片宁可不跑也不静默退化
     */
    public long[][] rangeBounds(String leftTable, String rightTable, String column, int count) {
        if (!column.matches(COLUMN_PATTERN)) {
            throw new IllegalStateException("shard.rangeKey 必须是单一列名（纯标识符），当前: " + column);
        }
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        for (String table : new String[]{leftTable, rightTable}) {
            if (!table.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw new IllegalStateException("shard.rangeKey 分片的表名不合法: " + table);
            }
            // 标识符已过正则闸，拼接安全；数值列 MIN/MAX 在有索引时走索引，代价可忽略
            java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT MIN(" + column + ") AS lo, MAX(" + column + ") AS hi FROM " + table);
            Object min = rows.isEmpty() ? null : rows.get(0).get("lo");
            Object max = rows.isEmpty() ? null : rows.get(0).get("hi");
            if (min == null || max == null) {
                throw new IllegalStateException("范围分片探测失败：" + table + "." + column + " 无数据或不是数值列");
            }
            // 范围分片只支持数值列：字符串列的"均分切点"没有定义（字典序无法线性插值），
            // 与其在这里给一个错误的结果，宁可 fail-loud 让使用者改用 where 取模分片
            if (!(min instanceof Number) || !(max instanceof Number)) {
                throw new IllegalStateException("范围分片只支持数值列：" + table + "." + column
                        + " 的类型是 " + min.getClass().getSimpleName()
                        + "，请改用 where 取模分片（引用业务键，两侧同函数）");
            }
            lo = Math.min(lo, ((Number) min).longValue());
            hi = Math.max(hi, ((Number) max).longValue());
        }
        if (count < 1) {
            throw new IllegalStateException("shard.count 至少为 1");
        }
        long[][] bounds = new long[count][2];
        long span = Math.max(1, hi - lo + 1);
        for (int i = 0; i < count; i++) {
            long segLo = lo + span * i / count;
            long segHi = (i == count - 1) ? hi : lo + span * (i + 1) / count - 1;
            bounds[i][0] = segLo;
            bounds[i][1] = Math.max(segLo, segHi);
        }
        return bounds;
    }

    // ---------------- 分片 ----------------

    public List<ReconShard> listShards(long jobId) {
        return jdbc.query("SELECT " + SHARD_COLUMNS + " FROM recon_shard WHERE job_id = ? ORDER BY shard_id", shardMapper(), jobId);
    }

    /** status 为 null 时返回全部；用于"只重跑失败分片"。 */
    public List<Integer> shardIds(long jobId, ShardStatus status) {
        if (status == null) {
            return jdbc.queryForList("SELECT shard_id FROM recon_shard WHERE job_id = ? ORDER BY shard_id", Integer.class, jobId);
        }
        return jdbc.queryForList("SELECT shard_id FROM recon_shard WHERE job_id = ? AND status = ? ORDER BY shard_id",
                Integer.class, jobId, status.name());
    }

    public void markShardRunning(long jobId, int shardId, int attempts) {
        jdbc.update("""
                UPDATE recon_shard SET status=?, attempts=?, error_msg=NULL, finished_at=NULL,
                       started_at=COALESCE(started_at, CURRENT_TIMESTAMP)
                WHERE job_id=? AND shard_id=?
                """, ShardStatus.RUNNING.name(), attempts, jobId, shardId);
    }

    public void markShardSuccess(long jobId, int shardId, MatchStats stats) {
        jdbc.update("""
                UPDATE recon_shard SET status=?, scanned_left=?, scanned_right=?, matched_rows=?, diff_rows=?,
                       error_msg=NULL, finished_at=CURRENT_TIMESTAMP
                WHERE job_id=? AND shard_id=?
                """, ShardStatus.SUCCESS.name(), stats.scannedLeft(), stats.scannedRight(), stats.matchedRows(),
                stats.diffRows(), jobId, shardId);
    }

    public void markShardFailed(long jobId, int shardId, String errorMsg) {
        jdbc.update("UPDATE recon_shard SET status=?, error_msg=?, finished_at=CURRENT_TIMESTAMP WHERE job_id=? AND shard_id=?",
                ShardStatus.FAILED.name(), truncate(errorMsg, 1000), jobId, shardId);
    }

    // ---------------- 差异 ----------------

    /** 幂等基石：重跑某个分片前先删掉它上一次的差异，再重新写入。 */
    public void deleteShardDiffs(long jobId, int shardId) {
        jdbc.update("DELETE FROM recon_diff WHERE job_id=? AND shard_id=?", jobId, shardId);
    }

    @Override
    public void insertDiffs(List<DiffRecord> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        // 业务键是 NOT NULL。走到这里还是空，说明规则拼出来的键为 NULL（dimension 走 CONCAT，键列有一个 NULL 整键就为 NULL），
        // 直接落库只会得到 "Column 'biz_key' cannot be null" 这种既不指列也不指行的报错，任务整个 FAILED 还查不出原因。
        for (DiffRecord diff : diffs) {
            if (diff.bizKey() == null) {
                throw new IllegalStateException(emptyKeyMessage(diff));
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO recon_diff (job_id, shard_id, diff_type, severity, biz_key, field_name, left_value,
                                        right_value, delta_value, delta_ratio, explanation)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, diffs, diffs.size(), (ps, diff) -> {
            ps.setLong(1, diff.jobId());
            ps.setInt(2, diff.shardId());
            ps.setString(3, diff.diffType());
            ps.setString(4, diff.severity() == null ? Severity.MEDIUM.name() : diff.severity().name());
            ps.setString(5, truncate(diff.bizKey(), 128));
            ps.setString(6, diff.field() == null ? "" : diff.field());
            ps.setString(7, truncate(diff.leftValue(), 512));
            ps.setString(8, truncate(diff.rightValue(), 512));
            ps.setBigDecimal(9, diff.deltaValue());
            ps.setBigDecimal(10, diff.deltaRatio());
            ps.setString(11, truncate(diff.explanation(), 1000));
        });
    }

    /** 业务键为 NULL 时的可定位提示：说清成因、怎么查、怎么修，别只留一句数据库约束报错。 */
    private static String emptyKeyMessage(DiffRecord diff) {
        return "对账键为空（biz_key = NULL），这条差异无法落库，分片会整片失败。"
                + "成因：规则用 dimension 拼键时会生成 CONCAT(列1,'|',列2,…)，键列里只要有一个是 NULL，整个键就是 NULL；"
                + "用 keyField 时该列本身为空也会这样。文件导入会把空字段写成 NULL，所以源数据里的空值就会触发。"
                + "排查：对两侧的键列各跑一次 SELECT COUNT(*) … WHERE 键列 IS NULL；"
                + "处理：先清洗键列（如 UPDATE 表 SET 列='' WHERE 列 IS NULL），或改用不含空值的列做键，或给该列建带默认值的视图。"
                + "本条差异：diffType=" + diff.diffType()
                + "，field=" + (diff.field() == null ? "(记录级)" : diff.field())
                + "，shardId=" + diff.shardId() + "。";
    }

    public long countDiffs(Long jobId, String diffType) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM recon_diff WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (jobId != null) {
            sql.append(" AND job_id = ?");
            args.add(jobId);
        }
        if (diffType != null && !diffType.isBlank()) {
            sql.append(" AND diff_type = ?");
            args.add(diffType);
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<DiffRecord> listDiffs(Long jobId, String diffType, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (jobId != null) {
            sql.append(" AND job_id = ?");
            args.add(jobId);
        }
        if (diffType != null && !diffType.isBlank()) {
            sql.append(" AND diff_type = ?");
            args.add(diffType);
        }
        sql.append(" ORDER BY severity DESC, id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), diffMapper(), args.toArray());
    }

    public Optional<DiffRecord> findDiff(long diffId) {
        return jdbc.query("SELECT " + DIFF_COLUMNS + " FROM recon_diff WHERE id = ?", diffMapper(), diffId)
                .stream().findFirst();
    }

    public void updateExplanation(long diffId, String explanation) {
        jdbc.update("UPDATE recon_diff SET explanation = ? WHERE id = ?", truncate(explanation, 1000), diffId);
    }

    /** 还没写归因说明的差异（批量归因用）。 */
    public List<DiffRecord> listUnexplainedDiffs(long jobId, int limit) {
        return jdbc.query("SELECT " + DIFF_COLUMNS
                        + " FROM recon_diff WHERE job_id = ? AND (explanation IS NULL OR explanation = '') ORDER BY id LIMIT ?",
                diffMapper(), jobId, limit);
    }

    public Map<String, Long> summaryByType(Long jobId) {
        return countBy("diff_type", jobId);
    }

    public Map<String, Long> summaryBySeverity(Long jobId) {
        return countBy("severity", jobId);
    }

    /**
     * 分组计数。{@code jobId == null} 是"全库汇总"—— 这一支<b>必须排除试算</b>：
     * 试算跑的是裁剪样本，把它算进全局分布等于让一份不完整的数据参与统计。
     * 指定 jobId 时不过滤（per-job 查询天然隔离，调用方已经知道自己在看哪一次）。
     */
    private Map<String, Long> countBy(String column, Long jobId) {
        String sql;
        if (jobId == null) {
            sql = "SELECT d." + column + " AS k, COUNT(*) AS c FROM recon_diff d"
                    + " JOIN recon_job j ON j.id = d.job_id WHERE j.kind = 'PROD'"
                    + " GROUP BY d." + column + " ORDER BY c DESC";
        } else {
            sql = "SELECT " + column + " AS k, COUNT(*) AS c FROM recon_diff"
                    + " WHERE job_id = ? GROUP BY " + column + " ORDER BY c DESC";
        }
        Map<String, Long> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jobId == null
                ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql, jobId);
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("k")), ((Number) row.get("c")).longValue());
        }
        return Collections.unmodifiableMap(result);
    }

    // ---------------- RowMapper ----------------

    private RowMapper<ReconJob> jobMapper() {
        return (rs, rowNum) -> new ReconJob(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getString("batch_id"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("shard_count"),
                rs.getInt("shard_done"),
                rs.getLong("left_rows"),
                rs.getLong("right_rows"),
                rs.getLong("matched_rows"),
                rs.getLong("diff_rows"),
                rs.getString("remark"),
                rs.getString("error_msg"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    private RowMapper<ReconShard> shardMapper() {
        return (rs, rowNum) -> new ReconShard(
                rs.getLong("job_id"),
                rs.getInt("shard_id"),
                ShardStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getLong("scanned_left"),
                rs.getLong("scanned_right"),
                rs.getLong("matched_rows"),
                rs.getLong("diff_rows"),
                rs.getString("error_msg"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    private RowMapper<DiffRecord> diffMapper() {
        return (rs, rowNum) -> new DiffRecord(
                rs.getLong("id"),
                rs.getLong("job_id"),
                rs.getInt("shard_id"),
                rs.getString("diff_type"),
                Severity.from(rs.getString("severity")),
                rs.getString("biz_key"),
                rs.getString("field_name"),
                rs.getString("left_value"),
                rs.getString("right_value"),
                rs.getBigDecimal("delta_value"),
                rs.getBigDecimal("delta_ratio"),
                rs.getString("explanation"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
