package io.github.dolow.reconlens.agent;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 文件导入（M7）：用户的原始数据在 CSV/Excel 里的接入管道。
 *
 * <p>补的是"第三层接入"的最后一段：用户手里没有库表，只有 CSV/Excel ——
 * 解析 → 建表 → 灌数 → <b>复用 {@link TableRegistrar} 落审计并进白名单</b>，
 * 之后起草/对账链路与原生表完全一致。注册不是本类自己发明的第二条通道，
 * 而是调用注册器的 confirm：审计只有一处，白名单快照只有一处。
 *
 * <p>两段式与注册器同款：preview 只读文件（列名、类型推断、行数），不改任何状态；
 * confirm 才建表灌数。目标表已存在时直接拒绝 —— 导入会<b>建新表</b>，绝不覆盖已有的：
 * "同名表"要么是用户笔误，要么是上次导入的产物，两种都不该被静默替换。
 *
 * <p>安全边界：
 * <ul>
 *   <li>默认只接受导入目录内的相对路径，解析后的规范路径必须仍在目录里 ——
 *       模型给一个 {@code ../../etc/passwd} 或绝对路径都直接拒绝；
 *       本地自用可开 {@code recon.agent.file-import.allow-absolute-paths=true}
 *       放行任意本地绝对路径（后缀白名单仍然生效）；</li>
 *   <li>表名从严正则（同注册器），列名规范化成合法标识符 —— 标识符进 DDL，
 *       数据值全部走 PreparedStatement 参数，一条都不拼进 SQL；</li>
 *   <li>行列上限来自 {@code recon.agent.file-import.*} —— 导入是同步动作，上界就是护栏。</li>
 * </ul>
 */
public class FileImporter {

    private static final Logger log = LoggerFactory.getLogger(FileImporter.class);

    /** 同 {@link TableRegistrar}：导入的输入会进 DDL，标识符从严。 */
    private static final Pattern TABLE_NAME = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    /** 类型推断采样行数：再多收益递减，还拖慢 preview。 */
    private static final int SAMPLE_ROWS = 500;

    private static final int BATCH_SIZE = 1000;

