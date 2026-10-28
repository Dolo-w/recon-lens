package io.github.dolow.reconlens.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 手册是装进提示词的"世界知识"，装载失败不能静默——静默的后果是模型开始瞎猜列名。 */
class ChatHandbookTest {

    @Test
    void 手册能从classpath读到_并且讲清楚了三条硬约束() {
        String text = new ChatHandbook().text();

        assertThat(text).isNotEqualTo(ChatHandbook.FALLBACK);
        assertThat(text).contains("三条硬约束").contains("不许编列名").contains("recon_describe_table");
        // 手册是给人话用的：内部注册名可以出现在"用哪些工具"里，但整体不该是 JSON 规范
        assertThat(text).doesNotContain("\"action\":\"tool\"");
    }
}
