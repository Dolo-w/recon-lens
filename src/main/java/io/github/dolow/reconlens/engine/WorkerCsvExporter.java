package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.dsl.SideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 把一侧取数结果流式导出成 Go worker 吃的 CSV：按键升序、RFC4180 转义、header 小写。
 *
 * <p>三个口径约定（与 Java 引擎完全一致，golden-set 对照测试钉住）：
 * <ol>
 *   <li><b>升序</b>：复用 {@link ReconCursors} 的取数 SQL —— 它本来就带 ORDER BY 业务键，
 *       是归并对账的正确性前提，worker 只是把同一个前提换了个执行器。</li>
 *   <li><b>值的字符串化走 {@link Row#render}</b>：BigDecimal 去 0 后 plain string（不出科学计数法）、
 *       其余 String.valueOf、null 写空串 —— 这正是 MergeMatcher 在 JVM 内看到的字符串。</li>
 *   <li><b>header 全小写</b>：与 {@link Row} 的列名归一同一把尺子，spec 里的字段名也小写，
 *       worker 端精确匹配就能对上。</li>
 * </ol>
 *
 * <p>导出与比对分离：CSV 落盘后 worker 才开始跑，CSV 本身就是这次任务的原始凭证，
 * 留在工作目录里可审计、可断点续跑（checkpoint 引用的就是这份文件）。
 */
@Component
public class WorkerCsvExporter {

    private static final Logger log = LoggerFactory.getLogger(WorkerCsvExporter.class);

    private final ReconCursors cursors;

    public WorkerCsvExporter(ReconCursors cursors) {
        this.cursors = cursors;
    }

    /**
     * 导出一侧数据。
     *
     * @return 实际导出行数
     */
    public long export(SideSpec side, Path target) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        long rows = 0;
        try (RowCursor cursor = cursors.open(side, null);
             BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            List<String> header = cursor.columns().stream().toList();
            writer.write(csvLine(header));
            writer.write("\r\n");
            Row row;
            while ((row = cursor.next()) != null) {
                writer.write(csvRow(header, row));
                writer.write("\r\n");
                rows++;
                if (rows % 100_000 == 0) {
                    log.info("worker 导出进度: side={} 已导出 {} 行", side.label(), rows);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("worker 导出 CSV 失败（" + side.label() + " → " + target + "）：" + e.getMessage(), e);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("worker 导出 CSV 改名失败：" + e.getMessage(), e);
        }
        log.info("worker 导出完成: side={} rows={} file={}", side.label(), rows, target);
        return rows;
    }

    /** RFC4180：含逗号/引号/换行/回车的字段加引号转义，其余裸写（数值列零膨胀）。 */
    private static String cell(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cells.get(i));
        }
        return sb.toString();
    }

    private static String csvRow(List<String> header, Row row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < header.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String rendered = row.str(header.get(i));
            sb.append(cell(rendered == null ? "" : rendered));
        }
        return sb.toString();
    }
}
