package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租约登记表本身的语义。这里不碰 {@code SampleMaterializer} —— 只验"登记/续租/过期/注销"
 * 这四件事的判据，剩下的（什么时候调、失败怎么办）在 {@code ShadowTrialServiceTest} 里测。
 */
class TrialSampleRepositoryTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS recon_trial_sample (
              run_id       VARCHAR(32) NOT NULL PRIMARY KEY,
              table_left   VARCHAR(64) NOT NULL,
              table_right  VARCHAR(64) NOT NULL,
              owner        VARCHAR(96) NOT NULL,
              heartbeat_at TIMESTAMP   NOT NULL,
              created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:trial_lease;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final TrialSampleRepository leases = new TrialSampleRepository(jdbc, "host-test-0001");

    private static final Instant T0 = Instant.parse("2026-10-17T08:00:00Z");

    @BeforeEach
    void reset() {
        jdbc.execute(DDL);
        jdbc.update("DELETE FROM recon_trial_sample");
    }

    @Test
    void registerMakesBothTablesLookOwned() {
        leases.register("run1", "trial_run1_l", "trial_run1_r", T0);

        assertThat(leases.isRegistered("trial_run1_l")).isTrue();
        assertThat(leases.isRegistered("trial_run1_r")).as("左表和右表指向同一次试算").isTrue();
        assertThat(leases.isRegistered("trial_run2_l")).isFalse();
        assertThat(leases.owner()).isEqualTo("host-test-0001");
    }

    @Test
    void expiredComparesAgainstTheHeartbeatNotTheCreationTime() {
        leases.register("run1", "trial_run1_l", "trial_run1_r", T0);
        leases.heartbeat("run1", T0.plusSeconds(600));         // 10 分钟后还在推进

        assertThat(leases.expired(T0.plusSeconds(300)))
                .as("截止点早于最后一次心跳 → 它没超时").isEmpty();
        assertThat(leases.expired(T0.plusSeconds(900)))
                .as("截止点晚于最后一次心跳 → 判死").hasSize(1);
        List<TrialSampleRepository.Lease> expired = leases.expired(T0.plusSeconds(900));
        assertThat(expired.get(0).runId()).isEqualTo("run1");
        assertThat(expired.get(0).tableLeft()).isEqualTo("trial_run1_l");
    }

    @Test
    void heartbeatFailsLoudlyWhenTheLeaseIsAlreadyGone() {
        // 另一个实例判我死了、把登记删了 —— 此刻我的表随时可能被 DROP。
        // 继续跑得到的结果不可信，所以这里必须炸，而不是记一条 warn 往下走。
        assertThatThrownBy(() -> leases.heartbeat("never-registered", T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约已丢失")
                .hasMessageContaining("never-registered");
    }

    @Test
    void unregisterReleasesTheClaim() {
        leases.register("run1", "trial_run1_l", "trial_run1_r", T0);
        leases.unregister("run1");

        assertThat(leases.isRegistered("trial_run1_l")).isFalse();
        assertThat(leases.expired(T0.plusSeconds(86400))).isEmpty();
    }

    @Test
    void defaultOwnerIsTraceableButNotStable() {
        String first = TrialSampleRepository.defaultOwner();
        String second = TrialSampleRepository.defaultOwner();

        assertThat(first).isNotBlank().hasSizeLessThanOrEqualTo(96);
        // 后缀随机是**有意的**：重启后换标识，旧标识的租约就会自然过期。
        // 如果标识稳定，一个反复重启的实例会不断"续命"自己上次崩溃留下的表。
        assertThat(first).isNotEqualTo(second);
    }
}
