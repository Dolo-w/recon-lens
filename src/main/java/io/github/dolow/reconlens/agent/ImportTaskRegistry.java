package io.github.dolow.reconlens.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件导入的后台任务登记处：{@code recon_import_file(action=confirm, async=true)} 立刻返回任务号，
 * {@code recon_import_status} 轮询。
 *
 * <h2>为什么要异步</h2>
 * 实测 62.5 万行 × 20 列导入 26 秒，而 MCP 客户端普遍 30 秒超时 —— 大文件同步导入必然超时，
 * 超时后调用方不知道表到底建没建成，重试又会撞"表已存在"。与试算队列同构：
 * 先发号、后台干、状态可查。
 *
 * <h2>刻意保持的简单</h2>
 * <ul>
 *   <li>单线程：导入会长时间占一条连接并做大批量写入，并发两个只会互相拖慢；</li>
 *   <li>有界队列（2）：队列满了直接拒绝并说清楚，比排到半小时后好；</li>
 *   <li>任务表在内存、完成即保留最近结果：跨重启的真相在库里（表建没建成、注册没注册都能查）；
 *       重启后内存任务号失效，{@code recon_import_status} 会明确说"查不到"，不假装成功。</li>
 * </ul>
 */
public class ImportTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskRegistry.class);

    /** 队列容量：同一时刻真的只会有一个人盯着一份大文件导入。 */
    private static final int QUEUE_CAPACITY = 2;

    public enum Status { QUEUED, RUNNING, SUCCESS, FAILED }

    /**
     * 一次导入任务的快照。
     *
     * @param rows   成功时导入的行数
     * @param error  失败原因（成功为 null）
     */
    public record Task(long id, String file, String table, Status status, Long rows, String error,
                       Instant submittedAt, Instant finishedAt) {

        Task withStatus(Status next) {
            return new Task(id, file, table, next, rows, error, submittedAt, finishedAt);
        }

        Task done(Long importedRows, String failure) {
            return new Task(id, file, table, failure == null ? Status.SUCCESS : Status.FAILED,
                    importedRows, failure, submittedAt, Instant.now());
        }
    }

    private final FileImporter importer;
    private final ExecutorService pool;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();

    public ImportTaskRegistry(FileImporter importer) {
        this.importer = importer;
        this.pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-import");
                    // 刻意不是 daemon：stdio MCP 宿主调完 confirm 就关管道时，daemon 线程会跟着 JVM 一起消失，
                    // 62 万行的导入被静默掐死在半路（实测：返回 QUEUED 后表根本没建出来）。
                    // 非 daemon 让 JVM 等它跑完再退，最差是宿主多等二十几秒，而不是"以为提交了、其实没导"。
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 排队一次导入。
     *
     * @throws IllegalStateException 队列已满
     */
    public long submit(String file, String table, String operator, String sheet) {
        long id = ids.incrementAndGet();
        Task queued = new Task(id, file, table, Status.QUEUED, null, null, Instant.now(), null);
        tasks.put(id, queued);
        try {
            pool.execute(() -> execute(id, file, table, operator, sheet));
        } catch (RuntimeException e) {
            tasks.put(id, queued.done(null, "后台队列已满（同时最多 2 个导入任务），稍后重试"));
            throw new IllegalStateException("导入队列已满，稍后重试", e);
        }
        return id;
    }

    public Optional<Task> find(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private void execute(long id, String file, String table, String operator, String sheet) {
        Task running = tasks.get(id);
        if (running != null) {
            tasks.put(id, running.withStatus(Status.RUNNING));
        }
        try {
            FileImporter.ImportPreview result = importer.confirm(file, table, operator, sheet);
            if (result.error() != null) {
                tasks.put(id, running.done(null, result.error()));
                log.warn("后台导入 #{} 失败: {} -> {}：{}", id, file, table, result.error());
                return;
            }
            tasks.put(id, running.done(result.rows(), null));
            log.info("后台导入 #{} 完成: {} -> {} 行数={}", id, file, table, result.rows());
        } catch (RuntimeException e) {
            tasks.put(id, running.done(null, "导入异常：" + e));
            log.error("后台导入 #{} 异常终止: {}", id, e.toString());
        }
    }
}
