package io.github.dolow.reconlens.store;

import io.github.dolow.reconlens.domain.JobKind;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 试算不污染生产视图（验收 A9 的另一半）。
 *
 * <p>A9 原文要求"跑一次 TRIAL 后巡检的环比指标不变"。这条断言只有在巡检真的从
 * {@link ReconRepository#recentProdJobs} 取数、而那个方法真的带 {@code kind='PROD'} 时才有意义 ——
 * 所以这里直接钉住 SQL 的条件，而不是钉住某个 service 的返回值。
 *
 * <p>为什么这件事值得单独一个测试类：污染是<b>静默</b>的。试算任务的样本被裁剪过，
 * 差异率天然和全量不同；一旦混进基线，巡检会持续报警或持续沉默，两种结果都很难归因到"多查了一张表"。
 */
class ReconRepositoryProdScopeTest {

    private static final String DDL_JOB = """
            CREATE TABLE IF NOT EXISTS recon_job (
              id            BIGINT AUTO_INCREMENT PRIMARY KEY,
              rule_code     VARCHAR(64)  NOT NULL,
              rule_version  INT,
              rule_snapshot TEXT,
              batch_id      VARCHAR(64)  NOT NULL,
              status        VARCHAR(16)  NOT NULL,
              kind          VARCHAR(8)   NOT NULL DEFAULT 'PROD',
              phase         VARCHAR(8)   NULL,
              shard_count   INT          NOT NULL,
              shard_done    INT          NOT NULL DEFAULT 0,
              left_rows     BIGINT       NOT NULL DEFAULT 0,
              right_rows    BIGINT       NOT NULL DEFAULT 0,
              matched_rows  BIGINT       NOT NULL DEFAULT 0,
              diff_rows     BIGINT       NOT NULL DEFAULT 0,
              remark        VARCHAR(255),
              error_msg     VARCHAR(1000),
              created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              started_at    TIMESTAMP,
              finished_at   TIMESTAMP
            )
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:recon_job_prod_scope;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final ReconRepository repository = new ReconRepository(jdbc);

    @BeforeEach
    void reset() {
        jdbc.execute(DDL_JOB);
        jdbc.update("DELETE FROM recon_job");
    }

    private long job(String ruleCode, JobKind kind, JobStatus status, long leftRows, long diffRows) {
        long id = repository.createJob(ruleCode, 1, "{}", "B1", null, 1, kind, null);
        jdbc.update("UPDATE recon_job SET status = ?, left_rows = ?, diff_rows = ? WHERE id = ?",
                status.name(), leftRows, diffRows, id);
        return id;
    }

    @Test
    void recentProdJobsSkipsTrialRuns() {
        job("BILL_VS_SETTLE", JobKind.PROD, JobStatus.SUCCESS, 100, 5);
        job("BILL_VS_SETTLE", JobKind.TRIAL, JobStatus.SUCCESS, 10, 9);

        List<ReconJob> jobs = repository.recentProdJobs(10);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).leftRows()).isEqualTo(100);
        assertThat(jobs).allSatisfy(j -> assertThat(j.status()).isEqualTo(JobStatus.SUCCESS));
    }

    /** 试算的差异率 90% 若进了基线，巡检会在每次试算后报一次"差异率暴涨" —— 这就是误报的来源。 */
    @Test
    void aTrialRunCannotBecomeTheBaselineOfThePerceptionScan() {
        job("BILL_VS_SETTLE", JobKind.PROD, JobStatus.SUCCESS, 100, 2);
        job("BILL_VS_SETTLE", JobKind.TRIAL, JobStatus.SUCCESS, 10, 9);
        job("BILL_VS_SETTLE", JobKind.PROD, JobStatus.SUCCESS, 100, 3);

        List<ReconJob> jobs = repository.recentProdJobs(10);

        assertThat(jobs).extracting(ReconJob::diffRows).containsExactly(3L, 2L);
    }

    @Test
    void listJobsIsProdOnlyAsWell() {
        job("BILL_VS_SETTLE", JobKind.PROD, JobStatus.SUCCESS, 100, 5);
        job("BILL_VS_SETTLE", JobKind.TRIAL, JobStatus.SUCCESS, 10, 9);

        assertThat(repository.listJobs(10)).hasSize(1);
    }
}
