package io.github.dolow.reconlens.chat;

import java.util.List;

/**
 * 一个动作的**表单描述**：没有 key、没有宿主时，界面靠它把按钮和输入框画出来。
 *
 * <p>为什么表单要和会话共用一份动作表：如果不共用，就会长出一个"聊天能做的事"和一个
 * "表单能做的事"两张清单，半年后没人说得清哪边少了哪一项。这里 {@code action} 就是
 * {@link ChatIntent}，字段名就是 {@link ChatIntentRouter.Call} 里的参数名。
 *
 * <p>字段全部标 {@code required=false} 的多半是"可以省略、省略时用会话槽位"——例如
 * {@code jobId}：上一句刚跑完，这一句就不用再报任务号。必填的只有那些槽位也救不了的
 * （规则正文）。
 */
public record ChatForm(ChatIntent action, String label, String summary, List<Field> fields) {

    /** 一个输入项。{@code type} 只有 text / textarea / number，够用且实现端不用猜。 */
    public record Field(String name, String label, String type, boolean required, String example, String hint) {

        static Field text(String name, String label, String example) {
            return new Field(name, label, "text", false, example, null);
        }

        static Field number(String name, String label, String hint) {
            return new Field(name, label, "number", false, "123", hint);
        }

        /** 单行必填文本：像表名这种"必须给、但只有一行"的输入。 */
    static Field requiredText(String name, String label, String example, String hint) {
        return new Field(name, label, "text", true, example, hint);
    }

    static Field requiredTextArea(String name, String label, String example) {
            return new Field(name, label, "textarea", true, example, null);
        }
    }

    boolean hasFields() {
        return !fields.isEmpty();
    }
}
