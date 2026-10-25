package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摘要归纳层的边界。
 *
 * <p>要钉的不是"归纳得好不好"（那是模型的事），而是两条硬规则：
 * <b>没有模型时一个字都不能改</b>，以及<b>模型编造规则编码/任务号时必须整段丢弃</b>。
 * 后者尤其重要——摘要里的 id 写错，复盘时会照着错的线索去翻原文。
 */
class SummaryEnricherTest {

    private final ObjectMapper json = new ObjectMapper();

    private SummaryEnricher enricher(boolean enabled, String apiKey) {
        return new SummaryEnricher(new LlmChatClient("https://example.invalid", apiKey,
                "deepseek-chat", 500, json), enabled);
    }

    private static ChatTurn turn(String role, String content, int seq) {
        return new ChatTurn(seq, 1L, seq, role, content, null, null, null);
    }

    private static final List<ChatTurn> TURNS = List.of(
            turn("user", "跑一下 BILL_VS_SETTLE", 1),
            turn("assistant", "任务 #12 完成，差异 4 条", 2),
            turn("user", "为什么差这么多", 3));

    @Test
    @DisplayName("没有模型：原样返回截断版，不改一个字")
    void 没有模型不改() {
        String truncated = "〔事实〕ruleCode=BILL_VS_SETTLE jobId=12";
        assertThat(enricher(true, "").enrich(truncated, TURNS)).isEqualTo(truncated);
        assertThat(enricher(false, "sk-x").enrich(truncated, TURNS)).isEqualTo(truncated);
    }

    @Test
    @DisplayName("合规的归纳：拼在截断版前面")
    void 合规归纳() {
        String truncated = "〔事实〕jobId=12";
        String model = "〔结论〕\n- 规则 BILL_VS_SETTLE 跑完了，任务 #12 有 4 条差异\n〔未决〕\n- 差异原因还没定位";
        assertThat(SummaryEnricher.compose(truncated, model, TURNS))
                .startsWith("〔结论〕").contains("〔未决〕").endsWith(truncated);
    }

    @Test
    @DisplayName("编造一个不存在的规则编码：整段丢弃，退回截断版")
    void 编造编码被拒() {
        String truncated = "〔事实〕jobId=12";
        String model = "〔结论〕\n- 同时跑了 ORDER_STATUS_RECON";
        assertThat(SummaryEnricher.compose(truncated, model, TURNS)).isEqualTo(truncated);
    }

    @Test
    @DisplayName("编造一个不存在的任务号：同样退货")
    void 编造任务号被拒() {
        String truncated = "〔事实〕jobId=12";
        String model = "〔结论〕\n- 任务 #99 也失败了";
        assertThat(SummaryEnricher.compose(truncated, model, TURNS)).isEqualTo(truncated);
    }

    @Test
    @DisplayName("校验器本身：只认材料里出现过的 id")
    void 校验器() {
        assertThat(SummaryEnricher.guard("BILL_VS_SETTLE 和 #12 都在", TURNS)).isTrue();
        assertThat(SummaryEnricher.guard("NO_SUCH_RULE", TURNS)).isFalse();
        assertThat(SummaryEnricher.guard("#7 不在材料里", TURNS)).isFalse();
    }

    @Test
    @DisplayName("事实行由代码生成：规则编码和任务号不会因为压缩而丢")
    void 事实行() {
        String line = SummaryEnricher.factsLine(TURNS);
        assertThat(line).startsWith("〔事实〕").contains("BILL_VS_SETTLE").contains("#12");
    }
}
