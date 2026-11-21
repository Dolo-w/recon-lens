package io.github.dolow.reconlens.explain.pattern;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.domain.DiffRecord;
import io.github.dolow.reconlens.domain.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经验模式库（K2 / L0）三条硬规矩：
 * <ul>
 *   <li>机械可复算：同一条差异在任何时候判定一致；</li>
 *   <li>fail 保守：坏文件跳过、坏模式跳过，绝不让坏知识挡住归因主链路；</li>
 *   <li>外部覆盖内置：客户定制自己的病，同 id 外部赢。</li>
 * </ul>
 */
class PatternLibraryTest {

    @TempDir
    Path tempDir;

    private static DiffRecord mismatch(String left, String right, String ratio, String deltaValue) {
        return DiffRecord.of(1L, 0, "AMOUNT_MISMATCH", Severity.HIGH, "ORD-1", "amount",
                left, right,
                deltaValue == null ? null : new BigDecimal(deltaValue),
                ratio == null ? null : new BigDecimal(ratio));
    }

    private PatternLibrary library(String externalDir) {
        ReconProperties properties = new ReconProperties();
        properties.getExplain().getPatterns().setDir(externalDir == null ? "" : externalDir);
        return new PatternLibrary(properties);
    }

    @Test
    void builtinLibraryLoadsAndMatchesUnitScaleDisease() {
        PatternLibrary library = library(null);

        assertThat(library.size()).as("内置库应装载数十条常见病").isGreaterThanOrEqualTo(14);

        // left=100, right=1 → ratio = (100-1)/100 = 0.99（比率饱和）→ 单位换算病
        List<DiffPattern> hits = library.match(mismatch("100", "1", "0.99", "99"));
        assertThat(hits).extracting(DiffPattern::id).contains("UNIT_SCALE_X100");
        assertThat(hits.get(0).hypothesis()).isNotBlank();
    }

    @Test
    void smallPositiveRatioMatchesFeePattern() {
        PatternLibrary library = library(null);

        // 0.6% → 手续费/服务费率档
        List<DiffPattern> hits = library.match(mismatch("100.00", "100.60", "0.006", "0.60"));
        assertThat(hits).extracting(DiffPattern::id).contains("FEE_RATE_SMALL");
    }

    @Test
    void pendingDiffTypeIsMarkedNotMissing() {
        PatternLibrary library = library(null);
        DiffRecord pending = DiffRecord.of(1L, 0, "MISSING_LEFT_PENDING", Severity.HIGH,
                "ORD-1", null, null, "100", null, null);

        List<DiffPattern> hits = library.match(pending);
        assertThat(hits).extracting(DiffPattern::id).contains("PENDING_NOT_MISSING");
        assertThat(hits.get(0).hypothesis()).as("PENDING 的假设必须明说'不是异常'").contains("不是异常");
    }

    @Test
    void shapeOutOfAllBandsMatchesNothing() {
        PatternLibrary library = library(null);
        // 比率 0.001（千分之一）低于费率档下限 0.003，金额差 10 超过抹零上限
        assertThat(library.match(mismatch("1000.00", "1001.00", "0.001", "1.00"))).isEmpty();
    }

    @Test
    void disabledLibraryMatchesNothing() {
        ReconProperties properties = new ReconProperties();
        properties.getExplain().getPatterns().setEnabled(false);
        PatternLibrary library = new PatternLibrary(properties);

        assertThat(library.enabled()).isFalse();
        assertThat(library.match(mismatch("100", "1", "99", "99"))).isEmpty();
    }

