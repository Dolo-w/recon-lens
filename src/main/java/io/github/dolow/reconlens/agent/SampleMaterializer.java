package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.domain.CoverageReason;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.engine.FieldType;
import io.github.dolow.reconlens.store.TrialSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 把采样窗口物化成<b>普通表</b>（不是 {@code TEMPORARY TABLE}）。
 *
 * <h2>为什么必须是普通表</h2>
 * {@code TEMPORARY TABLE} 是会话级的，而分片是并行执行的、每个分片从连接池里各取各的连接 ——
 * 在别的连接上看不见你建的表，必然 {@code Table doesn't exist}。
 * 这不是理论风险：{@code application.yml} 里池子配 24 就是为了"每个分片同时持有左右两个游标"，
 * 并行度 8 的时候连接一定是分散的。
 *
 * <h2>为什么物化的是"未分片的整表关系"</h2>
 * 只压 {@code sourceSelect + side.where + keyWindow}，<b>不要把 {@code shard.where} 也压进去</b>。
 * 一旦按分片物化，试算就退化成单分片，T4 又变成永绿 —— 那正是"用单分片试算"这个原错误换了个位置复现。
 * 分片条件留给 {@code ShardPlanner.plan()} 在试算运行时展开，行为与生产完全一致。
 */
public class SampleMaterializer {

    private static final Logger log = LoggerFactory.getLogger(SampleMaterializer.class);

    private final JdbcTemplate jdbc;
    private final TrialSampleRepository leases;

    public SampleMaterializer(JdbcTemplate jdbc, TrialSampleRepository leases) {
        this.jdbc = jdbc;
        this.leases = leases;
    }

    /** 一次试算的样本表。{@code runId} 进表名，并发试算互不相撞。 */
    public record Materialized(String runId, String tableLeft, String tableRight) {
    }

    /**
     * @param window 已取好的窗口；{@code lo == null} 时说明两侧都没数据，直接物化空表
     * @throws SampleBlockedException 无 DDL 权限 / CTAS 报错 / 超出字节上限
     */
    public Materialized materialize(RuleSpec rule, SampleWindowProbe.Window window, long maxSampleBytes) {
        String runId = newRunId();
        Materialized tables = new Materialized(runId, "trial_" + runId + "_l", "trial_" + runId + "_r");
        // 先登记再建表。反过来的话会留下"表已存在、登记还没有"的窗口，而孤儿回收正是按
        // "表存在但无登记 = 老残留"来判定的 —— 那个窗口里自己的表会被自己删掉。
        leases.register(runId, tables.tableLeft(), tables.tableRight(), Instant.now());
        try {
            createSampleTable(tables.tableLeft(), rule.left(), window.lo(), window.hi(), window.windowRows());
            createSampleTable(tables.tableRight(), rule.right(), window.lo(), window.hi(), window.windowRows());
            requireWithinByteLimit(tables, maxSampleBytes);
            return tables;
        } catch (SampleBlockedException e) {
            // 已经建了一张就把它清掉：半张样本表既没用，又会在下次清理时变成"来源不明"的残留
            drop(tables);
            throw e;
        }
    }

    /** 续租。由工作节点在关键节点调用（见 {@code ShadowTrialService}），不依赖任何守护线程。 */
    public void heartbeat(Materialized tables) {
        if (tables != null) {
            leases.heartbeat(tables.runId(), Instant.now());
        }
    }

    public void drop(Materialized tables) {
        if (tables == null) {
            return;
        }
        for (String table : new String[]{tables.tableLeft(), tables.tableRight()}) {
            dropTable(table);
        }
        try {
            // 表先删、登记后删：登记行的含义是"表在用"，表没了登记就该没。
            // 反过来的话中间的窗口里，登记消失但表还在 —— 那张表会被孤儿回收再删一次（无害，但会重复计数）。
            leases.unregister(tables.runId());
        } catch (RuntimeException e) {
            log.warn("注销试算租约 {} 失败（该行会在租约过期后被清理）: {}", tables.runId(), e.toString());
        }
    }

    /**
     * 回收残留样本表。两件事，判据不同，不能混成一件：
     *
     * <ol>
     *   <li><b>租约过期</b>：表在、登记也在，但心跳停了 —— 拥有它的实例已经死了（或卡住了）。
     *       这是唯一会删"有主"表的路径，所以门槛是 {@code leaseTtl}（默认 30 分钟，
     *       而一次正常试算约 1 分钟，余量 30 倍）。</li>
     *   <li><b>孤儿</b>：表在、登记不在 —— 只可能来自租约机制上线之前的老残留。
     *       新代码一定是先登记再建表，所以这不可能是"并发中"的状态。</li>
     * </ol>
     *
     * @param leaseTtl 心跳超过多久算拥有者已死；比一次试算的正常时长<b>大一个量级</b>才有意义
     * @return 实际删掉的表数
     */
    public int cleanupResidual(Instant now, Duration leaseTtl) {
        List<String> present;
        try {
            present = scanTrialTables();
        } catch (RuntimeException e) {
            // 连"库里有哪些 trial_ 表"都查不到时不做任何猜测：宁可这轮不清，也不要盲删
            log.warn("扫描残留样本表失败，本轮跳过清理: {}", e.toString());
            return 0;
        }
        if (present.isEmpty()) {
            return 0;
        }
        // DROP 用库里的原始表名，匹配用 lower：MySQL 在大小写敏感的文件系统上，
        // 把表名小写后再 DROP 会报"表不存在"，而这里恰恰要删的是"认不出来历"的老残留
        Set<String> presentLower = new HashSet<>();
        for (String table : present) {
            presentLower.add(lower(table));
        }

        Set<String> handledLower = new HashSet<>();
        int dropped = 0;

        for (TrialSampleRepository.Lease lease : leases.expired(now.minus(leaseTtl))) {
            for (String table : new String[]{lease.tableLeft(), lease.tableRight()}) {
                handledLower.add(lower(table));
                if (presentLower.contains(lower(table))) {
                    dropTable(table);
                    dropped++;
                }
            }
            leases.unregister(lease.runId());
            log.warn("回收租约过期的样本表（run_id={}, owner={}, 最后心跳={}）：{}/{}",
                    lease.runId(), lease.owner(), lease.heartbeatAt(), lease.tableLeft(), lease.tableRight());
        }

        for (String table : present) {
            if (handledLower.contains(lower(table))) {
                continue;
            }
            if (!leases.isRegistered(table)) {
                dropTable(table);
                dropped++;
                log.warn("回收无主样本表 {}（无租约登记：机制上线前的老残留，或登记写入前就崩了）", table);
            }
        }
        return dropped;
    }

    private List<String> scanTrialTables() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE LOWER(table_name) LIKE 'trial!_%' ESCAPE '!'",
                String.class);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    /** @return 是否执行了 DROP（{@code IF EXISTS} 下无法区分"本来就没有"，计数由调用方按扫描结果给） */
    private boolean dropTable(String table) {
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
            return true;
        } catch (RuntimeException e) {
            log.warn("删除样本表 {} 失败（下一轮清理会再试）: {}", table, e.toString());
            return false;
        }
    }

    private void createSampleTable(String table, SideSpec side, String lo, String hi, int windowRows) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("CREATE TABLE ").append(table).append(" AS SELECT * FROM (")
                .append(side.sourceSelect()).append(") src WHERE ");
        boolean hasSideWhere = side.where() != null && !side.where().isBlank();
        if (hasSideWhere) {
            sql.append('(').append(side.where()).append(") AND ");
        }
        if (lo == null || hi == null) {
            // 空窗口：用恒假谓词建一张空表，让后面的判定能正常走到 T5-a，而不是在物化阶段就炸掉。
            // 不写成 "WHERE 1=0" 是因为那样会丢掉 collation / 列类型等 CTAS 信息，后续比对字段会报"列不存在"。
            sql.append("1 = 0");
        } else {
            String literalLo = literal(side, lo);
            String literalHi = literal(side, hi);
            sql.append('(').append(side.keyExpression()).append(" BETWEEN ")
                    .append(literalLo).append(" AND ").append(literalHi).append(')');
        }
        // 确定性截断：从 lo 起取，不随机。两次试算必须看到同一份样本，否则 T2 的"幂等"断言会变成
        // "两次采样是否碰巧一样"，而那是数据分布问题，不是规则问题。
        sql.append(" ORDER BY ").append(side.keyExpression())
                .append(" LIMIT ").append(Math.max(1, windowRows));

        try {
            jdbc.execute(sql.toString());
        } catch (RuntimeException e) {
            // 只读账号（无 CREATE）、跨库权限、视图副作用都会落到这里。
            // 这里不做"能不能物化"的猜测：报错就是没物化成功，降级并留下原始错误。
            throw new SampleBlockedException(CoverageReason.NO_DDL_PRIVILEGE,
                    "物化样本表 " + table + " 失败（可能没有 CREATE 权限）: " + e.getMessage(), e);
        }
    }

    /**
     * 字节上限检查。<b>只在库能报出表大小时生效</b>（MySQL 的 {@code information_schema} 有 {@code data_length}，
     * H2 没有）—— 查不到就跳过，并在报告里写明"未计量"，而不是假装检查过。
     */
    private void requireWithinByteLimit(Materialized tables, long maxSampleBytes) {
        long total = 0;
        for (String table : new String[]{tables.tableLeft(), tables.tableRight()}) {
            Long bytes = tableBytes(table);
            if (bytes == null) {
                return;
            }
            total += bytes;
        }
        if (total > maxSampleBytes) {
            throw new SampleBlockedException(CoverageReason.SAMPLE_TOO_SMALL,
                    "样本 " + total + " 字节超过 recon.trial.max-sample-bytes=" + maxSampleBytes + "，需要缩窗重试");
        }
    }

    private Long tableBytes(String table) {
        try {
            List<Long> found = jdbc.queryForList(
                    "SELECT data_length + index_length FROM information_schema.tables"
                            + " WHERE LOWER(table_name) = LOWER(?)", Long.class, table);
            return found.isEmpty() ? null : found.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 键列的字面量：字符串必须加引号并转义单引号；数值直接写。 */
    static String literal(SideSpec side, String value) {
        if (value == null) {
            return "NULL";
        }
        FieldType type = side.keyType();
        if (type == FieldType.LONG || type == FieldType.DECIMAL) {
            return value;
        }
        if (type == FieldType.BOOLEAN) {
            return "TRUE".equalsIgnoreCase(value) ? "TRUE" : "FALSE";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String newRunId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
