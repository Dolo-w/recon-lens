package io.github.dolow.reconlens.dsl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.dolow.reconlens.dsl.ShardSplitAnalyzer.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片口径三分判定的表驱动用例。
 *
 * <p>这张表就是实现的行为规格：4 个合法形态、归一化等价、2 个反例、3 个"抽不出来"。
 * 加形态时先加一行用例，再改 {@link ShardSplitAnalyzer}。
 */
class ShardSplitAnalyzerTest {

    private final ShardSplitAnalyzer analyzer = new ShardSplitAnalyzer();

    private static final String MOD_LEFT = "MOD(order_no, {shards}) = {index}";
    private static final String MOD_RIGHT = "MOD(order_no, {shards}) = {index}";
    private static final String BUCKET_LEFT = "shard_bucket = {index} AND shard_bucket < {shards}";
    private static final String BUCKET_RIGHT = "shard_bucket = {index} AND shard_bucket < {shards}";

    static Stream<Arguments> cases() {
        return Stream.of(
                // ---------- 4 个合法形态：两侧一致 → SAME ----------
                Arguments.of("MOD 取模", MOD_LEFT, MOD_RIGHT, Verdict.SAME, "MOD(ORDER_NO)"),
                Arguments.of("CRC32 取模", "CRC32(order_no) % {shards} = {index}",
                        "CRC32(order_no) % {shards} = {index}", Verdict.SAME, "CRC32(ORDER_NO)"),
                Arguments.of("ORA_HASH 取模", "ORA_HASH(order_no, {shards}) = {index}",
                        "ORA_HASH(order_no, {shards}) = {index}", Verdict.SAME, "ORA_HASH(ORDER_NO)"),
                Arguments.of("桶列 + 边界谓词", BUCKET_LEFT, BUCKET_RIGHT, Verdict.SAME, "SHARD_BUCKET"),

                // ---------- 归一化：别名 / 反引号 / 空白 / 大小写 / 等号方向 都不该影响判定 ----------
                Arguments.of("表别名与空白大小写", "MOD(t.order_no, {shards}) = {index}",
                        "mod( `order_no` ,{shards} ) = {index}", Verdict.SAME, "MOD(ORDER_NO)"),
                Arguments.of("等号左右互换", "{index} = MOD(order_no, {shards})", MOD_RIGHT, Verdict.SAME, "MOD(ORDER_NO)"),
                Arguments.of("外层冗余括号", "( MOD(order_no, {shards}) ) = {index}", MOD_RIGHT, Verdict.SAME, "MOD(ORDER_NO)"),

                // ---------- 恒真谓词干扰：为了凑齐 {shards} 写的 {shards} = {shards} 不能影响抽取 ----------
                Arguments.of("恒真谓词在左", "shard_bucket = {index} AND {shards} = {shards}",
                        "shard_bucket = {index} AND shard_bucket < {shards}", Verdict.SAME, "SHARD_BUCKET"),

                // ---------- 反例一：两侧各自引用"不同的键" ----------
                Arguments.of("左按 id 右按 order_no", "MOD(id, {shards}) = {index}",
                        "MOD(order_no, {shards}) = {index}", Verdict.DIFFERENT, null),
                Arguments.of("桶列不同", "shard_bucket = {index} AND {shards} = {shards}",
                        "other_bucket = {index} AND {shards} = {shards}", Verdict.DIFFERENT, null),

                // ---------- 反例二：同一个键、不同的切分函数（只比键会漏掉这一种）----------
                Arguments.of("同键不同函数", "MOD(order_no, {shards}) = {index}",
                        "CRC32(order_no) % {shards} = {index}", Verdict.DIFFERENT, null),

                // ---------- 抽不出来 → UNKNOWN（只告警，不拒绝）----------
                Arguments.of("右侧完全不引用切分键", "MOD(order_no, {shards}) = {index}",
                        "{shards} = {shards} AND id > 0", Verdict.UNKNOWN, null),
                Arguments.of("未识别形态", "FLOOR(order_no / {shards}) = {index}",
                        "FLOOR(order_no / {shards}) = {index}", Verdict.UNKNOWN, null),
                Arguments.of("写死分母", "id % 8 = {index} AND {shards} = {shards}",
                        "id % 8 = {index} AND {shards} = {shards}", Verdict.UNKNOWN, null),
                Arguments.of("多个谓词同时携带 {index}",
                        "MOD(id, {shards}) = {index} AND MOD(uid, {shards}) = {index}",
                        "MOD(id, {shards}) = {index}", Verdict.UNKNOWN, null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void judgesShardSplit(String scenario, String whereLeft, String whereRight, Verdict expected, String expectedKey) {
        ShardSplitAnalyzer.Result result = analyzer.analyze(4, whereLeft, whereRight);

        assertThat(result.verdict()).as("判定: %s", result.reason()).isEqualTo(expected);
        if (expectedKey != null) {
            assertThat(result.leftKey()).isEqualTo(expectedKey);
            assertThat(result.rightKey()).isEqualTo(expectedKey);
        }
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void countsSingleShardAsSameWithoutLookingAtWhere() {
        // 单分片没有切分语义：不切就不会切错。这条的合法性依赖解析器拒绝 count=1 + where（见下一个用例）。
        assertThat(analyzer.analyze(1, null, null).verdict()).isEqualTo(Verdict.SAME);
        assertThat(analyzer.analyze(0, "whatever", "whatever").verdict()).isEqualTo(Verdict.SAME);
        assertThat(analyzer.analyze(new ShardSpec(1, 1, null, null)).verdict()).isEqualTo(Verdict.SAME);
    }

    @Test
    void readsShardSpecRecord() {
        assertThat(analyzer.analyze(new ShardSpec(4, 4, MOD_LEFT, "MOD(oi, {shards}) = {index}")).different()).isTrue();
    }

    // ---------- 与解析器的耦合点：F1 的上游保证 ----------

    /**
     * {@code count == 1 → SAME} 的安全性来自这条：解析器根本不放行"单分片 + where"。
     * 哪天有人放宽 {@code rejectSingleShardWhere}，这个用例会先红，而不是等到线上差异少一半。
     */
    @Test
    void parserRejectsSingleShardWithWhere() {
        assertThatThrownBy(() -> parse(singleShardYaml("  whereLeft: \"shard_bucket = {index}\"\n")))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("shard.count = 1 时不应配置 shard.whereLeft");
        assertThatThrownBy(() -> parse(singleShardYaml("  whereRight: \"shard_bucket = {index}\"\n")))
                .isInstanceOf(RuleException.class)
                .hasMessageContaining("shard.count = 1 时不应配置 shard.whereRight");
    }

    // ---------- 解析器接入：DIFFERENT 必须告警，SAME 必须闭嘴 ----------

    @Test
    void parserWarnsWhenBothSidesSplitByDifferentKeys() {
        List<ILoggingEvent> events = captureParserWarnings(
                shardedYaml("MOD(id, {shards}) = {index}", "MOD(order_no, {shards}) = {index}"));

        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).getFormattedMessage())
                .contains(ShardSplitAnalyzer.VIOLATION_CODE)
                .contains("两侧分片口径不一致")
                .contains("MOD(ID)")
                .contains("MOD(ORDER_NO)");
    }

    @Test
    void parserStaysSilentWhenBothSidesSplitByTheSameKey() {
        assertThat(captureParserWarnings(shardedYaml(MOD_LEFT, MOD_RIGHT))).isEmpty();
    }

    private List<ILoggingEvent> captureParserWarnings(String yaml) {
        Logger logger = (Logger) LoggerFactory.getLogger(RuleParser.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            parse(yaml);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private RuleSpec parse(String yaml) {
        return new RuleParser().parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    private static String shardedYaml(String whereLeft, String whereRight) {
        return """
                code: T_SHARD
                left:
                  table: bill_left
                  keyField: order_no
                right:
                  table: bill_right
                  keyField: order_no
                shard:
                  count: 4
                  whereLeft: "%s"
                  whereRight: "%s"
                compare:
                  - field: amount
                    type: DECIMAL
                    diffType: AMOUNT_MISMATCH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                """.formatted(whereLeft, whereRight);
    }

    private static String singleShardYaml(String extraShardLine) {
        return """
                code: T_SINGLE
                left:
                  table: bill_left
                  keyField: order_no
                right:
                  table: bill_right
                  keyField: order_no
                shard:
                  count: 1
                %scompare:
                  - field: amount
                    type: DECIMAL
                    diffType: AMOUNT_MISMATCH
                missing:
                  leftMissingDiffType: MISSING_LEFT
                  rightMissingDiffType: MISSING_RIGHT
                """.formatted(extraShardLine);
    }
}