    /** 外部目录：同 id 覆盖内置（客户定制），新 id 追加。 */
    @Test
    void externalDirOverridesBuiltinByIdAndAddsNewPatterns() throws Exception {
        Files.writeString(tempDir.resolve("custom.yml"), """
                patterns:
                  - id: UNIT_SCALE_X100
                    family: 金额量级
                    name: 客户定制版单位病
                    match:
                      deltaRatioIn: [98.5, 99.5]
                    hypothesis: 客户环境的单位换算约定（外部覆盖版）
                    probeHint: 查客户自己的换算表
                  - id: CUSTOM_LOCAL_DISEASE
                    family: 金额量级
                    name: 客户特有病
                    match:
                      deltaRatioMin: 0.031
                      deltaRatioMax: 0.032
                    hypothesis: 客户特有病假设
                """);

        PatternLibrary library = library(tempDir.toString());

        assertThat(library.size()).as("内置 + 外部，同 id 合并").isGreaterThanOrEqualTo(16);
        // ratio 99 → 覆盖版 UNIT_SCALE_X100（[98.5, 99.5]）命中
        assertThat(library.match(mismatch("100", "1", "99", "99")))
                .extracting(DiffPattern::id).contains("UNIT_SCALE_X100");
        // ratio 3.1 → 客户特有病（[3.0, 3.2]）命中
        assertThat(library.match(mismatch("100", "103.1", "0.031", "3.1")))
                .extracting(DiffPattern::id).contains("CUSTOM_LOCAL_DISEASE");
        DiffPattern overridden = library.all().stream()
                .filter(p -> p.id().equals("UNIT_SCALE_X100")).findFirst().orElseThrow();
        assertThat(overridden.hypothesis()).contains("外部覆盖版");
    }

    /** 坏 YAML 只跳过那份文件，内置库照常工作 —— 坏知识不能挡主链路。 */
    @Test
    void corruptExternalFileIsSkippedNotFatal() throws Exception {
        Files.writeString(tempDir.resolve("broken.yml"), "patterns: [ {{{{ 这不是 YAML");
        Files.writeString(tempDir.resolve("good.yml"), """
                patterns:
                  - id: GOOD_EXTERNAL
                    family: 金额量级
                    match:
                      deltaRatioMin: 0.07
                      deltaRatioMax: 0.075
                    hypothesis: 外部好模式
                """);

        PatternLibrary library = library(tempDir.toString());

        assertThat(library.size()).isGreaterThanOrEqualTo(16);
        assertThat(library.match(mismatch("100", "107.2", "0.072", "7.2")))
                .extracting(DiffPattern::id).contains("GOOD_EXTERNAL");
    }

    /** 无条件模式是全命中炸弹，从 YAML 到构造器每一层都要拦。 */
    @Test
    void patternWithoutMatchIsRejected() throws Exception {
        Files.writeString(tempDir.resolve("bad.yml"), """
                patterns:
                  - id: NO_MATCH_BOMB
                    family: 金额量级
                    hypothesis: 没有条件 = 全命中
                """);

        PatternLibrary library = library(tempDir.toString());

        assertThat(library.all()).extracting(DiffPattern::id).doesNotContain("NO_MATCH_BOMB");
    }

    /** 外部文件 mtime 变化触发重载（热更）。 */
    @Test
    void externalFileChangeIsPickedUpWithoutRestart() throws Exception {
        Path file = tempDir.resolve("hot.yml");
        Files.writeString(file, """
                patterns:
                  - id: HOT_PATTERN
                    family: 金额量级
                    match:
                      deltaRatioMin: 9.0
                      deltaRatioMax: 9.5
                    hypothesis: 热更前
                """);
        PatternLibrary library = library(tempDir.toString());
        assertThat(library.all()).extracting(DiffPattern::id).contains("HOT_PATTERN");

        // mtime 精度是秒级：保证时间戳确实变了
        Thread.sleep(1100);
        Files.writeString(file, """
                patterns:
                  - id: HOT_PATTERN
                    family: 金额量级
                    match:
                      deltaRatioMin: 9.0
                      deltaRatioMax: 9.5
                    hypothesis: 热更后
                """);

        assertThat(library.all()
                .stream().filter(p -> p.id().equals("HOT_PATTERN")).findFirst().orElseThrow()
                .hypothesis()).isEqualTo("热更后");
    }
}
