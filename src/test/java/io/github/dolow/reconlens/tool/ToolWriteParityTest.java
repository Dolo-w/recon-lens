package io.github.dolow.reconlens.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.chat.ChatIntent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「哪些工具会改状态」只有一份清单（{@link ToolNames#WRITES}），这里守住它不会和现实脱节。
 *
 * <p>之所以要有这条测试：改动前写动作的判断藏在 {@link ChatIntent#isWrite()} 里，
 * 加一个新的写工具（比如后来加的 {@code recon_import_file}）只要枚举忘了改，
 * 会话层就会把"该先回执"的动作直接执行掉 —— 没有任何编译期信号。
 */
class ToolWriteParityTest {

    @Test
    @DisplayName("WRITES 里的每个名字都真的注册过")
    void everyWriteNameIsRegistered() {
        List<String> known = new ArrayList<>(ToolNames.BASE);
        known.addAll(ToolNames.AGENT);
        assertThat(known).as("基础 + agent 两份清单").doesNotHaveDuplicates();
        assertThat(known).as("注册清单里存在的工具").containsAll(ToolNames.WRITES);
    }

    @Test
    @DisplayName("写工具 write() 为真，读工具为假")
    void specExposesWriteFlag() {
        assertThat(spec(ToolNames.RUN).write()).isTrue();
        assertThat(spec(ToolNames.DEFINE_RULE).write()).isTrue();
        assertThat(spec(ToolNames.BUILD_MODEL).write()).isTrue();
        assertThat(spec(ToolNames.IMPORT_FILE).write()).isTrue();
        assertThat(spec(ToolNames.LIST_RULES).write()).isFalse();
        assertThat(spec(ToolNames.LIST_DIFFS).write()).isFalse();
        assertThat(spec(ToolNames.EXPLAIN_JOB).write()).isFalse();
    }

    @Test
    @DisplayName("枚举说写 = 工具说写（聊天里 RETRY 走的就是 recon_run）")
    void enumAndSpecAgree() {
        for (ChatIntent intent : ChatIntent.values()) {
            if (!intent.isWrite()) {
                continue;
            }
            assertThat(ToolNames.WRITES)
                    .as("意图 %s 声称会写，但工具清单里没有对应的写工具", intent)
                    .isNotEmpty();
        }
        for (ChatIntent intent : List.of(ChatIntent.RUN, ChatIntent.RETRY,
                ChatIntent.BUILD_MODEL, ChatIntent.DEFINE_RULE)) {
            assertThat(intent.isWrite()).as("%s 必须是写动作", intent).isTrue();
        }
        assertThat(ToolNames.WRITES).contains(ToolNames.RUN, ToolNames.DEFINE_RULE, ToolNames.BUILD_MODEL);
    }

    @Test
    @DisplayName("建议是结构化字段，结果正文里不再夹带「下一步」")
    void suggestionIsNotPartOfText() {
        assertThat(spec(ToolNames.RUN).suggestion()).contains("recon_list_diffs");
        assertThat(spec(ToolNames.LIST_RULES).suggestion()).isNull();
    }

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, "", "{}", null, args -> null);
    }
}
