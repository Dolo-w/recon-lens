package io.github.dolow.reconlens.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffEvidence;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.domain.Severity;
import io.github.dolow.reconlens.domain.ShardStatus;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.store.ReconRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 执行分发器（v3 扩展架构 §一）：决定一个对账任务在 JVM 内跑还是交给 Go worker 跑，
 * 并保证<b>两条路径产出同口径</b>的 job 状态机、差异集与统计（golden-set 对照测试钉住）。
 *
 * <p>三档模式（{@code recon.engine.execution}）：
 * <ul>
 *   <li>{@code inprocess}（默认）—— JVM 内分片对账，行为与历史版本完全一致</li>
 *   <li>{@code go-worker} —— 两侧数据导出键序 CSV，调 worker 二进制比对，差异 JSONL 导回 recon_diff</li>
 *   <li>{@code auto} —— 两侧行数探测达到阈值（默认 5 万）才切 worker；探不了行数（自定义 SQL）保守走 inprocess</li>
 * </ul>
 *
 * <p>两条铁律：
 * <ol>
 *   <li><b>试算（captureDiffs）永远走 inprocess</b> —— 摘要 T2/T3/T4 的幂等比较语义绑定在
 *       Java 路径上（biz_key 截断的教训见 ReconEngine.runTraced），worker 路径没有摘要。</li>
 *   <li><b>go-worker 模式下 worker 不可用 = 任务失败（fail-loud）</b>，绝不静默降级 ——
 *       用户显式点名 worker 却偷偷用 JVM 跑，是在替他做他不知道的决定。
 *       {@code auto} 是软偏好，worker 不可用时降级 inprocess 但 WARN 说清楚。</li>
 * </ol>
 *
 * <p>worker 路径的差异 shardId 统一为 0：worker 的分片是提交粒度（checkpoint 单位），
 * 不是 Java 的规则分片计划 —— 跑完后分片记录重建为 worker 实际的提交单元数，
 * 不让 SUM 统计撒谎。断点续跑：工作目录里四件套（CSV×2+spec+checkpoint）齐全时
 * retry 自动 {@code --resume}，从已提交前缀接着跑，不重新导出。
 */
