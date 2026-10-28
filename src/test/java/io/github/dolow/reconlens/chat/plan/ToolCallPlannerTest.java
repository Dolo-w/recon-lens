package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具协议的解析与提示词。
 *
 * <p>钉的四件事：模型点了真工具要认；点了不存在的工具要拒收（不能让它一路执行到空指针）；
 * 不是 JSON 要退回老路径（工具能力只能加不能减）；reply 形态要能读出原话。
 */
class ToolCallPlannerTest {

    private final ObjectMapper json = new ObjectMapper();

    private static ToolSpec spec(String name, String description, String inputSchema) {
        return new ToolSpec(name, description, inputSchema, null, args -> ToolOutcome.text("ok"));
    }

    private static ToolRegistry registry(ToolSpec... specs) {
        List<ToolSpec> list = List.of(specs);
        return new ToolRegistry() {
            @Override
            public List<ToolSpec> specs() {
                return list;
            }

            @Override
            public Optional<ToolSpec> find(String name) {
                return list.stream().filter(spec -> spec.name().equals(name)).findFirst();
            }
        };
    }

    private ToolCallPlanner planner() {
        return new ToolCallPlanner(registry(
                spec("recon_list_diffs", "列出某个任务的对账差异明细。第二个句子不该进提示词。",
                        "{\"type\":\"object\",\"properties\":{\"jobId\":{\"type\":\"integer\"}}}"),
                spec("recon_run", "跑一次对账。写操作。",
                        "{\"type\":\"object\",\"properties\":{\"ruleCode\":{\"type\":\"string\"}}}")), json);
    }

    @Test
    @DisplayName("模型点真工具：认出名字与参数，数字参数转成字符串不丢")
    void parsesToolCall() {
        Optional<ToolCallPlanner.Decision> decision = planner().parse(
                "{\"action\":\"tool\",\"name\":\"recon_list_diffs\",\"args\":{\"jobId\":12}}");

        assertThat(decision).isPresent();
        assertThat(decision.get().isTool()).isTrue();
        assertThat(decision.get().name()).isEqualTo("recon_list_diffs");
        assertThat(decision.get().args()).containsEntry("jobId", "12");
    }

    @Test
    @DisplayName("action 直接写工具名也认（模型很常这么给）")
    void parsesToolNameAsAction() {
        Optional<ToolCallPlanner.Decision> decision = planner().parse(
                "{\"action\":\"recon_run\",\"args\":{\"ruleCode\":\"BILL_VS_SETTLE\"}}");

        assertThat(decision).isPresent();
        assertThat(decision.get().name()).isEqualTo("recon_run");
        assertThat(decision.get().args()).containsEntry("ruleCode", "BILL_VS_SETTLE");
    }

    @Test
    @DisplayName("点了不存在的工具：拒收，交回老路径，不抛")
    void rejectsUnknownTool() {
        assertThat(planner().parse(
                "{\"action\":\"tool\",\"name\":\"recon_delete_everything\",\"args\":{}}")).isEmpty();
        assertThat(planner().parse("{\"action\":\"tool\",\"args\":{}}")).isEmpty();
    }

    @Test
    @DisplayName("不是 JSON / 不是对象：退回空，工具能力不能把原来能听懂的话变听不懂")
    void rejectsNonJson() {
        assertThat(planner().parse("抱歉，我需要先确认一下。")).isEmpty();
        assertThat(planner().parse("[1,2,3]")).isEmpty();
        assertThat(planner().parse("  ")).isEmpty();
        String nothing = null;
        assertThat(planner().parse(nothing)).isEmpty();
    }

    @Test
    @DisplayName("reply 形态：读出模型的原话，不做任何执行")
    void parsesReply() {
        Optional<ToolCallPlanner.Decision> decision = planner().parse(
                "{\"action\":\"reply\",\"text\":\"你说的这几张表，是规则里左边和右边那两张吗？\"}");

        assertThat(decision).isPresent();
        assertThat(decision.get().isReply()).isTrue();
        assertThat(decision.get().isTool()).isFalse();
        assertThat(decision.get().reply()).contains("左边和右边那两张");
    }

    @Test
    @DisplayName("done 形态：结束这一轮")
    void parsesDone() {
        Optional<ToolCallPlanner.Decision> decision = planner().parse("{\"action\":\"done\"}");

        assertThat(decision).isPresent();
        assertThat(decision.get().isDone()).isTrue();
    }

    @Test
    @DisplayName("提示词：每个工具一行，参数名从 inputSchema 读，描述只取第一句")
    void rendersSectionFromSchema() {
        String section = planner().section();

        assertThat(section).contains("recon_list_diffs（args: jobId）").contains("recon_run（args: ruleCode）");
        assertThat(section).contains("\"action\":\"tool\"").contains("\"action\":\"reply\"");
        assertThat(section).doesNotContain("第二个句子不该进提示词");
    }

    @Test
    @DisplayName("没有工具时可关闭：不出现工具段，也不算认得出工具")
    void disabledWithoutTools() {
        ToolCallPlanner none = ToolCallPlanner.none(json);

        assertThat(none.enabled()).isFalse();
        assertThat(none.section()).isEmpty();
        assertThat(none.parse("{\"action\":\"recon_run\",\"args\":{}}")).isEmpty();
    }

    @Test
    @DisplayName("工具名反推意图：recon_run → RUN（写），list_tables → LIST_DATA（别名）")
    void mapsToolToIntent() {
        assertThat(ToolCallPlanner.intentFor("recon_run").name()).isEqualTo("RUN");
        assertThat(ToolCallPlanner.intentFor("recon_run").isWrite()).isTrue();
        assertThat(ToolCallPlanner.intentFor("recon_list_tables").name()).isEqualTo("LIST_DATA");
        assertThat(ToolCallPlanner.intentFor("recon_whatever").name()).isEqualTo("HELP");
    }

    @Test
    @DisplayName("工具参数还原成 Call：回执与写确认读同一个对象")
    void buildsCallFromArgs() {
        var call = ToolCallPlanner.callFor("recon_list_diffs", Map.of("jobId", "12", "diffId", "7"));

        assertThat(call.intent().name()).isEqualTo("LIST_DIFFS");
        assertThat(call.jobId()).isEqualTo(12L);
        assertThat(call.diffId()).isEqualTo(7L);
    }
}
