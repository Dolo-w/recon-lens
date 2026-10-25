package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规则起名的边界。
 *
 * <p>这里不测"名字起得好不好"（那是模型的事），只测**代码必须守住的几条**：
 * 没配模型时不能挡住起草、非法字符必须清洗、重名必须加序号。
 * 名字是给人认的，但它同时是落库主键的一部分——撞名比难听严重得多。
 */
class RuleNamerTest {

    private final ObjectMapper json = new ObjectMapper();

    private RuleNamer unconfigured() {
        return new RuleNamer(new LlmChatClient("", "", "deepseek-chat", 1000, json), json);
    }

    @Test
    @DisplayName("没配模型：不给建议，起草照常走 AUTO_ 方案")
    void 没配模型不挡路() {
        RuleNamer namer = unconfigured();
        assertThat(namer.configured()).isFalse();
        assertThat(namer.propose("按订单和状态对账", "bill_left", "bill_right", "order_no", "amount")).isEmpty();
    }

    @Test
    @DisplayName("非法字符清洗成合法标识符，且必须以字母开头")
    void 清洗() {
        assertThat(RuleNamer.sanitize("order status recon")).isEqualTo("ORDER_STATUS_RECON");
        // 全中文清洗完什么都不剩：退回一个中性名字，而不是留个空串去撞落库校验
        assertThat(RuleNamer.sanitize("订单-状态")).isEqualTo("SESSION_RECON");
        assertThat(RuleNamer.sanitize("")).isEqualTo("SESSION_RECON");
        assertThat(RuleNamer.sanitize("9lives")).startsWith("R_");
    }

    @Test
    @DisplayName("重名加序号，不覆盖别人")
    void 重名加序号() {
        assertThat(RuleNamer.unique("ORDER_STATUS_RECON", List.of())).isEqualTo("ORDER_STATUS_RECON");
        assertThat(RuleNamer.unique("ORDER_STATUS_RECON", List.of("ORDER_STATUS_RECON")))
                .isEqualTo("ORDER_STATUS_RECON_2");
        assertThat(RuleNamer.unique("ORDER_STATUS_RECON",
                List.of("ORDER_STATUS_RECON", "ORDER_STATUS_RECON_2")))
                .isEqualTo("ORDER_STATUS_RECON_3");
        // 大小写不同也算撞名：库里的编码大小写不敏感
        assertThat(RuleNamer.unique("order_status_recon", List.of("ORDER_STATUS_RECON")))
                .isEqualTo("ORDER_STATUS_RECON_2");
    }

    @Test
    @DisplayName("「第N条」的序号解析：中文数字和阿拉伯数字都认")
    void 序号解析() {
        assertThat(ChatRuntime.draftIndex("第二条的试算通过了吗")).isEqualTo(2);
        assertThat(ChatRuntime.draftIndex("第 3 条改一下")).isEqualTo(3);
        assertThat(ChatRuntime.draftIndex("第十二条呢")).isEqualTo(12);
        assertThat(ChatRuntime.draftIndex("这条规则呢")).isNegative();
    }

    @Test
    @DisplayName("原话里的中文业务词要留下来，否则「那条订单对状态的」永远对不上")
    void 原话业务词() {
        assertThat(DraftIntake.spokenWords("按订单和状态对账，比金额")).contains("订单").contains("金额");
        // 纯英文列名不算"业务词"，存了也没用
        assertThat(DraftIntake.spokenWords("比 amount")).isEmpty();
    }

    @Test
    @DisplayName("描述匹配：中文二元组能打上分，虚词不算分")
    void 描述匹配打分() {
        String label = "bill_left→bill_right，维度 order_no，比 amount，用户说：订单和状态";

        assertThat(ChatRuntime.matchScore("那条按订单和状态对账的", label)).isGreaterThanOrEqualTo(2);
        assertThat(ChatRuntime.matchScore("随便看看这个", label)).isZero();
        // 表名权重更高
        assertThat(ChatRuntime.matchScore("bill_left 那条", label)).isGreaterThanOrEqualTo(2);
    }
}
