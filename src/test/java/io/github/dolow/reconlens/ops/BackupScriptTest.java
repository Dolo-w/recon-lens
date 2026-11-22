package io.github.dolow.reconlens.ops;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 备份/恢复脚本的"能跑"验证（清单 P0-7）。
 *
 * <p><b>为什么脚本也需要测试</b>：这两个脚本平时没人执行 —— 它们只在出事故时才被想起。
 * 而"出事故时才发现脚本有语法错/参数解析炸了"，是运维里最典型也最致命的一类失败。
 * 所以这里至少保证：语法正确、--dry-run 能跑通、关键保护性参数没被删掉。
 *
 * <p>真正的恢复演练必须在有 MySQL 的环境做（见 docs/backup-restore-sop.md 第五节）。
 * 本测试不假装自己验证了那件事。
 */
class BackupScriptTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    private static Path script(String name) {
        return ROOT.resolve("scripts").resolve(name);
    }

    /** 没有 bash（比如纯 Windows CI）就跳过；这不是"测试通过"，是"这个环境测不了"。 */
    private static boolean bashAvailable() {
        try {
            Process p = new ProcessBuilder("bash", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("脚本不能挂住").isTrue();
        return out + "\n[exit=" + p.exitValue() + "]";
    }

    @Test
    @DisplayName("两个脚本都存在")
    void scriptsExist() {
        assertThat(script("backup.sh")).exists();
        assertThat(script("restore.sh")).exists();
    }

    @Test
    @DisplayName("bash -n 语法检查通过（出事故时才跑第一次的脚本，语法错是致命的）")
    void syntaxIsValid() throws Exception {
        Assumptions.assumeTrue(bashAvailable(), "本机没有 bash，跳过");
        for (String name : List.of("backup.sh", "restore.sh")) {
            assertThat(run("bash", "-n", script(name).toString()))
                    .as(name + " 语法必须合法")
                    .contains("[exit=0]");
        }
    }

    @Test
    @DisplayName("backup.sh --dry-run 能跑且不产生文件")
    void backupDryRun() throws Exception {
        Assumptions.assumeTrue(bashAvailable(), "本机没有 bash，跳过");

        String out = run("bash", script("backup.sh").toString(), "--dry-run");

        assertThat(out).contains("[exit=0]");
        assertThat(out).contains("mysqldump");
        assertThat(out).contains("未连数据库");
    }

    @Test
    @DisplayName("restore.sh --dry-run 能跑（且默认恢复目标是新库，不碰同名库）")
    void restoreDryRun() throws Exception {
        Assumptions.assumeTrue(bashAvailable(), "本机没有 bash，跳过");

        Path fake = Files.createTempFile("recon-backup-", ".sql.gz");
        Files.writeString(fake, "placeholder");

        String out = run("bash", script("restore.sh").toString(), fake.toString(), "--dry-run");

        assertThat(out).contains("[exit=0]");
        assertThat(out).contains("recon_restore_verify_")
                .as("不给 --database 时必须恢复到新验证库 —— 恢复演练最常见的错误是手滑覆盖生产");
        Files.deleteIfExists(fake);
    }

    @Test
    @DisplayName("restore.sh 指定已有库却没 --yes 时拒绝执行")
    void restoreRefusesOverwriteWithoutConfirmation() throws Exception {
        Assumptions.assumeTrue(bashAvailable(), "本机没有 bash，跳过");

        Path fake = Files.createTempFile("recon-backup-", ".sql.gz");
        Files.writeString(fake, "placeholder");

        String out = run("bash", script("restore.sh").toString(), fake.toString(),
                "--database", "recon_lens", "--dry-run");

        assertThat(out).contains("[exit=5]");
        assertThat(out).contains("缺少 --yes");
        Files.deleteIfExists(fake);
    }

    @Test
    @DisplayName("保护性参数没被误删（凭证走文件、单事务、备份后校验、保留策略）")
    void safetyFlagsPresent() throws IOException {
        String backup = Files.readString(script("backup.sh"), StandardCharsets.UTF_8);
        assertThat(backup)
                .as("凭证不能出现在命令行参数里")
                .contains("defaults-extra-file");
        assertThat(backup)
                .as("必须 --single-transaction，否则备份会锁表影响业务")
                .contains("--single-transaction");
        assertThat(backup)
                .as("备份完必须立刻校验，坏文件要当场发现而不是等恢复那天")
                .contains("gzip -t");
        assertThat(backup)
                .as("必须有保留策略，否则备份目录会把磁盘吃满")
                .contains("-mtime");

        String restore = Files.readString(script("restore.sh"), StandardCharsets.UTF_8);
        assertThat(restore).contains("recon_restore_verify_");
        assertThat(restore).contains("--yes");
        assertThat(restore).contains("information_schema.tables");
    }
}
