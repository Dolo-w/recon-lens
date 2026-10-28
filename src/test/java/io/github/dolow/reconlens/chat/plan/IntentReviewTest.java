package io.github.dolow.reconlens.chat.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dolow.reconlens.chat.ChatIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事实复核层的行为：只拦反问句，不动正常指令。
 *
 * <p>用例的选择标准：每条都要对应一个真实踩过的坑，或者一个明确不能误伤的句式。
 */
@DisplayName("意图复核：反问句要先问回去，正常指令不能误伤")
class IntentReviewTest {

    @Test
    @DisplayName("「不是三个维度吗」是质疑，不该被当成建表/起草指令去执行")
    void rhetoricalQuestionIsNotAnInstruction() {
        String question = IntentReview.question("刚刚我说的不是三个维度吗", ChatIntent.DEFINE_RULE);

        assertThat(question).isNotNull();
        assertThat(question).contains("是在问").contains("定义规则");
        assertThat(question).contains("我先不动手");
    }

    @Test
    @DisplayName("「难道已经批过了？」同样要问回去，不能触发审批")
    void d难道IsAlsoRhetorical() {
        assertThat(IntentReview.question("难道已经批过了？", ChatIntent.APPROVE)).isNotNull();
    }

    @Test
    @DisplayName("「为什么差这么多」是正常归因指令，必须放行")
    void plainQuestionWithoutNegationIsAnInstruction() {
        assertThat(IntentReview.question("为什么差这么多", ChatIntent.EXPLAIN_JOB)).isNull();
    }

    @Test
    @DisplayName("「能批吗」是要走审批流程的提问，放行给审批处理器自己回答")
    void permissionQuestionIsHandledByItsIntent() {
        assertThat(IntentReview.question("能批吗", ChatIntent.APPROVE)).isNull();
    }

    @Test
    @DisplayName("普通指令与空消息都要放行")
    void ordinaryInstructionAndEmptyMessagePass() {
        assertThat(IntentReview.question("跑一下 BILL_VS_SETTLE", ChatIntent.RUN)).isNull();
        assertThat(IntentReview.question(null, ChatIntent.RUN)).isNull();
        assertThat(IntentReview.question("   ", ChatIntent.RUN)).isNull();
    }
}
