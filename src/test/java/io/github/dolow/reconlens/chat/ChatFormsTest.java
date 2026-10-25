package io.github.dolow.reconlens.chat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表单目录的完整性。
 *
 * <p>这类"清单型"代码最容易出的事故是**加了一个动作、忘了加表单**——用户在那个动作上永远
 * 只有一个「未知动作」的报错，而接口文档里写着它支持。所以这里钉住的是双向覆盖：
 * 每个 {@link ChatIntent} 都有表单，每个表单都指向真实存在的动作。
 */
class ChatFormsTest {

    @Test
    void 每个动作都有表单_没有漏的也没有多的() {
        Set<ChatIntent> covered = new HashSet<>();
        for (ChatForm form : ChatForms.all()) {
            assertThat(covered.add(form.action()))
                    .as("动作 %s 出现了两次表单", form.action())
                    .isTrue();
        }
        assertThat(covered).containsExactlyInAnyOrder(ChatIntent.values());
    }

    @Test
    void 每个表单都填了给人看的字且字段名不重复() {
        for (ChatForm form : ChatForms.all()) {
            assertThat(form.label()).as("%s 缺 label", form.action()).isNotBlank();
            assertThat(form.summary()).as("%s 缺 summary", form.action()).isNotBlank();
            Set<String> names = new HashSet<>();
            for (ChatForm.Field field : form.fields()) {
                assertThat(names.add(field.name()))
                        .as("%s 的字段 %s 重复", form.action(), field.name())
                        .isTrue();
                assertThat(field.label()).isNotBlank();
                assertThat(field.type()).isIn("text", "number", "textarea");
            }
        }
    }

    @Test
    void 字段名必须能落到路由结果的组件上_否则填了也不生效() {
        // 与 ChatIntentRouter.Call 的组件同名是这份目录的约定（少一层映射少一处写错的机会）。
        // 这里用白名单钉住：新增字段时必须同时改 Call，否则表单会静默丢值。
        Set<String> known = Set.of("ruleCode", "jobId", "diffId", "diffType", "ruleBody", "table");
        for (ChatForm form : ChatForms.all()) {
            for (ChatForm.Field field : form.fields()) {
                assertThat(known).as("%s 的字段 %s 不在 Call 上", form.action(), field.name())
                        .contains(field.name());
            }
        }
    }

    @Test
    void 只有起草是必填_其余动作允许靠会话槽位替你填() {
        List<ChatForm> withRequired = ChatForms.all().stream()
                .filter(form -> form.fields().stream().anyMatch(ChatForm.Field::required))
                .toList();
        assertThat(withRequired).extracting(ChatForm::action)
                .containsExactlyInAnyOrder(ChatIntent.DEFINE_RULE, ChatIntent.DESCRIBE_TABLE);
    }

    @Test
    void 动作名按大小写不敏感查找_前端写成run也能用() {
        assertThat(ChatForms.of("run").map(ChatForm::action)).contains(ChatIntent.RUN);
        assertThat(ChatForms.of("Run").map(ChatForm::action)).contains(ChatIntent.RUN);
        assertThat(ChatForms.of("NO_SUCH_ACTION")).isEmpty();
        assertThat(ChatForms.of(null)).isEmpty();
        assertThat(ChatForms.of("   ")).isEmpty();
    }

    @Test
    void 建模型没有分片输入框_因为路由结果里没有这个组件() {
        ChatForm form = ChatForms.of("BUILD_MODEL").orElseThrow();
        assertThat(form.fields()).extracting(ChatForm.Field::name).doesNotContain("shards");
    }}
