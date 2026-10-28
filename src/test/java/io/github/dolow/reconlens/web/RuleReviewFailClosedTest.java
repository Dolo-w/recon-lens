package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.rule.RuleTrialRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 没配 {@code recon.review.api-key} 时，审批接口<b>一律拒绝</b>。
 *
 * <p>这是 fail-closed 的验收：安全开关的默认值必须是"关闭"，而不是"打开但不检查"。
 * 忘了配密钥的后果应该是"审批走不通"，不是"谁都能发布规则"。
 *
 * <p>用单元测试而不是 {@code @SpringBootTest}：这条规则与 Spring 装配无关，
 * 而多起一个上下文会往同一个内存库里再灌一遍 data.sql（这也正是它一开始被写成集成测试时踩到的坑）。
 */
class RuleReviewFailClosedTest {

    /** 试算实现随 agent 装配；这里只关心"没密钥就拒绝"，不需要它存在。 */
    private static ObjectProvider<RuleTrialRunner> noRunner() {
        return new StaticListableBeanFactory().getBeanProvider(RuleTrialRunner.class);
    }

    @Test
    void 没配密钥时审批接口关闭() {
        ReconProperties properties = new ReconProperties();
        RuleReviewController controller = new RuleReviewController(null, properties, noRunner());

        assertThat(properties.getReview().getApiKey()).isEmpty();
        assertThatThrownBy(() -> controller.review("ANY_RULE", 1, "whatever"))
                .isInstanceOf(RuleReviewController.ReviewForbiddenException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void 配了密钥也要比对一致() {
        ReconProperties properties = new ReconProperties();
        properties.getReview().setApiKey("s3cret");
        RuleReviewController controller = new RuleReviewController(null, properties, noRunner());

        assertThatThrownBy(() -> controller.review("ANY_RULE", 1, "wrong"))
                .isInstanceOf(RuleReviewController.ReviewForbiddenException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void 被拒时给出可照做的下一步_而不是只报一个错误码() {
        ReconProperties properties = new ReconProperties();
        properties.getReview().setApiKey("s3cret");
        RuleReviewController controller = new RuleReviewController(null, properties, noRunner());

        RuleReviewController.ReviewForbiddenException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> controller.review("ANY_RULE", 1, "wrong"),
                RuleReviewController.ReviewForbiddenException.class);

        assertThat(e.hint()).contains("--recon.review.api-key");
    }
}
