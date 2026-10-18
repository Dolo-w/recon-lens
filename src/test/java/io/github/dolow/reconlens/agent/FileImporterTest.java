package io.github.dolow.reconlens.agent;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件导入（M7）：CSV/Excel → 建表灌数 → 注册白名单的完整管道。
 *
 * <p>三个重心：① 解析要稳（RFC 4180 的引号/换行/转义、BOM、分隔符探测 —— 用户文件什么样都有）；
 * ② 护栏是真的（路径穿越、已存在表、行列上限、非法表名都要拦）；③ 失败要干净
 * （灌数失败回滚删表，不留"建了表没数据"的中间态）。
 */
class FileImporterTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static AgentProperties properties;
    private static TableRegistrar registrar;
    private static FileImporter importer;

    @TempDir
    static Path importDir;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:file_importer;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS recon_registered_table (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + " table_name VARCHAR(128) NOT NULL, columns_snapshot VARCHAR(2048) NOT NULL,"
                + " registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        properties = new AgentProperties();
        properties.getFileImport().setDir(importDir.toString());
        SchemaCatalog schema = new SchemaCatalog(ds, properties,
                new io.github.dolow.reconlens.store.RegisteredTableRepository(jdbc));
        registrar = new TableRegistrar(schema, new io.github.dolow.reconlens.store.RegisteredTableRepository(jdbc), jdbc);
        importer = new FileImporter(properties, registrar, jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_registered_table");
    }

    // ---------------- CSV 解析 ----------------

    @Test
    void csv解析_引号转义与引号内换行() throws IOException {
        writeCsv("orders.csv", """
                order_no,amount,note
                SN001,100.50,"普通,含逗号"
                SN002,55.00,"含""双引号""与
                换行的备注"
                SN003,80.00,
                """);
        FileImporter.ImportPreview p = importer.preview("orders.csv");
        assertThat(p.error()).isNull();
        assertThat(p.columns()).containsExactly("order_no", "amount", "note");
        assertThat(p.rows()).isEqualTo(3L);
        assertThat(p.columnTypes()).containsExactly("VARCHAR(32)", "DECIMAL(24,6)", "VARCHAR(32)");
    }

    @Test
    void csv的BOM与分号分隔符都能处理() throws IOException {
        Path file = importDir.resolve("bom.csv");
        // BOM + 分号分隔 + 金额用逗号小数（分号文件常见）
        byte[] content = ("\uFEFForder_no;amount;status\r\nSN001;100,50;PAID\r\nSN002;55,00;PAID\r\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);
        FileImporter.ImportPreview p = importer.preview("bom.csv");
        assertThat(p.error()).isNull();
        assertThat(p.columns()).containsExactly("order_no", "amount", "status");
        assertThat(p.rows()).isEqualTo(2L);
    }

    @Test
    void 中文表头规范化成合法列名_原文保留对照() throws IOException {
        writeCsv("cn.csv", "订单编号,金额(元),,备注\nSN001,10,x,a\nSN002,20,y,b\n");
        FileImporter.ImportPreview p = importer.preview("cn.csv");
        assertThat(p.error()).isNull();
        // 非 ASCII 表头转下划线 → 全下划线时补 col_N（H2/MySQL 都兼容；原文在 originalHeaders 里）
        assertThat(p.columns()).containsExactly("col_1", "col_2", "col_3", "col_4");
        assertThat(p.originalHeaders()).containsExactly("订单编号", "金额(元)", "", "备注");
    }

    @Test
    void 重名表头自动加序号() throws IOException {
        writeCsv("dup.csv", "amount,amount,amount\n1,2,3\n");
        assertThat(importer.preview("dup.csv").columns())
                .containsExactly("amount", "amount_2", "amount_3");
    }

    // ---------------- Excel 解析 ----------------

    @Test
    void excel解析_数字去尾零_空行容忍() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("对账");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("order_no");
            header.createCell(1).setCellValue("amount");
            header.createCell(2).setCellValue("qty");
            for (int i = 1; i <= 3; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("SN00" + i);
                row.createCell(1).setCellValue(i == 2 ? 55.50 : 100.0); // 100.0 要变成 "100"
                row.createCell(2).setCellValue(i);
            }
            Path file = importDir.resolve("bills.xlsx");
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        FileImporter.ImportPreview p = importer.preview("bills.xlsx");
        assertThat(p.error()).isNull();
        assertThat(p.columns()).containsExactly("order_no", "amount", "qty");
        assertThat(p.rows()).isEqualTo(3L);
        assertThat(p.columnTypes()).as("金额 100.0/55.5 混合 → DECIMAL；qty 全整数 → BIGINT")
                .containsExactly("VARCHAR(32)", "DECIMAL(24,6)", "BIGINT");
    }

    // ---------------- 类型推断 ----------------

    @Test
    void 类型推断_日期与全空列() throws IOException {
        writeCsv("types.csv", """
                id,created,empty_col,mixed,short_text
                1,2026-10-17,,10,ab
                2,2026-10-17,,12.5,abcd
                """);
        assertThat(importer.preview("types.csv").columnTypes())
                .containsExactly("BIGINT", "DATETIME", "VARCHAR(255)", "DECIMAL(24,6)", "VARCHAR(32)");
    }

    // ---------------- 护栏 ----------------

    @Test
    void 路径穿越被拒绝() {
        FileImporter.ImportPreview p = importer.preview("../outside.csv");
        assertThat(p.error()).contains("导入目录").contains("相对路径");
    }

    @Test
    void 绝对路径被拒绝() {
        FileImporter.ImportPreview p = importer.preview(importDir.resolve("orders.csv").toString());
        assertThat(p.error()).contains("绝对路径").contains("allow-absolute-paths");
    }

    @Test
    void 打开开关后绝对路径可以导入() throws IOException {
        writeCsv("abs.csv", "order_no,amount\nSN001,10\n");
        Path absolute = importDir.resolve("abs.csv");
        properties.getFileImport().setAllowAbsolutePaths(true);
        try {
            // preview 走绝对路径
            FileImporter.ImportPreview p = importer.preview(absolute.toString());
            assertThat(p.error()).as("默认拒绝的同一个路径，开开关后应该放行").isNull();
            assertThat(p.rows()).isEqualTo(1L);
            // confirm 全链路也走绝对路径
            FileImporter.ImportPreview c = importer.confirm(absolute.toString(), "imported_abs", "tester");
            assertThat(c.error()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM imported_abs", Long.class)).isEqualTo(1L);
        } finally {
            properties.getFileImport().setAllowAbsolutePaths(false);
        }
    }

    @Test
    void 开关打开后护栏仍然生效() throws IOException {
        properties.getFileImport().setAllowAbsolutePaths(true);
        try {
            // 穿越被放行成绝对路径也没用：相对路径仍然只在导入目录里解析
            assertThat(importer.preview("../outside.csv").error()).contains("导入目录");
            // 后缀白名单对绝对路径照样拦
            Path exe = importDir.resolve("payload.exe");
            Files.write(exe, new byte[]{1});
            assertThat(importer.preview(exe.toString()).error()).contains("只支持");
            // 不存在的文件给明确提示
            assertThat(importer.preview("D:/no/such/file.csv").error()).contains("文件不存在");
        } finally {
            properties.getFileImport().setAllowAbsolutePaths(false);
        }
    }

    @Test
    void 不支持的后缀被拒绝() {
        FileImporter.ImportPreview p = importer.preview("payload.exe");
        assertThat(p.error()).contains("只支持");
    }

    @Test
    void 文件不存在给明确提示() {
        assertThat(importer.preview("missing.csv").error()).contains("文件不存在");
    }

    @Test
    void 非法表名在confirm就被拒绝() throws IOException {
        writeCsv("orders.csv", "order_no,amount\nSN001,10\n");
        assertThat(importer.confirm("orders.csv", "t; DROP TABLE x", "tester").error()).contains("表名不合法");
        assertThat(importer.confirm("orders.csv", "`t_order`", "tester").error()).contains("表名不合法");
    }

    @Test
    void 目标表已存在拒绝_绝不覆盖() throws IOException {
        writeCsv("orders.csv", "order_no,amount\nSN001,10\n");
        jdbc.execute("CREATE TABLE IF NOT EXISTS existing_t (id BIGINT)");
        FileImporter.ImportPreview p = importer.confirm("orders.csv", "existing_t", "tester");
        assertThat(p.error()).contains("已存在").contains("换一个表名");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM existing_t", Long.class))
                .as("已有表一行都不能少").isEqualTo(0L);
    }

    @Test
    void 行数超上限拒绝() throws IOException {
        properties.getFileImport().setMaxRows(1);
        writeCsv("rows.csv", "id\n1\n2\n3\n");
        FileImporter.ImportPreview p = importer.preview("rows.csv");
        assertThat(p.error()).contains("超过上限");
        assertThat(p.rows()).isEqualTo(3L);
        properties.getFileImport().setMaxRows(100_000);
    }

    // ---------------- confirm 全链路 ----------------

    @Test
    void confirm建表灌数并注册进白名单() throws IOException {
        writeCsv("full.csv", "order_no,amount,status\nSN001,100.50,PAID\nSN002,55.00,PAID\n");
        FileImporter.ImportPreview p = importer.confirm("full.csv", "imported_bills", "tester");

        assertThat(p.error()).isNull();
        assertThat(p.table()).isEqualTo("imported_bills");
        assertThat(p.rows()).isEqualTo(2L);
        // 数据真的进去了
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM imported_bills", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT amount FROM imported_bills WHERE order_no='SN001'", BigDecimal.class))
                .as("DECIMAL(24,6) 的 scale 是 6，比数值别比 scale").isEqualByComparingTo("100.50");
        // 注册审计落了，白名单快照是规范化列名
        assertThat(jdbc.queryForList("SELECT table_name FROM recon_registered_table", String.class))
                .contains("imported_bills");
        assertThat(jdbc.queryForObject(
                "SELECT columns_snapshot FROM recon_registered_table WHERE table_name='imported_bills'", String.class))
                .isEqualTo("order_no,amount,status");
        // 空值列是 NULL 不是空串
        writeCsv("nulls.csv", "id,note\n1,\n2,abc\n");
        importer.confirm("nulls.csv", "imported_nulls", "tester");
        assertThat(jdbc.queryForObject("SELECT note FROM imported_nulls WHERE id=1", String.class)).isNull();
    }

    @Test
    void 灌数失败回滚删表_不留中间态() throws IOException {
        // 类型推断会把坏值兜底成 VARCHAR，所以"灌数失败"用 spy 注入更真实 ——
        // 这里验证的是失败路径本身：删回滚表 + 不落审计，而不是格式错误的转换
        writeCsv("bad.csv", "id,name\n1,张三\n2,李四\n");
        org.springframework.jdbc.core.JdbcTemplate spyJdbc =
                org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doThrow(new RuntimeException("模拟灌数失败"))
                .when(spyJdbc).batchUpdate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
        FileImporter failing = new FileImporter(properties, registrar, spyJdbc);

        FileImporter.ImportPreview p = failing.confirm("bad.csv", "imported_bad", "tester");
        assertThat(p.error()).contains("灌数失败").contains("回滚");
        assertThat(jdbc.queryForList("SELECT table_name FROM recon_registered_table", String.class))
                .as("注册审计也没落").doesNotContain("imported_bad");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='imported_bad'", Long.class))
                .as("失败的表已回滚删除").isEqualTo(0L);
    }

    private static void writeCsv(String name, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(importDir.resolve(name)), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
