package io.github.dolow.reconlens.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.store.AgentRunRepository;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 巡检的判据与"别喊第二遍"。
 *
 * <p>这里最值得看的是<b>抑制之后仍然看得见</b>那一条：抑制不是让问题消失，
 * 而是把它从"新发现"挪到"我知道，别重复喊"。如果实现成"抑制 = 丢弃"，
 * 报告上会显示"一切正常" —— 那比不抑制更危险。
 */
class PerceptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private final ReconRepository repository = mock(ReconRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentProperties properties = new AgentProperties();

    /** 可推进的时钟：抑制窗口是按分钟算的，不能靠 sleep 等。 */
    private final MutableClock clock = new MutableClock(NOW);
    private PerceptionService service;

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        service = new PerceptionService(repository, runs, properties, new ObjectMapper(), clock);
        when(runs.startRun(anyString(), anyString(), anyString())).thenReturn(1L);
        when(runs.nextSeq(anyLong())).thenReturn(1);
    }

    private static ReconJob job(long id, String ruleCode, JobStatus status, long leftRows, long diffRows) {
        return new ReconJob(id, ruleCode, "B1", status, 1, 1, leftRows, leftRows, leftRows - diffRows, diffRows,
                null, null, NOW, NOW, NOW);
    }

    private void jobs(ReconJob... jobs) {
        when(repository.recentProdJobs(anyInt())).thenReturn(new ArrayList<>(List.of(jobs)));
    }

    private void types(long jobId, String... types) {
        Map<String, Long> map = new java.util.LinkedHashMap<>();
        for (String type : types) {
            map.put(type, 1L);
        }
        when(repository.summaryByType(eq(jobId))).thenReturn(map);
    }

    private void severities(long jobId, Map<String, Long> map) {
        when(repository.summaryBySeverity(eq(jobId))).thenReturn(map);
    }

    @Test
    void noFinishedJobsProducesAnEmptyReportButStillLeavesATrace() {
        jobs();

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.trends()).isEmpty();
        assertThat(report.clean()).isTrue();
        assertThat(report.describe()).contains("无已完成的生产任务");
        // "巡检在跑"这件事本身要能证明，所以没异常也落轨迹
        verify(runs).startRun(anyString(), eq("SCHEDULED"), anyString());
    }

    @Test
    void aJumpInDiffRateIsReportedAsHigh() {
        jobs(job(2, "BILL", JobStatus.SUCCESS, 100, 50), job(1, "BILL", JobStatus.SUCCESS, 100, 20));
        types(2, "AMOUNT_MISMATCH");
        types(1, "AMOUNT_MISMATCH");
        severities(2, Map.of("HIGH", 10L, "LOW", 40L));

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.findings())
                .anySatisfy(finding -> {
                    assertThat(finding.kind()).isEqualTo("DIFF_RATE_JUMP");
                    assertThat(finding.level()).isEqualTo(PerceptionService.Level.HIGH);
                    assertThat(finding.message()).contains("2.50 倍");
                });
    }

    @Test
    void aDiffTypeNeverSeenInTheWindowIsReported() {
        jobs(job(2, "BILL", JobStatus.SUCCESS, 100, 5), job(1, "BILL", JobStatus.SUCCESS, 100, 5));
        types(2, "AMOUNT_MISMATCH", "MISSING_RIGHT");
        types(1, "AMOUNT_MISMATCH");
        severities(2, Map.of("LOW", 5L));

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.findings())
                .anySatisfy(finding -> {
                    assertThat(finding.kind()).isEqualTo("NEW_DIFF_TYPE");
                    assertThat(finding.message()).contains("MISSING_RIGHT").doesNotContain("AMOUNT_MISMATCH");
                });
    }

    @Test
    void partialFailedIsReportedBecauseTheDiffCountIsNotTrustworthy() {
        jobs(job(2, "BILL", JobStatus.PARTIAL_FAILED, 100, 5), job(1, "BILL", JobStatus.SUCCESS, 100, 5));
        types(2, "AMOUNT_MISMATCH");
        types(1, "AMOUNT_MISMATCH");
        severities(2, Map.of("LOW", 5L));

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.findings())
                .anySatisfy(finding -> assertThat(finding.kind()).isEqualTo("PARTIAL_FAILED"));
    }

    /** 抑制不是丢弃：第二次同一异常仍在报告里，只是不在"新发现"里。 */
    @Test
    void theSameFindingIsSuppressedButStillVisible() {
        jobs(job(2, "BILL", JobStatus.SUCCESS, 100, 50), job(1, "BILL", JobStatus.SUCCESS, 100, 20));
        types(2, "AMOUNT_MISMATCH");
        types(1, "AMOUNT_MISMATCH");
        severities(2, Map.of("HIGH", 10L, "LOW", 40L));

        PerceptionService.PerceptionReport first = service.scan();
        PerceptionService.PerceptionReport second = service.scan();

        assertThat(first.suppressed()).isEmpty();
        assertThat(second.findings()).isEmpty();
        assertThat(second.suppressed())
                .as("抑制必须可见：否则「没报」会被读成「没问题」")
                .isNotEmpty()
                .extracting(PerceptionService.Finding::kind)
                .contains("DIFF_RATE_JUMP");
        assertThat(second.describe()).contains("抑制窗口内未重复上报");
    }

    @Test
    void suppressionExpiresAfterTheConfiguredWindow() {
        jobs(job(2, "BILL", JobStatus.SUCCESS, 100, 50), job(1, "BILL", JobStatus.SUCCESS, 100, 20));
        types(2, "AMOUNT_MISMATCH");
        types(1, "AMOUNT_MISMATCH");
        severities(2, Map.of("HIGH", 10L, "LOW", 40L));

        service.scan();
        clock.advance(Duration.ofMinutes(properties.getAlertSuppressMinutes() + 1));
        PerceptionService.PerceptionReport later = service.scan();

        assertThat(later.findings())
                .as("超过抑制窗口后必须重新上报，否则一个长期异常会在第一次之后就永远沉默")
                .isNotEmpty();
    }

    @Test
    void runningJobsAreNotTreatedAsFacts() {
        jobs(job(2, "BILL", JobStatus.RUNNING, 100, 0), job(1, "BILL", JobStatus.SUCCESS, 100, 20));
        types(1, "AMOUNT_MISMATCH");
        severities(1, Map.of("LOW", 20L));

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.trends()).hasSize(1);
        assertThat(report.trends().get(0).jobId()).isEqualTo(1L);
        assertThat(report.findings())
                .anySatisfy(finding -> assertThat(finding.kind()).isEqualTo("NO_BASELINE"));
    }

    @Test
    void oneJobMeansNoComparisonAtAll() {
        jobs(job(1, "BILL", JobStatus.SUCCESS, 100, 20));
        types(1, "AMOUNT_MISMATCH");
        severities(1, Map.of("LOW", 20L));

        PerceptionService.PerceptionReport report = service.scan();

        assertThat(report.trends().get(0).previousDiffRate()).isNull();
        assertThat(report.trends().get(0).changeRatio())
                .as("没有上一期时环比必须是 null，不能是 0 或 1 —— 0% 与「不知道」是两件事")
                .isNull();
    }
}
