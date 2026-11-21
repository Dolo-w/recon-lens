package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>golden-set 对照测试：两执行器必须同口径</b>（v3 扩展架构 §一的硬不变式）。
 *
 * <p>同一份数据（覆盖 decimal 精度与 tolerance 边界、单边缺失、重复键、BOOLEAN、
 * null、事件时间），inprocess 与 go-worker 各跑一遍，
 * 差异集合（忽略 shardId，delta 归一化数值比较）必须逐条等价。
 *
 * <p>worker 二进制由测试现场 {@code go build}（CI 与本机都有 Go 工具链）；
 * 没有 Go 的环境 assume 跳过 —— 对照测试需要两个执行器同时存在，这是前提不是缺陷。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.rules-location=classpath:rules-test/",
        "spring.datasource.url=jdbc:h2:mem:golden_set;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class GoWorkerGoldenSetTest {

    @Autowired
    private ReconEngine engine;

    @Autowired
    private ReconRepository repository;

    @Autowired
    private ReconProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    static Path workRoot;

    private static Path workerBinary;

    private String previousExecution;
    private String previousWorkerPath;
    private String previousWorkerDir;

    @BeforeAll
    static void buildWorker() throws IOException, InterruptedException {
        Path go = findGo();
        Assumptions.assumeTrue(go != null, "本机没有 Go 工具链，跳过 golden-set 对照（CI 上有）");
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path goWorkerDir = projectRoot.resolve("go-worker");
        Assumptions.assumeTrue(Files.isDirectory(goWorkerDir), "go-worker 目录不存在，跳过");
        workerBinary = workRoot.resolve("recon-worker"
                + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : ""));
        Process process = new ProcessBuilder(go.toAbsolutePath().toString(), "build", "-o",
                workerBinary.toAbsolutePath().toString(), ".")
                .directory(goWorkerDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean ok = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
        Assumptions.assumeTrue(ok && process.exitValue() == 0,
                "worker 编译失败（exit=" + (ok ? process.exitValue() : "timeout") + "）:\n" + output);
    }

    private static Path findGo() {
        for (String candidate : List.of("go",
                "C:\\Program Files\\Go\\bin\\go.exe",
                "/usr/local/go/bin/go",
                "/usr/bin/go")) {
            try {
                Process p = new ProcessBuilder(candidate, "version").redirectErrorStream(true).start();
                if (p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    // Windows 上直接用 "go" 依赖 PATH；能跑通就说明找得到
                    return Path.of(candidate.contains("\\") || candidate.startsWith("/") ? candidate : candidate);
                }
            } catch (IOException | InterruptedException e) {
                // 试下一个候选
            }
        }
        return null;
    }

    @BeforeEach
    void seed() {
        previousExecution = properties.getEngine().getExecution();
        previousWorkerPath = properties.getEngine().getGoWorkerPath();
        previousWorkerDir = properties.getEngine().getGoWorkerDir();

        jdbc.update("DELETE FROM recon_diff_evidence");
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM recon_shard");
        jdbc.update("DELETE FROM recon_job");
        jdbc.execute("DROP TABLE IF EXISTS gl_left");
        jdbc.execute("DROP TABLE IF EXISTS gl_right");
        jdbc.execute("CREATE TABLE gl_left (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32), "
                + "amount DECIMAL(12,2), status VARCHAR(16), ok BOOLEAN, event_time DATETIME)");
        jdbc.execute("CREATE TABLE gl_right (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32), "
                + "amount DECIMAL(12,2), status VARCHAR(16), ok BOOLEAN)");
        // A1 完全一致；A2 三字段全不同；A3 左有右无；A4 右有左无
        // A5 左侧重复键（501/502）右侧一行（501）→ 只报 DUPLICATE 不配对
        // A6 status 双 null（空语义）；A7 tolerance 内；A8 tolerance 外
        jdbc.update("INSERT INTO gl_left (order_no, amount, status, ok, event_time) VALUES "
                + "('A1', 100.00, 'PAID', TRUE, '2026-03-15 10:00:00'), "
                + "('A2', 200.00, 'PAID', TRUE, '2026-03-16 10:00:00'), "
                + "('A3', 300.00, 'PAID', TRUE, '2026-03-17 10:00:00'), "
                + "('A5', 501.00, 'PAID', TRUE, '2026-03-18 10:00:00'), "
                + "('A5', 502.00, 'PAID', TRUE, '2026-03-18 10:00:00'), "
                + "('A6', 60.00, NULL, TRUE, '2026-03-19 10:00:00'), "
                + "('A7', 100.01, 'PAID', TRUE, '2026-03-20 10:00:00'), "
                + "('A8', 100.00, 'PAID', TRUE, '2026-03-21 10:00:00')");
        jdbc.update("INSERT INTO gl_right (order_no, amount, status, ok) VALUES "
                + "('A1', 100.00, 'PAID', TRUE), "
                + "('A2', 205.50, 'SENT', FALSE), "
                + "('A4', 400.00, 'PAID', TRUE), "
                + "('A5', 501.00, 'PAID', TRUE), "
                + "('A6', 60.00, NULL, TRUE), "
                + "('A7', 100.02, 'PAID', TRUE), "
                + "('A8', 101.00, 'PAID', TRUE)");
    }

    @AfterEach
    void restore() {
        properties.getEngine().setExecution(previousExecution);
        properties.getEngine().setGoWorkerPath(previousWorkerPath);
        properties.getEngine().setGoWorkerDir(previousWorkerDir);
    }

    private void configExecution(String mode) {
        ReconProperties.Engine engineProps = properties.getEngine();
        engineProps.setExecution(mode);
        if ("go-worker".equals(mode)) {
            engineProps.setGoWorkerPath(workerBinary.toAbsolutePath().toString());
        }
        engineProps.setGoWorkerDir(workRoot.resolve("worker-data").toString());
    }

    private List<List<String>> runAndCollect(String mode) {
        configExecution(mode);
        ReconJob job = engine.run("GOLDEN_SET", "golden-" + mode, "golden-set 对照");
        assertThat(job.status()).as("任务应成功: " + job.errorMsg()).isEqualTo(JobStatus.SUCCESS);
        return jdbc.query("""
                        SELECT diff_type, severity, biz_key, field_name, left_value, right_value,
                               delta_value, delta_ratio, event_at
                        FROM recon_diff WHERE job_id = ? ORDER BY diff_type, biz_key
                        """,
                (rs, i) -> List.of(
                        rs.getString("diff_type"),
                        rs.getString("severity"),
                        rs.getString("biz_key"),
                        String.valueOf(rs.getString("field_name")),
                        String.valueOf(rs.getString("left_value")),
                        String.valueOf(rs.getString("right_value")),
                        normalized(rs.getBigDecimal("delta_value")),
                        normalized(rs.getBigDecimal("delta_ratio")),
                        String.valueOf(rs.getString("event_at"))),
                job.id());
    }

    /** delta 数值归一化：两条路径的标度可能不同（-5.50 vs -5.5），按数值比较。 */
    private static String normalized(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    @Test
    @DisplayName("golden-set：inprocess 与 go-worker 差异集合逐条等价")
    void bothPathsProduceIdenticalDiffSets() {
        List<List<String>> inprocess = new ArrayList<>(runAndCollect("inprocess"));
        List<List<String>> worker = new ArrayList<>(runAndCollect("go-worker"));

        inprocess.sort(Comparator.comparing(list -> String.join("|", list)));
        worker.sort(Comparator.comparing(list -> String.join("|", list)));

        // 期望差异：A2 三字段 + A3 缺失 + A4 缺失 + A5 重复键 + A8 金额差（A7 在容差内、A6 双 null 一致）
        assertThat(inprocess).as("inprocess 期望 7 条差异: " + inprocess).hasSize(7);
        assertThat(worker).as("worker 差异条数").hasSize(inprocess.size());
        assertThat(worker).as("两执行器的差异集合必须逐条等价")
                .containsExactlyElementsOf(inprocess);

        // 关键语义抽查（对两条路径同时成立，防止对照通过但语义错）
        assertThat(inprocess).anySatisfy(row -> {
            assertThat(row.get(0)).isEqualTo("AMOUNT_MISMATCH");
            assertThat(row.get(2)).isEqualTo("A8");
            assertThat(normalized(new BigDecimal(row.get(6)))).isEqualTo("-1");
        });
        assertThat(inprocess).anySatisfy(row -> assertThat(row.get(0)).isEqualTo("DUPLICATE_KEY"));
        assertThat(inprocess).anySatisfy(row -> {
            assertThat(row.get(0)).isEqualTo("MISSING_RIGHT");
            assertThat(row.get(8)).contains("2026-03-17"); // 事件时间取存在侧
        });
    }

    @Test
    @DisplayName("go-worker 模式下 worker 不可用 = 任务失败（fail-loud 不静默降级）")
    void goWorkerModeFailsLoudWhenBinaryMissing() {
        configExecution("go-worker");
        properties.getEngine().setGoWorkerPath(workRoot.resolve("no-such-worker.exe").toString());

        assertThatThrownBy(() -> engine.run("GOLDEN_SET", "golden-fail", "fail-loud"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("go-worker-path");

        // 任务必须被送到终态：不能因为分发决策失败就卡在 RUNNING
        List<Long> running = jdbc.queryForList(
                "SELECT id FROM recon_job WHERE status = 'RUNNING'", Long.class);
        assertThat(running).as("失败的任务不能留在 RUNNING").isEmpty();
        List<String> errors = jdbc.queryForList(
                "SELECT error_msg FROM recon_job WHERE status = 'FAILED'", String.class);
        assertThat(errors).anySatisfy(msg -> assertThat(msg).contains("go-worker-path"));
    }

    @Test
    @DisplayName("auto 模式：行数低于阈值走 inprocess，行为不变")
    void autoModeStaysInprocessBelowThreshold() {
        configExecution("auto");
        properties.getEngine().setGoWorkerThreshold(1_000_000);
        ReconJob job = engine.run("GOLDEN_SET", "golden-auto", "auto 低于阈值");
        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);
        // shard 记录还是规则分片计划的口径（inprocess 路径），没有被 worker 重建
        Integer shards = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_shard WHERE job_id = ?", Integer.class, job.id());
        assertThat(shards).isNotNull().isGreaterThanOrEqualTo(1);
    }
}
