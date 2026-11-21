package io.github.dolow.reconlens.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 规则 DSL → Go worker 规格 JSON 的翻译器，以及 worker 产物（stats.json / diffs.jsonl）的解析。
 *
 * <p>翻译是<b>逐字映射</b>不是语义近似：diffType/severity/tolerance 原文传递，
 * 字段类型枚举转小写；唯一需要动脑的是字段名与 CSV header 的对齐 ——
 * 导出 CSV 的列名是 {@link Row} 归一后的小写，spec 里的字段名也必须小写，
 * worker 端是精确匹配（不是大小写不敏感）。声明式维度（derivedKey）的键列
 * 在 CSV 里叫 {@link SideSpec#KEY_ALIAS}，spec 的 keyField 直接指它。
 */
@Component
public class WorkerSpecWriter {

    private static final Logger log = LoggerFactory.getLogger(WorkerSpecWriter.class);

    private final ObjectMapper mapper;

    public WorkerSpecWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 生成规格 JSON 并落盘。
     *
     * @param jobId 写进每条差异的任务号（保持与 DB 一致，不用 worker 自己的默认值）
     */
    public Path write(RuleSpec rule, long jobId, Path target) {
        ObjectNode spec = mapper.createObjectNode();
        spec.put("jobId", String.valueOf(jobId));
        spec.set("left", sideOf(rule.left()));
        spec.set("right", sideOf(rule.right()));

        ArrayNode compare = spec.putArray("compare");
        for (FieldRule field : rule.compare()) {
            ObjectNode node = compare.addObject();
            node.put("field", field.field().toLowerCase(Locale.ROOT));
            node.put("type", field.type().name().toLowerCase(Locale.ROOT));
            if (field.ignoreCase()) {
                node.put("ignoreCase", true);
            }
            if (field.tolerance() != null) {
                node.put("tolerance", field.tolerance().toPlainString());
            }
            if (field.tolerancePercent() != null) {
                node.put("tolerancePercent", field.tolerancePercent().toPlainString());
            }
            node.put("diffType", field.diffType());
            node.put("severity", field.severity().name());
        }

        ObjectNode missing = spec.putObject("missing");
        missing.put("leftMissingDiffType", rule.missing().leftMissingDiffType());
        missing.put("rightMissingDiffType", rule.missing().rightMissingDiffType());
        missing.put("severity", rule.missing().severity().name());

        ObjectNode duplicate = spec.putObject("duplicate");
        duplicate.put("diffType", rule.duplicate().diffType());
        duplicate.put("severity", rule.duplicate().severity().name());

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), spec);
        } catch (IOException e) {
            throw new IllegalStateException("worker 规格文件写盘失败：" + e.getMessage(), e);
        }
        log.info("worker 规格已生成: {}", target);
        return target;
    }

    private ObjectNode sideOf(SideSpec side) {
        ObjectNode node = mapper.createObjectNode();
        node.put("label", side.label());
        node.put("keyField", side.derivedKey() ? SideSpec.KEY_ALIAS : side.keyField().toLowerCase(Locale.ROOT));
        node.put("keyType", side.keyType().name().toLowerCase(Locale.ROOT));
        if (side.eventTime() != null && !side.eventTime().isBlank()) {
            node.put("eventTime", side.eventTime().toLowerCase(Locale.ROOT));
        }
        return node;
    }

    /**
     * 解析 worker 的 stats.json。worker 跑完必然写它（哪怕 PARTIAL），
     * 读不到 = 进程被外力杀掉，调用方按失败处理。
     */
    public WorkerStats readStats(Path statsFile) {
        try {
            JsonNode root = mapper.readTree(statsFile.toFile());
            return new WorkerStats(
                    root.path("status").asText(""),
                    root.path("scanned").path("left").asLong(0),
                    root.path("scanned").path("right").asLong(0),
                    root.path("matched").asLong(0),
                    root.path("diffRows").asLong(0),
                    root.path("shards").path("committed").asInt(0),
                    root.path("shards").path("total").asInt(0),
                    root.path("error").asText(null));
        } catch (IOException e) {
            throw new IllegalStateException("worker 统计文件读不了（" + statsFile + "）：" + e.getMessage(), e);
        }
    }

    public JsonNode readLine(String line) {
        try {
            return mapper.readTree(line);
        } catch (IOException e) {
            throw new IllegalStateException("worker 差异行不是合法 JSON：" + line, e);
        }
    }

    /** worker 跑完的统计口径（字段名与 go-worker 的 stats.json 一一对应）。 */
    public record WorkerStats(String status, long scannedLeft, long scannedRight, long matched, long diffRows,
                              int shardsCommitted, int shardsTotal, String error) {
    }
}
