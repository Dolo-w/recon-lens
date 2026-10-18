package io.github.dolow.reconlens.rule;

import io.github.dolow.reconlens.ReconLensApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容哈希回填必须与 {@code recon.seed-rules} 无关。
 *
 * <p>这条用例用两个上下文模拟"升级前后"：第一个上下文（seed 打开）把规则种子写进库，
 * 然后把 {@code spec_sha256} 清成 NULL，假装这些行是迁移前落库的；
 * 第二个上下文按生产配置 {@code recon.seed-rules=false} 启动 —— 如果回填还挂在
 * {@code seedFromRuleDirectory()} 里面，这里就会有行保持 NULL，审计视图里这一版的
 * "落库内容指纹"永远是空白。
 *
 * <p>补的是 {@code spec_sha256}（迁移期内容指纹），不是 {@code reviewed_spec_sha}
 * （审批哈希）。后者故意不补：NULL = 没经过人工审批，补它等于把未审的规则标成审过。
 *
 * <p>两次启动用同一个 H2 <b>文件</b>库，靠 {@code spring.sql.init.mode} 控制第二次不再灌演示数据；
 * 配置一律走命令行参数（{@code --k=v}）——{@link SpringApplicationBuilder#properties(String...)}
 * 设的是最低优先级的默认值，会被 application.yml 盖掉。
 */
class RuleBackfillSeedDisabledTest {

    @TempDir
    static Path dir;

    private static String url;

    @BeforeAll
    static void databaseUrl() {
        url = "jdbc:h2:file:" + dir.resolve("backfill").toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    }

    @Test
    void legacyRowsAreBackfilledEvenWithSeedingDisabled() {
        try (ConfigurableApplicationContext ctx = context(true, "always")) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_rule", Integer.class))
                    .as("种子规则先落库，才有'历史行'可造").isPositive();

            // 造出"迁移前落库"的形状：有规则体，没有内容指纹
            jdbc.update("UPDATE recon_rule SET spec_sha256 = NULL");
            assertThat(nullSha(jdbc)).isPositive();
        }

        try (ConfigurableApplicationContext ctx = context(false, "never")) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            assertThat(nullSha(jdbc))
                    .as("seed-rules=false 时也必须无条件回填历史行的 spec_sha256")
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recon_rule WHERE spec_sha256 IS NOT NULL", Integer.class))
                    .isPositive();
        }
    }

    private static Integer nullSha(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recon_rule WHERE spec_sha256 IS NULL", Integer.class);
    }

    private static ConfigurableApplicationContext context(boolean seedRules, String sqlInitMode) {
        return new SpringApplicationBuilder(ReconLensApplication.class).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.sql.init.mode=" + sqlInitMode,
                "--recon.seed-rules=" + seedRules,
                "--recon.engine.run-demo-on-startup=false",
                "--recon.agent.enabled=false",
                "--recon.llm.enabled=false",
                "--server.port=0",
                "--logging.level.root=WARN");
    }
}