    private static final Pattern DATE_ONLY = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");
    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{1,2}-\\d{1,2}[ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,3})?)?");

    private final AgentProperties properties;
    private final TableRegistrar registrar;
    private final JdbcTemplate jdbc;

    public FileImporter(AgentProperties properties, TableRegistrar registrar, JdbcTemplate jdbc) {
        this.properties = properties;
        this.registrar = registrar;
        this.jdbc = jdbc;
    }

    /**
     * 一次导入预览/确认的结果。{@code error} 非空表示没有任何变更（与注册器同语义）。
     * {@code columns} 是<b>规范化后</b>的列名（进 DDL 的就是它们），
     * {@code originalHeaders} 保留文件里的原始表头，方便人对照确认。
     */
    public record ImportPreview(String file, String table, List<String> columns,
                                List<String> originalHeaders, List<String> columnTypes,
                                Long rows, String error) {
    }

    /** 只读预览：解析文件头与采样行，推断列类型与总行数。不改任何状态。 */
    public ImportPreview preview(String file) {
        List<String> headers;
        List<String> normalized;
        List<String> types;
        long rowCount;
        try {
            Path path = resolve(file);
            try (ParsedFile parsed = open(path)) {
                headers = parsed.headers();
                if (headers.isEmpty()) {
                    return new ImportPreview(file, null, List.of(), List.of(), List.of(), null,
                            "文件里没有表头行（第一个 Sheet / 第一行是空的？）");
                }
                if (headers.size() > properties.getFileImport().getMaxColumns()) {
                    return new ImportPreview(file, null, List.of(), headers, List.of(), null,
                            "列数 " + headers.size() + " 超过上限 " + properties.getFileImport().getMaxColumns()
                                    + "（recon.agent.file-import.max-columns）");
                }
                List<List<String>> sample = parsed.nextRows(SAMPLE_ROWS);
                rowCount = sample.size() + parsed.countRemaining();
                if (rowCount > properties.getFileImport().getMaxRows()) {
                    return new ImportPreview(file, null, List.of(), headers, List.of(), rowCount,
                            "数据行 " + rowCount + " 超过上限 " + properties.getFileImport().getMaxRows()
                                    + "（recon.agent.file-import.max-rows）；先拆文件或调大上限");
                }
                normalized = normalizeColumns(headers);
                types = inferTypes(sample, headers.size());
            }
        } catch (IOException e) {
            return new ImportPreview(file, null, List.of(), List.of(), List.of(), null,
                    "读不了文件：" + e.getMessage());
        }
        return new ImportPreview(file, null, normalized, headers, types, rowCount, null);
    }

    /**
     * 确认导入：建表 → 灌数 → 注册进白名单。灌数/注册失败时把新建表删回去 ——
     * 重试时不会撞"表已存在"，也不留"建了表但没灌完数"的中间态。
     */
    public ImportPreview confirm(String file, String table, String operator) {
        String tableName = table == null ? "" : table.trim();
        if (!TABLE_NAME.matcher(tableName).matches()) {
            return new ImportPreview(file, tableName, List.of(), List.of(), List.of(), null,
                    "表名不合法：只允许 [schema.]标识符（字母/数字/下划线/$），不要引号或特殊字符");
        }
        ImportPreview p = preview(file);
        if (p.error() != null) {
            return p;
        }
        if (tableExists(tableName)) {
            return new ImportPreview(file, tableName, p.columns(), p.originalHeaders(), p.columnTypes(), p.rows(),
                    "表 " + tableName + " 已存在 —— 导入绝不覆盖已有的表，请换一个表名"
                            + "（如果那张表就是上次导入失败的残留，先手动 DROP 它）");
        }
        List<String> columns = p.columns();
        List<String> types = p.columnTypes();
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(columns.get(i)).append(' ').append(types.get(i));
        }
        ddl.append(")");
        try {
            jdbc.execute(ddl.toString());
        } catch (RuntimeException e) {
            return new ImportPreview(file, tableName, columns, p.originalHeaders(), types, p.rows(),
                    "建表失败：" + rootMessage(e));
        }
        long inserted;
        try {
            inserted = loadRows(resolve(file), tableName, columns, types);
        } catch (Exception e) {
            safeDrop(tableName);
            return new ImportPreview(file, tableName, columns, p.originalHeaders(), types, p.rows(),
                    "灌数失败（已回滚删除新建表，可换表名重试）：" + rootMessage(e));
        }
        TableRegistrar.Preview registered = registrar.confirm(tableName, operator);
        if (registered.error() != null) {
            safeDrop(tableName);
            return new ImportPreview(file, tableName, columns, p.originalHeaders(), types, inserted,
                    "数据已导入但注册白名单失败（表已回滚删除）：" + registered.error());
        }
        log.info("文件导入完成: file={} -> table={} rows={} columns={}", file, tableName, inserted, columns.size());
        return new ImportPreview(file, tableName, columns, p.originalHeaders(), types, inserted, null);
    }

    // ------------------------------------------------------------------
    // 路径解析：导入目录是默认的文件边界；allow-absolute-paths 打开后放行本地绝对路径

    private Path resolve(String file) throws IOException {
        if (file == null || file.isBlank()) {
            throw new IOException("文件名不能为空");
        }
        String name = file.trim().replace('\\', '/');
        Path candidate = Paths.get(name);
        if (candidate.isAbsolute()) {
            if (!properties.getFileImport().isAllowAbsolutePaths()) {
                throw new IOException("不接受绝对路径: " + file
                        + "（要么把文件放进导入目录 " + Paths.get(properties.getFileImport().getDir()).toAbsolutePath().normalize()
                        + " 后用相对路径，要么本地自用时配置 recon.agent.file-import.allow-absolute-paths=true 放开）");
            }
            Path target = candidate.toAbsolutePath().normalize();
            checkFileConstraints(target);
            return target;
        }
        Path root = Paths.get(properties.getFileImport().getDir()).toAbsolutePath().normalize();
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("路径必须在导入目录 " + root + " 之内（相对路径），不接受 " + file);
        }
        checkFileConstraints(target);
        return target;
    }

    /** 与路径来源无关的公共护栏：后缀白名单 + 必须是存在的普通文件。 */
    private void checkFileConstraints(Path target) throws IOException {
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IOException("只支持 .csv / .xlsx / .xls 文件，当前: " + target.getFileName());
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("文件不存在或不是普通文件: " + target);
        }
    }

    // ------------------------------------------------------------------
    // 文件解析：CSV（RFC 4180）与 Excel 统一成 headers + 行流

    private interface ParsedFile extends AutoCloseable {
        List<String> headers();

        /** 读接下来最多 {@code limit} 行。 */
        List<List<String>> nextRows(int limit) throws IOException;

        /** 数完剩余行（调用后文件读到 EOF，{@link #nextRows} 再调返回空）。 */
        long countRemaining() throws IOException;

        @Override
        void close() throws IOException;
    }

    private ParsedFile open(Path path) throws IOException {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")
                ? openCsv(path) : openExcel(path);
    }

    private ParsedFile openCsv(Path path) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8), 1 << 16);
        boolean threw = true;
        try {
            reader.mark(1);
            int bom = reader.read();
            if (bom != 0xFEFF) {
                reader.reset();
            }
            ParsedFile csv = new CsvFile(reader);
            threw = false;
            return csv;
        } finally {
            if (threw) {
                reader.close();
            }
        }
    }

    /**
     * RFC 4180 CSV：双引号转义、引号内的逗号与换行。分隔符按首行出现次数探测（逗号/分号/制表符）。
     * BOM 由 {@link #openCsv} 吃掉。
     */
    private static final class CsvFile implements ParsedFile {
        private final BufferedReader reader;
        private final char delimiter;
        private final List<String> headers;
        private boolean eof;

        CsvFile(BufferedReader reader) throws IOException {
            this.reader = reader;
            reader.mark(1 << 20);
            String first = reader.readLine();
            this.delimiter = detectDelimiter(first);
            reader.reset();
            List<String> h = readRawRecord();
            this.headers = h == null || (h.size() == 1 && h.get(0).isBlank()) ? List.of() : h;
        }

        private static char detectDelimiter(String line) {
            if (line == null) {
                return ',';
            }
            char best = ',';
            long bestCount = 0;
            for (char candidate : new char[]{',', ';', '\t'}) {
                long count = line.chars().filter(c -> c == candidate).count();
                if (count > bestCount) {
                    best = candidate;
                    bestCount = count;
                }
            }
            return best;
        }

        /** 读一条完整记录：引号内的逗号/换行/双引号转义都按 RFC 4180 处理。返回 null 表示 EOF。 */
        private List<String> readRawRecord() throws IOException {
            if (eof) {
                return null;
            }
            StringBuilder field = new StringBuilder();
            List<String> record = new ArrayList<>();
            boolean inQuotes = false;
            boolean quotedThisField = false;
            boolean anyChar = false;
            int c;
            while ((c = reader.read()) != -1) {
                anyChar = true;
                if (inQuotes) {
                    if (c == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append((char) c);
                    }
                    continue;
                }
                if (c == '"' && field.isEmpty() && !quotedThisField) {
                    inQuotes = true;
                    quotedThisField = true;
                } else if (c == delimiter) {
                    record.add(field.toString());
                    field.setLength(0);
                    quotedThisField = false;
                } else if (c == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') {
                        reader.reset();
                    }
                    record.add(field.toString());
                    return record;
                } else if (c == '\n') {
                    record.add(field.toString());
                    return record;
                } else {
                    field.append((char) c);
                }
            }
            if (!anyChar && field.isEmpty() && record.isEmpty()) {
                eof = true;
                return null;
            }
            record.add(field.toString());
            return record;
        }

        @Override
        public List<String> headers() {
            return headers;
        }

        @Override
        public List<List<String>> nextRows(int limit) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            while (rows.size() < limit) {
                List<String> record = readRawRecord();
                if (record == null) {
                    break;
                }
                rows.add(record);
            }
            return rows;
        }

        @Override
        public long countRemaining() throws IOException {
            long count = 0;
            while (readRawRecord() != null) {
                count++;
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private ParsedFile openExcel(Path path) throws IOException {
        InputStream in = Files.newInputStream(path);
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(in);
        } catch (Exception e) {
            in.close();
            throw new IOException("Excel 解析失败（文件损坏或不是合法的工作簿）：" + rootMessage(e));
        }
        return new ExcelFile(workbook);
    }

    /** 只读第一个 Sheet；单元格一律转字符串（整数去掉无意义的 .0）。 */
    private static final class ExcelFile implements ParsedFile {
        private final Workbook workbook;
        private final Sheet sheet;
        private final List<String> headers;
        private int nextRow;

        ExcelFile(Workbook workbook) {
            this.workbook = workbook;
            this.sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            List<String> h = new ArrayList<>();
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    h.add(cellText(cell));
                }
            }
            this.headers = h;
            this.nextRow = 1;
        }

        private static String cellText(Cell cell) {
            if (cell == null) {
                return "";
            }
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? cell.getLocalDateTimeCellValue().toString()
                        : stripTrailingZero(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCachedFormulaResultType() == CellType.NUMERIC
                        ? stripTrailingZero(cell.getNumericCellValue())
                        : cell.getCellFormula();
                default -> "";
            };
        }

        private static String stripTrailingZero(double value) {
            if (value == Math.rint(value) && Math.abs(value) < 1e15) {
                return String.valueOf((long) value);
            }
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }

        @Override
        public List<String> headers() {
            return headers;
        }

        @Override
        public List<List<String>> nextRows(int limit) {
            int lastRow = sheet.getLastRowNum();
            List<List<String>> rows = new ArrayList<>();
            while (nextRow <= lastRow && rows.size() < limit) {
                Row row = sheet.getRow(nextRow);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < headers.size(); i++) {
                    out.add(row == null ? "" : cellText(row.getCell(i)));
                }
                rows.add(out);
                nextRow++;
            }
            return rows;
        }

        @Override
        public long countRemaining() {
            int lastRow = sheet.getLastRowNum();
            return Math.max(0, lastRow - nextRow + 1L);
        }

        @Override
        public void close() throws IOException {
            workbook.close();
        }
    }

    // ------------------------------------------------------------------
    // 列名规范化与类型推断

    /**
     * 表头规范化：<b>只保留 ASCII 字母与数字</b>（其余转下划线）、空表头补 col_N、数字开头补前缀、
     * 重名加序号 —— 保证 DDL 在 H2 与 MySQL 上都合法。中文表头会变成 col_N，
     * 原文保留在 {@code originalHeaders} 里供人对照 —— 比起把方言差异留给运行时报错，这个取舍值得。
     */
    static List<String> normalizeColumns(List<String> headers) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i) == null ? "" : headers.get(i).trim();
            StringBuilder sb = new StringBuilder();
            raw.chars().forEach(c -> sb.append(c < 128 && Character.isLetterOrDigit(c) ? (char) c : '_'));
            String name = sb.toString();
            if (name.isEmpty() || name.chars().allMatch(c -> c == '_')) {
                name = "col_" + (i + 1);
            }
            if (Character.isDigit(name.charAt(0))) {
                name = "c" + name;
            }
            String candidate = name;
            int suffix = 2;
            while (out.contains(candidate)) {
                candidate = name + "_" + suffix++;
            }
            out.add(candidate);
        }
        return out;
    }

    /**
     * 类型推断：采样行逐列看。全整型 → BIGINT；有小数 → DECIMAL(24,6)；
     * 像日期时间 → DATETIME；超过 255 字符 → TEXT；否则 VARCHAR（宽度上取整到 32/64/128/255）。
     * 全空列给 VARCHAR(255)：宁可宽，不要因为推断激进让灌数失败。
     */
    static List<String> inferTypes(List<List<String>> sample, int columnCount) {
        List<String> types = new ArrayList<>();
        for (int col = 0; col < columnCount; col++) {
            boolean allInt = true;
            boolean allNumeric = true;
            boolean allDateTime = true;
            boolean anyValue = false;
            int maxLen = 0;
            for (List<String> row : sample) {
                String v = col < row.size() ? row.get(col) : "";
                if (v == null || v.isBlank()) {
                    continue;
                }
                anyValue = true;
                maxLen = Math.max(maxLen, v.length());
                if (!isInteger(v)) {
                    allInt = false;
                }
                if (!isNumeric(v)) {
                    allNumeric = false;
                }
                if (!looksLikeDateTime(v)) {
                    allDateTime = false;
                }
            }
            if (!anyValue) {
                types.add("VARCHAR(255)");
            } else if (allInt) {
                types.add("BIGINT");
            } else if (allNumeric) {
                types.add("DECIMAL(24,6)");
            } else if (allDateTime) {
                types.add("DATETIME");
            } else if (maxLen > 255) {
                types.add("TEXT");
            } else {
                int width = maxLen <= 32 ? 32 : maxLen <= 64 ? 64 : maxLen <= 128 ? 128 : 255;
                types.add("VARCHAR(" + width + ")");
            }
        }
        return types;
    }

    private static boolean isInteger(String v) {
        return v.matches("[+-]?\\d{1,18}");
    }

    private static boolean isNumeric(String v) {
        try {
            new BigDecimal(v.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean looksLikeDateTime(String v) {
        String s = v.trim();
        if (DATE_ONLY.matcher(s).matches()) {
            try {
                LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-M-d"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        if (DATE_TIME.matcher(s).matches()) {
            try {
                String normalized = s.replace('T', ' ');
                String pattern = normalized.contains(".")
                        ? "yyyy-M-d H:m:s.SSS" : "yyyy-M-d H:m[:s]";
                DateTimeFormatter.ofPattern(pattern).parse(normalized);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 灌数：值全部走 PreparedStatement，标识符已从严校验

    private long loadRows(Path path, String table, List<String> columns, List<String> types) throws IOException {
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";
        long inserted = 0;
        try (ParsedFile parsed = open(path)) {
            // CsvFile/ExcelFile 的构造已消费表头行，这里读到的都是数据
            while (true) {
                List<List<String>> rows = parsed.nextRows(BATCH_SIZE);
                if (rows.isEmpty()) {
                    break;
                }
                List<Object[]> params = new ArrayList<>(rows.size());
                for (List<String> row : rows) {
                    Object[] one = new Object[columns.size()];
                    for (int i = 0; i < columns.size() && i < types.size(); i++) {
                        String v = i < row.size() ? row.get(i) : "";
                        one[i] = v == null || v.isBlank() ? null : convert(v, types.get(i));
                    }
                    params.add(one);
                }
                jdbc.batchUpdate(sql, params);
                inserted += rows.size();
            }
        }
        return inserted;
    }

    /**
     * 数字列显式转 Long/BigDecimal（H2 严格模式不接受字符串到数字的隐式转换），其余原样交驱动适配。
     * 格式坏值宁可抛错让整体回滚，也不静默塞 null —— 导入的数据要对账，坏值必须人看见。
     */
    private static Object convert(String value, String type) {
        if (type.startsWith("BIGINT")) {
            return Long.parseLong(value.trim());
        }
        if (type.startsWith("DECIMAL")) {
            return new BigDecimal(value.trim());
        }
        return value;
    }

    private boolean tableExists(String table) {
        try {
            // WHERE 1=0 让 COUNT 恒为一行一列的 0：验证了表存在，又不扫任何数据
            jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE 1=0", Long.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void safeDrop(String table) {
        try {
            jdbc.execute("DROP TABLE " + table);
        } catch (RuntimeException e) {
            log.warn("回滚删除失败，需要手动清理: {} ({})", table, e.toString());
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
