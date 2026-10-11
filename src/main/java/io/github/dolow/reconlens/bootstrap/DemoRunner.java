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
import java.util.List;
import java.util.Map;

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

    public DemoRunner(ReconProperties properties, ReconEngine engine,
                      ReconRepository repository, ExplainService explainService) {
        this.properties = properties;
        this.engine = engine;
        this.repository = repository;
        this.explainService = explainService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getEngine().isRunDemoOnStartup()) {
            log.info("启动演示已关闭（recon.engine.run-demo-on-startup=false）");
            return;
        }

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
