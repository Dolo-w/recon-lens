package io.github.dolow.reconlens.connector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 连接器落盘公共设施（v3 扩展架构 §三）：所有 {@link BillingConnector} 实现共用同一套
 * CSV 落盘口径——RFC4180 全字段引号转义 + 临时文件原子改名（半截响应不落目录），
 * 文件名带时间戳与随机后缀可复跑可审计。口径只有一份，厂商实现各自长字段映射，不各自长落盘逻辑。
 */
public final class ConnectorCsvWriter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ConnectorCsvWriter() {
    }

    /**
     * 把一批记录按表头落成 {@code {vendor}-{resource}-{时间戳}-{随机}}.csv，返回文件名。
     * 目录不存在会创建；写失败抛 {@link IllegalStateException}（消息带目录，fail-loud）。
     */
    public static String write(String vendor, String resource, Path dir,
                               List<String> headers, List<List<String>> records) {
        Path abs = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(abs);
            String name = vendor + "-" + resource + "-" + TS.format(Instant.now()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".csv";
            Path tmp = abs.resolve(name + ".tmp");
            Path target = abs.resolve(name);
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(csvLine(headers));
                w.write("\r\n");
                for (List<String> record : records) {
                    w.write(csvLine(record));
                    w.write("\r\n");
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("拉取结果写盘失败（目录 " + abs + "）：" + e.getMessage(), e);
        }
    }

    private static String csvLine(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }
}
