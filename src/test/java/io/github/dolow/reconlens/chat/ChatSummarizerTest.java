package io.github.dolow.reconlens.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要是个纯函数，所以这里不需要数据库、不需要 Spring（ADR-0001 D2）。
 *
 * <p>纯函数的好处就在这几行测试里：同样的轮次压一百次都是一个字，出问题能对拍。
 * 如果摘要调了模型，这里就只能断言"不为空"，那种测试挡不住任何回归。
 */
class ChatSummarizerTest {

    private static ChatTurn turn(int seq, String role, String content) {
        return new ChatTurn(seq, 1L, seq, role, content, null, null, Instant.EPOCH);
    }

    @Test
    void 一次压缩把每一轮压成一行_并推进水位线() {
        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "跑一下 BILL_VS_SETTLE"),
                turn(2, "assistant", "任务 #12 · 规则 BILL_VS_SETTLE · 状态 SUCCESS\n分片 2/2 完成\n差异 4 条"),
                turn(3, "user", "为什么差这么多"),
                turn(4, "assistant", "任务 #12 已生成 4 条归因（解释器=rule-template）")));

        assertThat(result.uptoSeq()).isEqualTo(4);
        assertThat(result.text())
                .contains("〔第 1–4 轮〕")
                .contains("问（1）：跑一下 BILL_VS_SETTLE")
                .contains("答（2）：任务 #12 · 规则 BILL_VS_SETTLE · 状态 SUCCESS")
                .contains("问（3）：为什么差这么多");
    }

    @Test
    void 第二次压缩接着上一次的摘要写_不重复前面的轮次() {
        ChatSummarizer.Result first = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "第一问"), turn(2, "assistant", "第一答")));
        ChatSummarizer.Result second = ChatSummarizer.summarize(first.text(), first.uptoSeq(), List.of(
                turn(1, "user", "第一问"),
                turn(2, "assistant", "第一答"),
                turn(3, "user", "第二问"),
                turn(4, "assistant", "第二答")));

        assertThat(second.uptoSeq()).isEqualTo(4);
        assertThat(second.text()).contains("第一问").contains("第二问");
        // 第一段只出现一次：重复压会让摘要越滚越长，最后比原文还长
        assertThat(second.text().split("第一问", -1)).hasSize(2);
    }

    @Test
    void 只有新轮次时才推进水位_没新东西就原地不动() {
        ChatSummarizer.Result result = ChatSummarizer.summarize("旧摘要", 7, List.of(
                turn(3, "user", "老轮次"), turn(5, "assistant", "老回答")));

        assertThat(result.uptoSeq()).isEqualTo(7);
        assertThat(result.text()).isEqualTo("旧摘要");
    }

    @Test
    void 轮次太多时砍中间_保住开头和最近() {
        List<ChatTurn> turns = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            turns.add(turn(i, i % 2 == 1 ? "user" : "assistant", "第 " + i + " 轮"));
        }

        String text = ChatSummarizer.summarize(null, 0, turns).text();

        assertThat(text).contains("第 1 轮").contains("第 30 轮").contains("略去");
        assertThat(text).doesNotContain("第 15 轮");
    }

    @Test
    void 摘要总长度有上限_不会越压越长() {
        String longAnswer = "差".repeat(400) + "\n第二行";
        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "assistant", longAnswer), turn(2, "assistant", longAnswer)));

        assertThat(result.text().length()).isLessThanOrEqualTo(ChatSummarizer.MAX_SUMMARY_CHARS + 40);
    }

    @Test
    void 工具轮不单独占一行_它的产出已经在助手轮里() {
        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "看差异"), turn(2, "tool", "recon_list_diffs 返回 4 条"),
                turn(3, "assistant", "共 4 条差异")));

        assertThat(result.text()).doesNotContain("recon_list_diffs");
        assertThat(result.uptoSeq()).isEqualTo(3);
    }

    @Test
    void token估算_中文按字算_英文按四字符一个() {
        assertThat(ChatSummarizer.estimateTokens("")).isZero();
        assertThat(ChatSummarizer.estimateTokens("对账")).isEqualTo(2);
        assertThat(ChatSummarizer.estimateTokens("abcdefgh")).isEqualTo(2);
        assertThat(ChatSummarizer.estimateTokens("对账 12")).isEqualTo(3);
    }

    @Test
    void 砍掉的中间段留下坐标和关键词_不是一句干巴巴的省略() {
        List<ChatTurn> turns = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            turns.add(turn(i, i % 2 == 1 ? "user" : "assistant", "第 " + i + " 轮"));
        }
        // 中段才出现的两个实体：它们既不在开头 4 行里，也不在结尾 10 行里
        turns.set(19, turn(20, "user", "顺便看一眼 CUSTOMER_SUM_RECON 的任务 #7"));
        turns.set(21, turn(22, "assistant", "那个任务的差异类型是 MISSING_LEFT"));

        String text = ChatSummarizer.summarize(null, 0, turns).text();

        String marker = text.lines().filter(l -> l.startsWith("…（略去")).findFirst().orElseThrow();
        assertThat(marker)
                .contains("第 4–30 轮")          // 被砍掉那段的坐标，翻回去有方向
                .contains("CUSTOMER_SUM_RECON")  // 中段出现的规则名
                .contains("MISSING_LEFT")        // 中段出现的差异类型
                .contains("#7")                  // 中段提到的任务号
                .contains("完整原文见轮次表");    // 明确指出原文没丢，只是没进摘要
    }

    @Test
    void 长会话的压缩比至少四比一_而且不丢首尾关键事实() {
        List<ChatTurn> turns = new java.util.ArrayList<>();
        int originalChars = 0;
        for (int i = 1; i <= 60; i++) {
            String content = (i % 2 == 1 ? "第 " + i + " 问：" : "第 " + i + " 答：")
                    + "这条差异的归因是时间差，账单侧存在而结算侧没有记录，建议按等键重跑本分片。".repeat(4);
            originalChars += content.length();
            turns.add(turn(i, i % 2 == 1 ? "user" : "assistant", content));
        }
        turns.set(0, turn(1, "user", "跑一下 BILL_VS_SETTLE"));
        turns.set(59, turn(60, "assistant", "结论：任务 #2 共 4 条差异，已全部归因"));

        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, turns);

        assertThat(result.text().length() * 4).isLessThan(originalChars);
        assertThat(result.text()).contains("BILL_VS_SETTLE").contains("已全部归因");
    }

    // -------------------------------------------------– ADR-0001 N3：被否决的方案要留在摘要里

    @Test
    void 被否决的一轮单独成段_并且带上坐标() {
        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "跑一下 SMOKE_RECON"),
                turn(2, "assistant", "这个动作没做成：规则 SMOKE_RECON 最新是 v1（DRAFT），还没生效。"
                        + "草稿要先看试算结论，再在控制台领确认码签字——会话里说「批准」不算。"),
                turn(3, "user", "那直接签发吧"),
                turn(4, "assistant", "会话不能批准规则。审批只认 POST /api/rule-reviews 发的一次性确认码。")));

        assertThat(result.text())
                .contains(ChatSummarizer.REJECT_MARK)
                .contains("问（1）→ 答（2）：这个动作没做成")
                .contains("问（3）→ 答（4）：会话不能批准规则");
    }

    @Test
    void 被否决段不会被中间裁剪吃掉_因为它在最后() {
        List<ChatTurn> turns = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            turns.add(turn(i, i % 2 == 1 ? "user" : "assistant", "第 " + i + " 轮：" + "内容".repeat(30)));
        }
        // 把"被否决"放在很前面，再让总行数远超裁剪阈值
        turns.set(3, turn(4, "assistant", "这个动作没做成：规则 A_RULE 还没生效"));

        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, turns);

        assertThat(result.text()).contains(ChatSummarizer.REJECT_MARK);
        assertThat(result.text()).contains("还没生效");
        assertThat(result.text()).contains("问（");
    }

    @Test
    void 二次压缩不会把旧的否决段再抄一遍() {
        ChatSummarizer.Result first = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "跑一下 A_RULE"),
                turn(2, "assistant", "这个动作没做成：规则 A_RULE 还没生效")));
        ChatSummarizer.Result second = ChatSummarizer.summarize(
                first.text(), first.uptoSeq(), List.of(
                        turn(3, "user", "看差异"),
                        turn(4, "assistant", "任务 #2 差异共 4 条")));

        long marks = second.text().lines().filter(l -> l.startsWith(ChatSummarizer.REJECT_MARK)).count();
        assertThat(marks).isEqualTo(1);
        // 注意：正文那行「答（2）」里也有"还没生效"，所以只数否决段自己的行
        long rejected = second.text().lines().filter(l -> l.startsWith("· 问（1）→ 答（2）")).count();
        assertThat(rejected).isEqualTo(1);
        assertThat(second.text()).contains("问（3）：看差异");
    }

    @Test
    void 否决段最多留三条_最近踩的坑优先() {
        List<ChatTurn> turns = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            turns.add(turn(i * 2 - 1, "user", "第 " + i + " 次尝试"));
            turns.add(turn(i * 2, "assistant", "失败：" + "原因" + i));
        }
        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, turns);

        String tail = result.text().substring(result.text().indexOf(ChatSummarizer.REJECT_MARK));
        long lines = tail.lines().filter(l -> l.startsWith("· ")).count();
        assertThat(lines).isEqualTo(3);
        assertThat(tail).contains("原因10").doesNotContain("原因1：");
    }

    @Test
    void 清单类回答保留到第三条第规则_普通回答仍然只留一句话() {
        String catalog = "规则库里有 3 条对账规则，调 recon_run 时把 code 当 ruleCode 传就行："
                + "BILL_VS_SETTLE 按客户加订单号逐笔比金额，"
                + "CUSTOMER_STATUS_RECON 按客户加状态汇总比金额和订单数，"
                + "CUSTOMER_SUM_RECON 按客户汇总比金额、允许 0.5% 偏差。"
                + "要改维度或比对字段，用 recon_define_rule 提交新版本。";
        String plain = "这是一段很长的普通解释，里面没有任何规则编码，"
                + "所以它应该和以前一样被截到六十四字，避免把一整段话搬进摘要里来。";

        ChatSummarizer.Result result = ChatSummarizer.summarize(null, 0, List.of(
                turn(1, "user", "有哪些规则"),
                turn(2, "assistant", catalog + "\n\n—— 明细 ——\n" + "明细行".repeat(50)),
                turn(3, "user", "为什么"),
                turn(4, "assistant", plain)));

        // 64 字会在第 2 条规则中间砍断，220 字能把三条规则的"比什么"都留在摘要里
        assertThat(result.text()).contains("CUSTOMER_SUM_RECON 按客户汇总比金额、允许 0.5% 偏差");
        String plainLine = result.text().lines()
                .filter(l -> l.startsWith("· 答（4）：")).findFirst().orElseThrow();
        assertThat(plainLine).hasSizeLessThanOrEqualTo(64 + "· 答（4）：".length());
    }
}
