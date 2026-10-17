package io.github.dolow.reconlens;

import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 百万级压测：证明「引擎内存占用与数据量无关」而不只是嘴上说说。
 *
 * <p>默认不随 {@code mvn test} 执行（{@code @Tag("bench")} 已在 pom 的 surefire.excludedGroups 里排除）。
 * 手动跑法见 docs/benchmark.md：
 * <pre>
 * mvn -Dtest=BenchH2Test -Dsurefire.excludedGroups= \
 *     -Dsurefire.argLine="-Xmx2g -Dfile.encoding=UTF-8" test
 * </pre>
 * <p>两个参数都不能省：pom 里的排除项写的是 {@code ${surefire.excludedGroups}}，只有 {@code -Dsurefire.excludedGroups=}
 * 才能把它覆盖成空（写成 {@code -DexcludedGroups=} 不会生效，表现是 <b>BUILD SUCCESS 但一条 bench 都没跑</b>）；
 * 堆也别按"引擎内存与数据量无关"往小里压 —— 那说的是引擎，H2 是嵌入式库、跟被测代码同一个堆，{@code -Xmx256m}
 * 会在对账途中直接 {@code 90098 The database has been closed}（与本次改动无关，改动前的提交同样如此）。
 *
 * <p>数据落在 H2 文件库（{@code ./target/bench-db}）而不是内存库，避免把 100 万行算进被测 JVM 的堆，
 * 否则测出来的就不是引擎的堆占用而是数据库的。
 */
@Tag("bench")
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "spring.sql.init.mode=always",
        "recon.rules-location=classpath:bench-rules/"
})
class BenchH2Test {

