package io.github.dolow.reconlens.llm;

import io.github.dolow.reconlens.config.ReconProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 客户端的契约：<b>永不抛异常</b>，拿不到就是 {@code empty}。
 *
 * <p>这条约束看起来"太宽松"，但它保护的正是更重要的一半：对账结论不能因为一次 API 抖动而失败。
 * 反过来说，如果哪天有人在 {@code complete} 里加了一句 {@code throw}，调用方的兜底就会失效 ——
 * 所以这里连"未配置时调用"这种明显不联网的路径也要断言一遍"不抛"。
 */
class OpenAiCompatibleChatClientTest {

    @Test
    void notConfiguredMeansUnavailableAndEmpty() {
        ReconProperties properties = new ReconProperties();
        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(properties);

        assertThat(client.available()).isFalse();
        assertThat(client.complete("system", "user")).isEmpty();
    }

    @Test
    void enabledWithoutApiKeyIsStillUnavailable() {
        ReconProperties properties = new ReconProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setApiKey("");
        // 环境变量在 CI 里也可能存在，所以只断言"没有 key 时不可用"这一条：
        // resolveApiKey 会读 RECON_LLM_API_KEY，若它被设置则属于预期内的"可用"
        if (properties.getLlm().resolveApiKey().isEmpty()) {
            assertThat(new OpenAiCompatibleChatClient(properties).available()).isFalse();
        }
    }

    /** 指向一个必然连不上的地址：不允许抛，只允许 empty。 */
    @Test
    void unreachableEndpointReturnsEmptyInsteadOfThrowing() {
        ReconProperties properties = new ReconProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setApiKey("test-key");
        properties.getLlm().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getLlm().setTimeoutSeconds(1);

        OpenAiCompatibleChatClient client = new OpenAiCompatibleChatClient(properties);

        assertThatCode(() -> assertThat(client.complete("system", "user")).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void nameIdentifiesTheWireProtocolNotTheVendor() {
        // 叫 openai-compatible 而不是 gpt / deepseek：换厂商不该让报告里的"解释器"跟着变
        assertThat(new OpenAiCompatibleChatClient(new ReconProperties()).name())
                .isEqualTo("llm-openai-compatible");
    }
}
