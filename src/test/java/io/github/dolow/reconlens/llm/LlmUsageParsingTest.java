package io.github.dolow.reconlens.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 token 用量的解析：{@code usage.total_tokens} 优先，其次分项相加，都没有返回 0。
 *
 * <p>为什么值得单测：这个值直接决定 Agent 预算账本（{@code maxLlmTokensPerRun}）。
 * 解析错了不会报错，只会让预算"看起来没用完"或"提前炸掉" —— 属于沉默错误。
 */
class LlmUsageParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private int usageOf(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        return OpenAiCompatibleChatClient.usageOf(root);
    }

    @Test
    @DisplayName("标准 OpenAI 响应：取 usage.total_tokens")
    void usesTotalTokens() throws Exception {
        assertThat(usageOf("""
                {"choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":812,"completion_tokens":188,"total_tokens":1000}}
                """)).isEqualTo(1000);
    }

    @Test
    @DisplayName("只有分项：相加")
    void sumsPromptAndCompletionWhenTotalIsMissing() throws Exception {
        assertThat(usageOf("""
                {"usage":{"prompt_tokens":300,"completion_tokens":45}}
                """)).isEqualTo(345);
    }

    @Test
    @DisplayName("没有 usage（网关裁剪/非标准实现）：返回 0，调用方退回估算")
    void returnsZeroWhenUsageIsAbsent() throws Exception {
        assertThat(usageOf("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}")).isZero();
    }
}
