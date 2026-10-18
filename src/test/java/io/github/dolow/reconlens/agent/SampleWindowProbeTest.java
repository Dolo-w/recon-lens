package io.github.dolow.reconlens.agent;

import io.github.dolow.reconlens.TestRules;
import io.github.dolow.reconlens.dsl.RuleSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取窗的两条硬约束：<b>一次探测拿全窗信息</b>、<b>取并集不取交集</b>。
 *
 * <p>第二条不是风格问题。演示数据里左表键到 ORD20260009、右表键到 ORD20260011，
 * 而"账单缺失 ORD20260011"正是最该被检出的差异之一。
 * 取交集会把 hi 压到 ORD20260009，于是那个键连样本都进不去 —— T3/T4 的输入就变成自证的。
 */
@SpringBootTest(properties = {
        // 每个测试类一个独立的库名：两个上下文都执行 data.sql，共用一个内存库会主键冲突
        "spring.datasource.url=jdbc:h2:mem:trial_probe;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "recon.engine.run-demo-on-startup=false",
        "recon.agent.enabled=true",
        "recon.agent.schema.tables=bill_left,bill_right"
})
class SampleWindowProbeTest {

    @Autowired
    private SampleWindowProbe probe;

    private final RuleSpec rule = TestRules.bill();

    @Test
    void unionKeepsTheTailThatIntersectionWouldCut() {
        SampleWindowProbe.Window window = probe.probe(rule.left(), rule.right(), 500);

        assertThat(window.left().rows()).isEqualTo(10);
        assertThat(window.right().rows()).isEqualTo(10);
        assertThat(window.lo()).isEqualTo("ORD20260001");
        // 交集会得到 ORD20260009 —— 正好把"账单缺失 ORD20260011"的那个键裁掉
        assertThat(window.hi()).isEqualTo("ORD20260011");
    }

    @Test
    void windowRowsIsPerSideNotTotal() {
        SampleWindowProbe.Window window = probe.probe(rule.left(), rule.right(), 5);
        assertThat(window.left().rows()).isEqualTo(5);
        assertThat(window.right().rows()).isEqualTo(5);
        // 每侧各 5 行，两侧的区间端点都参与并集
        assertThat(window.hi()).isEqualTo(SampleWindowProbe.max(
                window.left().hi(), window.right().hi()));
    }

    @Test
    void emptySideDoesNotBlockTheOther() {
        // 单侧为空时窗口仍应成立（另一侧的行照样要验），由 T5-a 去报"样本不足"
        SampleWindowProbe.Window window = probe.probe(rule.left(), rule.right(), 500);
        assertThat(window.empty()).isFalse();
    }

    @Test
    void requireSameCollationToleratesUnknownCollation() {
        // H2 的 information_schema 没有 collation_name：查不到时返回 null，表示"无法判定"。
        // 这时绝不能抛异常 —— 那会让所有 H2 环境的试算全部降级。
        SampleWindowProbe.Window window = probe.probe(rule.left(), rule.right(), 500);
        probe.requireSameCollation(window);
    }

    @Test
    void stringLiteralsAreQuotedAndEscaped() {
        String literal = SampleMaterializer.literal(rule.left(), "O'BRIEN");
        assertThat(literal).isEqualTo("'O''BRIEN'");
    }
}
