package io.github.dolow.reconlens;

import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 水位/cut-off 端到端：种子数据的 updated_at 固定在 2026-10-01，
 * lag=300s 时探测结果必然是「未就绪」——于是单边缺失必须标 {@code *_PENDING}（未到达），
 * 不能冒充「真缺失」。这是 MISSING ≠ NOT_YET_ARRIVED 的实机证明。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.engine.watermarks.[BILL_VS_SETTLE].left=updated_at",
        "recon.engine.watermarks.[BILL_VS_SETTLE].right=updated_at",
        "recon.engine.watermarks.[BILL_VS_SETTLE].lag-seconds=300"
})
class WatermarkReadinessTest {

    @Autowired
    private ReconEngine engine;
    @Autowired
    private ReconRepository repository;

    @Test
    void 未就绪侧的单边缺失标PENDING而不是冒充真缺失() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "wm-pending", "水位未就绪");

        assertThat(job.status()).isEqualTo(JobStatus.SUCCESS);

        Map<String, Long> byType = repository.summaryByType(job.id());
        // 种子数据两侧各有一条单边缺失：水位未就绪时它们必须以 _PENDING 形态出现
        assertThat(byType).containsEntry("MISSING_RIGHT_PENDING", 1L);
        assertThat(byType).containsEntry("MISSING_LEFT_PENDING", 1L);
        // 金额差异/重复键与水位无关，保持原类型
        assertThat(byType).containsEntry("AMOUNT_MISMATCH", 1L);
        assertThat(byType).doesNotContainKey("MISSING_RIGHT");
        assertThat(byType).doesNotContainKey("MISSING_LEFT");
    }

    @Test
    void PENDING差异的解释里写明水位状态() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "wm-explain", "水位归因");

        java.util.List<DiffRecord> pending = repository.listDiffs(job.id(), "MISSING_RIGHT_PENDING", 0, 10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).explanation()).isNotBlank();
        assertThat(pending.get(0).explanation()).contains("未");
    }

    @Test
    void 数据快照记录了这次跑的水位判定() {
        ReconJob job = engine.run("BILL_VS_SETTLE", "wm-snapshot", "水位快照");

        String snapshot = repository.dataSnapshot(job.id()).orElseThrow();
        assertThat(snapshot).contains("readiness")
                .contains("leftWatermark")
                .contains("\"leftReady\":false");
    }

    @Test
    void 重跑后PENDING判定仍然幂等() {
        ReconJob first = engine.run("BILL_VS_SETTLE", "wm-rerun", "水位重跑一");
        ReconJob second = engine.retry(first.id(), false);

        assertThat(second.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(repository.countDiffs(first.id(), null)).isEqualTo(first.diffRows());
        assertThat(repository.summaryByType(first.id()))
                .containsEntry("MISSING_RIGHT_PENDING", 1L)
                .containsEntry("MISSING_LEFT_PENDING", 1L);
    }
}
