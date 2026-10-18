package io.github.dolow.reconlens.bootstrap;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.explain.ExplainService;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 启动即演示：拉起一份 H2 内存数据，跑一遍 BILL_VS_SETTLE，把差异分类和归因打到日志。
 * <p>目的是让人 clone 下来直接 {@code mvn spring-boot:run} 就能看到结果，不用先准备数据库。
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final ReconProperties properties;
    private final ReconEngine engine;
    private final ReconRepository repository;
    private final ExplainService explainService;
    private final DataSource dataSource;

    public DemoRunner(ReconProperties properties, ReconEngine engine,
                      ReconRepository repository, ExplainService explainService,
                      DataSource dataSource) {
        this.properties = properties;
        this.engine = engine;
        this.repository = repository;
        this.explainService = explainService;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getEngine().isRunDemoOnStartup()) {
            log.info("启动演示已关闭（recon.engine.run-demo-on-startup=false）");
            return;
        }
        if (!isEmbedded() && !properties.getEngine().isDemoAllowExternalDb()) {
            // 演示会往库里写 bill_left/bill_right 的演示行和一个 demo-* 任务。默认 profile 是 H2 内存库，
            // 写完就没了；可要是有人只把数据源换成真实 MySQL，演示就会混进业务数据 —— 那种情况必须显式放行。
            log.info("启动演示已跳过：当前数据源不是内嵌 H2（真要在这套数据源上跑，"
                    + "显式设 recon.engine.demo-allow-external-db=true，并确认它是演示库）");
            return;
        }
        try {
            runDemo();
        } catch (RuntimeException e) {
            // 演示是"附带效果"，不该让人起不了服务：换库、种子规则被改、数据表被清过都会让它失败。
            // 打一条说明就继续启动，否则一个演示脚本能把整个应用挡在门外。
            log.warn("启动演示失败（不影响服务启动，可用 recon.engine.run-demo-on-startup=false 关掉）：{}",
                    e.toString());
        }
    }

    /** 凭 JDBC URL 判断是不是内嵌 H2（默认 profile 用来做"clone 下来就能跑"的演示库）。 */
    private boolean isEmbedded() {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            return url != null && url.startsWith("jdbc:h2:mem:");
        } catch (SQLException e) {
            log.warn("判断数据源类型失败，按「外部数据源」处理（演示将跳过）：{}", e.toString());
            return false;
        }
    }

    private void runDemo() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "demo-" + LocalDate.now(), "启动自动演示");
        Map<String, Long> byType = repository.summaryByType(job.id());
        int explained = explainService.explainJob(job.id(), 50);
        List<DiffRecord> samples = repository.listDiffs(job.id(), null, 0, 5);

        StringBuilder report = new StringBuilder(512);
        report.append("\n================ recon-lens 启动演示 ================\n");
        report.append(String.format("任务 #%d 状态=%s 分片=%d%n", job.id(), job.status(), job.shardCount()));
        report.append(String.format("扫描行数: 左侧 %d / 右侧 %d，匹配 %d，差异 %d 条%n",
                job.leftRows(), job.rightRows(), job.matchedRows(), job.diffRows()));
        report.append("差异分类: ").append(byType).append('\n');
        report.append("归因补齐: ").append(explained).append(" 条（解释器=")
                .append(explainService.llmName()).append("）\n");
        report.append("----------------------------------------------------\n");
        for (DiffRecord diff : samples) {
            report.append('[').append(diff.severity()).append("] ").append(diff.diffType())
                    .append(' ').append(describe(diff)).append('\n')
                    .append("    归因: ").append(diff.explanation()).append('\n');
        }
        report.append("----------------------------------------------------\n");
        report.append("试一下: curl http://localhost:8080/api/jobs/" ).append(job.id()).append('\n');
        report.append("====================================================");
        log.info(report.toString());
    }

    /** 单条差异的一行摘要：单边缺失与重复没有"字段"概念，就不硬塞 field=。 */
    private static String describe(DiffRecord diff) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("key=").append(diff.bizKey());
        if (diff.field() != null && !diff.field().isBlank()) {
            sb.append(" field=").append(diff.field());
        }
        sb.append(' ').append(orNone(diff.leftValue())).append(" -> ").append(orNone(diff.rightValue()));
        return sb.toString();
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }
}