@Component
public class ExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ExecutionDispatcher.class);

    /** worker 退出码（与 go-worker/runner.go 的约定一字不差）。 */
    private static final int EXIT_OK = 0;
    private static final int EXIT_ARGS = 1;
    private static final int EXIT_DATA = 2;
    private static final int EXIT_PARTIAL = 3;

    private static final int DIFF_BATCH_SIZE = 500;

    private final ReconProperties properties;
    private final ReconRepository repository;
    private final WorkerCsvExporter exporter;
    private final WorkerSpecWriter specWriter;

    public ExecutionDispatcher(ReconProperties properties, ReconRepository repository,
                               WorkerCsvExporter exporter, WorkerSpecWriter specWriter) {
        this.properties = properties;
        this.repository = repository;
        this.exporter = exporter;
        this.specWriter = specWriter;
    }

    /** 当前任务是否由 worker 接手。captureDiffs=true（试算）永远 false。 */
    public boolean shouldTakeOver(RuleSpec rule, boolean captureDiffs) {
        if (captureDiffs) {
            return false;
        }
        String mode = mode();
        return switch (mode) {
            case "go-worker" -> requireWorkerAvailable("go-worker");
            case "auto" -> workerAvailable() && estimateRows(rule) >= threshold();
            default -> false;
        };
    }

    /**
     * worker 路径完整执行：从导出 CSV 到 finishJob 全程负责。
     * 调用方（ReconEngine.execute）只负责在前面 markJobRunning。
     */
    public List<ShardResult> executeViaWorker(RuleSpec rule, long jobId, ReconEngine.Readiness readiness) {
        String mode = mode();
        Path work = workDir(jobId);
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw new IllegalStateException("worker 工作目录建不了（" + work + "）：" + e.getMessage(), e);
        }
        Path leftCsv = work.resolve("left.csv");
        Path rightCsv = work.resolve("right.csv");
        Path specFile = work.resolve("spec.json");
        Path outFile = work.resolve("diffs.jsonl");
        Path statsFile = work.resolve("stats.json");
        Path checkpoint = work.resolve("checkpoint.json");

        // 断点续跑：四件套齐全（上次跑过且前缀已提交）就接着跑，不重新导出。
        // CSV 是那次任务的原始凭证，重新导出反而可能拿到不一样的数据。
        boolean resume = Files.exists(checkpoint) && Files.exists(leftCsv) && Files.exists(rightCsv)
                && Files.exists(specFile) && Files.exists(outFile);
        if (resume) {
            log.info("任务 #{} 检测到 checkpoint，从已提交前缀续跑（worker）", jobId);
        } else {
            log.info("任务 #{} 走 go-worker 路径：导出键序 CSV → worker 比对", jobId);
            exporter.export(rule.left(), leftCsv);
            exporter.export(rule.right(), rightCsv);
            specWriter.write(rule, jobId, specFile);
        }

        List<String> command = new ArrayList<>();
        command.add(properties.getEngine().getGoWorkerPath());
        command.add("--left"); command.add(leftCsv.toAbsolutePath().toString());
        command.add("--right"); command.add(rightCsv.toAbsolutePath().toString());
        command.add("--spec"); command.add(specFile.toAbsolutePath().toString());
        command.add("--out"); command.add(outFile.toAbsolutePath().toString());
        command.add("--format"); command.add("jsonl");
        command.add("--stats"); command.add(statsFile.toAbsolutePath().toString());
        command.add("--checkpoint"); command.add(checkpoint.toAbsolutePath().toString());
        command.add("--job-id"); command.add(String.valueOf(jobId));
        if (resume) {
            command.add("--resume");
        }
        if (readiness.configured()) {
            if (readiness.leftWatermark() != null) {
                command.add("--left-watermark");
                command.add(readiness.leftWatermark().toString());
            }
            if (readiness.rightWatermark() != null) {
                command.add("--right-watermark");
                command.add(readiness.rightWatermark().toString());
            }
            if (readiness.deadline() != null) {
                command.add("--deadline");
                command.add(readiness.deadline().toString());
            }
        }

        WorkerSpecWriter.WorkerStats stats;
        try {
            int exit = runWorker(jobId, command);
            stats = specWriter.readStats(statsFile);
            if (exit == EXIT_OK && "COMPLETED".equals(stats.status())) {
                importDiffs(rule, jobId, outFile);
                onFinished(jobId, stats, JobStatus.SUCCESS, null);
                log.info("任务 #{} worker 路径完成: 扫描 L{} R{} 差异 {} 条（{} 个提交分片）",
                        jobId, stats.scannedLeft(), stats.scannedRight(), stats.diffRows(), stats.shardsTotal());
                return List.of(new ShardResult(0, ShardStatus.SUCCESS, 1,
                        new MatchStats(stats.scannedLeft(), stats.scannedRight(), stats.matched(), stats.diffRows()),
                        null, false, null, List.of()));
            }
            // 非 0 退出 / PARTIAL：差异不完整，绝不导回 —— 半份差异比没有差异更危险
            String reason = describeFailure(exit, stats, outFile.getParent());
            log.warn("任务 #{} worker 路径失败（exit={}）: {}", jobId, exit, reason);
            return failed(jobId, reason);
        } catch (IOException e) {
            return failed(jobId, "worker 进程执行失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(jobId, "worker 进程等待被中断");
        }
    }

    // ------------------------------------------------------------------
    // 内部

    private int runWorker(long jobId, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // 消费输出防管道塞满阻塞（worker 输出很短，但还是按规矩来）
        try (BufferedReader out = new BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                log.info("[worker #{}] {}", jobId, line);
            }
        }
        int timeout = Math.max(1, properties.getEngine().getGoWorkerTimeoutSeconds());
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("worker 进程超时（" + timeout + " 秒）被强杀 —— "
                    + "大任务请调 recon.engine.go-worker-timeout-seconds");
        }
        return process.exitValue();
    }

    /** 差异 JSONL 导回 recon_diff / recon_diff_evidence（shardId=0，全量或全无）。 */
    private void importDiffs(RuleSpec rule, long jobId, Path outFile) throws IOException {
        List<DiffRecord> buffer = new ArrayList<>(DIFF_BATCH_SIZE);
        List<DiffEvidence> evidenceBuffer = new ArrayList<>(DIFF_BATCH_SIZE);
        Set<String> seenEvidence = new HashSet<>();
        long count = 0;
        repository.deleteShardDiffs(jobId, 0);
        repository.deleteShardEvidence(jobId, 0);
        try (BufferedReader reader = Files.newBufferedReader(outFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = specWriter.readLine(line);
                buffer.add(toRecord(rule, jobId, node));
                collectEvidence(node, jobId, evidenceBuffer, seenEvidence);
                count++;
                if (buffer.size() >= DIFF_BATCH_SIZE) {
                    repository.insertDiffs(List.copyOf(buffer));
                    flushEvidence(evidenceBuffer);
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            repository.insertDiffs(List.copyOf(buffer));
        }
        flushEvidence(evidenceBuffer);
        log.info("任务 #{} 差异导回完成: {} 条", jobId, count);
    }

    private DiffRecord toRecord(RuleSpec rule, long jobId, JsonNode node) {
        BigDecimal delta = decimalOrNull(node, "deltaValue");
        BigDecimal ratio = decimalOrNull(node, "deltaRatio");
        return DiffRecord.of(jobId, 0,
                node.path("diffType").asText(""),
                Severity.from(node.path("severity").asText("MEDIUM")),
                node.path("bizKey").asText(""),
                node.hasNonNull("field") ? node.get("field").asText() : null,
                node.hasNonNull("leftValue") ? node.get("leftValue").asText() : null,
                node.hasNonNull("rightValue") ? node.get("rightValue").asText() : null,
                delta, ratio,
                node.hasNonNull("eventAt") ? node.get("eventAt").asText() : null);
    }

    private void collectEvidence(JsonNode node, long jobId, List<DiffEvidence> buffer, Set<String> seen) {
        String bizKey = node.path("bizKey").asText("");
        addEvidence(node, "evidenceLeft", DiffEvidence.LEFT, bizKey, jobId, buffer, seen);
        addEvidence(node, "evidenceRight", DiffEvidence.RIGHT, bizKey, jobId, buffer, seen);
    }

    private void addEvidence(JsonNode node, String field, String side, String bizKey, long jobId,
                             List<DiffEvidence> buffer, Set<String> seen) {
        if (!node.hasNonNull(field) || !seen.add(side + "|" + bizKey)) {
            return;
        }
        buffer.add(new DiffEvidence(jobId, 0, side, bizKey, "go-worker", node.get(field).asText()));
        if (buffer.size() >= DIFF_BATCH_SIZE) {
            flushEvidence(buffer);
        }
    }

    private void flushEvidence(List<DiffEvidence> buffer) {
        if (!buffer.isEmpty()) {
            repository.insertEvidence(List.copyOf(buffer));
            buffer.clear();
        }
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        String raw = node.get(field).asText();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("worker 产出的 " + field + " 不是合法十进制数: \"" + raw
                    + "\" —— 语义对齐出问题了，宁可任务失败也不落脏数据");
        }
    }

    /** 失败收尾：清掉可能的半截差异、分片记录如实标失败、job 标 FAILED。 */
    private List<ShardResult> failed(long jobId, String reason) {
        repository.deleteShardDiffs(jobId, 0);
        repository.deleteShardEvidence(jobId, 0);
        repository.resetShards(jobId, 1);
        repository.markShardFailed(jobId, 0, reason);
        repository.finishJob(jobId, JobStatus.FAILED, reason);
        return List.of(ShardResult.failed(0, 1, reason));
    }

    private void onFinished(long jobId, WorkerSpecWriter.WorkerStats stats, JobStatus status, String error) {
        // 分片记录如实重建为 worker 的提交单元数；统计汇总在 shard 0 上，
        // 其余提交单元没有独立的可汇报统计（worker stats 只到聚合层）
        repository.resetShards(jobId, 1);
        repository.markShardSuccess(jobId, 0,
                new MatchStats(stats.scannedLeft(), stats.scannedRight(), stats.matched(), stats.diffRows()));
        repository.finishJob(jobId, status, error);
    }

    private String describeFailure(int exit, WorkerSpecWriter.WorkerStats stats, Path work) {
        String statsError = stats.error() == null || stats.error().isBlank() ? "" : "：" + stats.error();
        String committed = stats.shardsTotal() > 0
                ? "（已提交前缀 " + stats.shardsCommitted() + "/" + stats.shardsTotal() + " 个分片保留在 checkpoint，重跑自动续）"
                : "";
        return switch (exit) {
            case EXIT_ARGS -> "worker 参数错误（规则或路径问题）" + statsError;
            case EXIT_DATA -> "worker 数据错误（CSV 编码/格式/乱序）" + statsError
                    + " —— 差异不完整不导回，修复数据后重跑任务" + committed;
            case EXIT_PARTIAL -> "worker 中途失败，只完成了最大可提交前缀" + statsError
                    + " —— 差异不完整不导回，重跑任务自动从断点续" + committed;
            default -> "worker 异常退出（exit=" + exit + "）" + statsError;
        };
    }

    private String mode() {
        String mode = properties.getEngine().getExecution();
        if (mode == null || mode.isBlank()) {
            return "inprocess";
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    private int threshold() {
        return Math.max(1, properties.getEngine().getGoWorkerThreshold());
    }

    private boolean workerAvailable() {
        String path = properties.getEngine().getGoWorkerPath();
        return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
    }

    private boolean requireWorkerAvailable(String mode) {
        if (workerAvailable()) {
            return true;
        }
        throw new IllegalStateException("execution=" + mode + " 但 recon.engine.go-worker-path 未配置或文件不存在（"
                + properties.getEngine().getGoWorkerPath() + "）—— 显式点名 worker 却不可用必须失败，"
                + "不静默降级（要自动降级请用 execution=auto）");
    }

    /** auto 决策的行数估算：两侧都是可探测的单一表才返回行数（取大者），否则 null = 保守走 inprocess。 */
    private long estimateRows(RuleSpec rule) {
        Long left = repository.tableRowCount(rule.left().table());
        Long right = repository.tableRowCount(rule.right().table());
        if (left == null || right == null) {
            log.info("auto 模式行数探测不可用（自定义 SQL 或表不存在），保守走 inprocess: rule={}", rule.code());
            return -1;
        }
        // 聚合维度按原表行数估算会高估 —— 高估只是性能选择（worker 语义相同），不影响正确性
        return Math.max(left, right);
    }

    private Path workDir(long jobId) {
        return Path.of(properties.getEngine().getGoWorkerDir()).toAbsolutePath().normalize().resolve(String.valueOf(jobId));
    }

    /** 供 ReconEngine 判断是否进入 worker 路径时的模式可读性日志。 */
    record Decision(String mode, boolean takeOver) {
    }
}