    /** 每次跑都用全新的文件库：schema.sql 不是幂等的，重复跑同一个库会报表已存在。 */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:./target/bench-db-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }

    private static final int N = 1_000_000;
    private static final long EXPECT_LEFT = N;
    private static final long EXPECT_RIGHT = N - 100 + 100 + 100;   // 缺 100 / 重复 100 / 右侧独有 100

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ReconEngine engine;
    @Autowired
    private ReconRepository repository;

    @Test
    void millionRowsWithEightShardsAndSingleShard() {
        prepare();

        long leftRows = count("big_left");
        long rightRows = count("big_right");
        System.out.printf("[bench] 造数完成：big_left=%d big_right=%d%n", leftRows, rightRows);
        assertThat(leftRows).isEqualTo(EXPECT_LEFT);
        assertThat(rightRows).isEqualTo(EXPECT_RIGHT);

        Result sharded = time("BIG_VS_SETTLE", leftRows, rightRows);
        Result single = time("BIG_VS_SETTLE_SINGLE", leftRows, rightRows);

        System.out.printf("[bench] 分片口径对比：8 分片 %d ms，1 分片 %d ms，差异分类完全一致=%s%n",
                sharded.elapsedMs, single.elapsedMs, sharded.byType.equals(single.byType));

        // 分片只影响耗时，不影响结果 —— 这是分片设计的前提，用百万级数据实测
        assertThat(single.byType).isEqualTo(sharded.byType);
        assertThat(sharded.job.diffRows()).isEqualTo(800L);
        assertThat(sharded.job.leftRows()).isEqualTo(EXPECT_LEFT);
        assertThat(sharded.job.rightRows()).isEqualTo(EXPECT_RIGHT);

        Map<String, Long> byType = sharded.byType;
        assertThat(byType).containsEntry("AMOUNT_MISMATCH", 400L);
        assertThat(byType).containsEntry("STATUS_MISMATCH", 100L);
        assertThat(byType).containsEntry("MISSING_RIGHT", 100L);
        assertThat(byType).containsEntry("DUPLICATE_KEY", 100L);
        assertThat(byType).containsEntry("MISSING_LEFT", 100L);
    }

    private Result time(String ruleCode, long leftRows, long rightRows) {
        long heapBefore = usedHeapMb();
        long start = System.nanoTime();
        ReconJob job = engine.run(ruleCode, "bench-" + System.nanoTime(), "百万级压测");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long heapAfter = usedHeapMb();
        Map<String, Long> byType = repository.summaryByType(job.id());

        double rowsPerSecond = elapsedMs == 0 ? 0d : (leftRows + rightRows) * 1000d / elapsedMs;
        System.out.printf("[bench] %s：任务 #%d 状态=%s 分片=%d 耗时=%d ms（%.2f 万行/秒）%n",
                ruleCode, job.id(), job.status(), job.shardCount(), elapsedMs, rowsPerSecond / 10_000d);
        System.out.printf("[bench]   左侧扫描=%d 右侧扫描=%d 匹配=%d 差异=%d%n",
                job.leftRows(), job.rightRows(), job.matchedRows(), job.diffRows());
        System.out.printf("[bench]   差异分类=%s%n", byType);
        System.out.printf("[bench]   堆占用：跑前 %d MB，跑后 %d MB（JVM 上限 %d MB）%n",
                heapBefore, heapAfter, Runtime.getRuntime().maxMemory() / 1024 / 1024);
        assertThat(job.status().name()).isEqualTo("SUCCESS");
        return new Result(job, elapsedMs, byType);
    }

    private record Result(ReconJob job, long elapsedMs, Map<String, Long> byType) {
    }

    /** 造数：100 万行账单 + 100.01 万行结算，埋 5 类差异（数量见 EXPECT_* 与断言）。 */
    private void prepare() {
        jdbc.execute("DROP TABLE IF EXISTS big_left");
        jdbc.execute("DROP TABLE IF EXISTS big_right");
        jdbc.execute("CREATE TABLE big_left  (id BIGINT, order_no VARCHAR(32), amount DECIMAL(18,2), status VARCHAR(16))");
        jdbc.execute("CREATE TABLE big_right (id BIGINT, order_no VARCHAR(32), amount DECIMAL(18,2), status VARCHAR(16))");

        // 左侧 100 万：金额 = 100 + n%9000/100，状态 n%11=0 为 UNPAID
        // 注意 "X" 必须加引号：SYSTEM_RANGE 的列名是大写 X，而本项目默认 DATABASE_TO_LOWER=TRUE
        jdbc.execute("INSERT INTO big_left (id, order_no, amount, status) " +
                "SELECT \"X\", 'ORD' || LPAD(CAST(\"X\" AS VARCHAR), 10, '0'), " +
                "       CAST(100 + MOD(\"X\", 9000) / 100.0 AS DECIMAL(18,2)), " +
                "       CASE WHEN MOD(\"X\", 11) = 0 THEN 'UNPAID' ELSE 'PAID' END " +
                "FROM SYSTEM_RANGE(1, " + N + ")");

        // 右侧主体：n%10000=0 的 100 个键不发（→ 右侧缺失）；n%2000=0 金额 -1；n%5000=0 状态取反
        jdbc.execute("INSERT INTO big_right (id, order_no, amount, status) " +
                "SELECT \"X\", 'ORD' || LPAD(CAST(\"X\" AS VARCHAR), 10, '0'), " +
                "       CASE WHEN MOD(\"X\", 2000) = 0 THEN CAST(100 + MOD(\"X\", 9000) / 100.0 - 1 AS DECIMAL(18,2)) " +
                "            ELSE CAST(100 + MOD(\"X\", 9000) / 100.0 AS DECIMAL(18,2)) END, " +
                "       CASE WHEN MOD(\"X\", 5000) = 0 " +
                "                 THEN (CASE WHEN MOD(\"X\", 11) = 0 THEN 'PAID' ELSE 'UNPAID' END) " +
                "            ELSE (CASE WHEN MOD(\"X\", 11) = 0 THEN 'UNPAID' ELSE 'PAID' END) END " +
                "FROM SYSTEM_RANGE(1, " + N + ") WHERE MOD(\"X\", 10000) <> 0");

        // n%10000=1 的 100 个键在右侧各多一条（→ 重复键）
        jdbc.execute("INSERT INTO big_right (id, order_no, amount, status) " +
                "SELECT 9000000 + id, order_no, amount, status FROM big_left WHERE MOD(id, 10000) = 1");

        // 右侧独有的 100 个键（→ 左侧缺失）
        jdbc.execute("INSERT INTO big_right (id, order_no, amount, status) " +
                "SELECT 8000000 + \"X\", 'ORD' || LPAD(CAST(" + N + " + \"X\" AS VARCHAR), 10, '0'), 1234.00, 'PAID' " +
                "FROM SYSTEM_RANGE(1, " + N + ") WHERE MOD(\"X\", 10000) = 2");

        // 归并依赖按业务键升序取数，建索引让数据库来排序而不是把全表读进堆
        jdbc.execute("CREATE INDEX idx_big_left_key  ON big_left(order_no)");
        jdbc.execute("CREATE INDEX idx_big_right_key ON big_right(order_no)");
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
    }
}
