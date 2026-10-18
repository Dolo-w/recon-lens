package io.github.dolow.reconlens.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台导入的簿记：立刻返回任务号、状态可轮询、失败带原因。
 *
 * <p>真实的建表/灌数在 {@code FileImporter} 里（由 {@code FileImporterTest} 用真库覆盖），
 * 这里只验「发号 → 后台跑 → 状态落定」这条通道。
 */
class ImportTaskRegistryTest {

    private final FileImporter importer = mock(FileImporter.class);
    private final ImportTaskRegistry registry = new ImportTaskRegistry(importer);

    @AfterEach
    void shutdown() {
        registry.shutdown();
    }

    private static FileImporter.ImportPreview preview(String table, long rows, String error) {
        return new FileImporter.ImportPreview("orders.csv", table, List.of("order_no"), List.of("订单号"),
                List.of("VARCHAR(64)"), rows, error);
    }

    /** 轮询到终态；5 秒还没落定直接判失败（比固定 sleep 稳，也不引第三方等待库）。 */
    private ImportTaskRegistry.Status awaitStatus(long taskId, ImportTaskRegistry.Status expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            ImportTaskRegistry.Task task = registry.find(taskId).orElseThrow();
            if (task.status() == expected) {
                return task.status();
            }
            if (task.status() == ImportTaskRegistry.Status.FAILED && expected != task.status()) {
                throw new AssertionError("任务 " + taskId + " 失败：" + task.error());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断", e);
            }
        }
        throw new AssertionError("5 秒内没到 " + expected
                + "，当前：" + registry.find(taskId).orElseThrow().status());
    }

    @Test
    @DisplayName("confirm 立刻返回任务号，后台跑完状态变 SUCCESS 并带上行数")
    void submitReturnsImmediatelyThenSucceeds() {
        when(importer.confirm(eq("orders.csv"), eq("recon_orders"), any(), any()))
                .thenReturn(preview("recon_orders", 625_000L, null));

        long taskId = registry.submit("orders.csv", "recon_orders", "wang", null);

        assertThat(taskId).isPositive();
        assertThat(registry.find(taskId)).isPresent();
        assertThat(awaitStatus(taskId, ImportTaskRegistry.Status.SUCCESS))
                .isEqualTo(ImportTaskRegistry.Status.SUCCESS);
        ImportTaskRegistry.Task done = registry.find(taskId).orElseThrow();
        assertThat(done.rows()).isEqualTo(625_000L);
        assertThat(done.error()).isNull();
        assertThat(done.table()).isEqualTo("recon_orders");
    }

    @Test
    @DisplayName("导入失败（FileImporter 返回 error）落 FAILED 并保留原因")
    void importerErrorBecomesFailedTask() {
        when(importer.confirm(eq("bad.csv"), eq("recon_bad"), any(), any()))
                .thenReturn(preview("recon_bad", 0, "第 3 行有 21 列，表头只有 20 列"));

        long taskId = registry.submit("bad.csv", "recon_bad", "wang", null);

        assertThat(awaitStatus(taskId, ImportTaskRegistry.Status.FAILED))
                .isEqualTo(ImportTaskRegistry.Status.FAILED);
        assertThat(registry.find(taskId).orElseThrow().error()).contains("表头只有 20 列");
    }

    @Test
    @DisplayName("异常也落 FAILED，不会把任务永远留在 RUNNING")
    void exceptionBecomesFailedTask() {
        when(importer.confirm(eq("boom.csv"), eq("recon_boom"), any(), any()))
                .thenThrow(new IllegalStateException("连接断开"));

        long taskId = registry.submit("boom.csv", "recon_boom", "wang", null);

        assertThat(awaitStatus(taskId, ImportTaskRegistry.Status.FAILED))
                .isEqualTo(ImportTaskRegistry.Status.FAILED);
        assertThat(registry.find(taskId).orElseThrow().error()).contains("连接断开");
    }

    @Test
    @DisplayName("查不到的任务号返回空，而不是假装成功")
    void unknownTaskIsEmpty() {
        assertThat(registry.find(999_999L)).isEmpty();
    }
}
