package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 同一件事的不同说法，落到同一个意图。
 *
 * <p>为什么单独一组用例：路由是顺序匹配，"不同说法"以前靠一条条往词表里加，
 * 加错一处就会被前面某档抢走（"数据有多少行"被答成"看哪个任务"就是这么来的）。
 * 这里按"用户怎么说"列一长串口语，固定住它们该落到哪一档 —— 之后再加词，
 * 先在这里加一行，跑不过就说明撞了别的档。
 */
@DisplayName("同义说法归一到同一意图")
class ChatSynonymTest {

    private final ChatIntentRouter router = new ChatIntentRouter();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            // 看差异：差异这件事，用户会用什么词
            "有对不上的吗 | LIST_DIFFS",
            "两边对不平的是哪些 | LIST_DIFFS",
            "差异清单给我 | LIST_DIFFS",
            "哪些单子有问题 | LIST_DIFFS",
            "帮我把差额找出来 | LIST_DIFFS",
            "挂账的有几笔 | LIST_DIFFS",
            // 归因：问原因优先于看明细
            "为什么对不上 | EXPLAIN_JOB",
            "这些差错咋回事 | EXPLAIN_JOB",
            "什么原因导致的 | EXPLAIN_JOB",
            // 任务总览
            "跑完了吗 | JOB_SUMMARY",
            "跑得怎么样 | JOB_SUMMARY",
            "跑到哪了 | JOB_SUMMARY",
            "扫了多少行 | JOB_SUMMARY",
            "怎么扫了这么多 | JOB_SUMMARY",
            // 跑一次
            "跑一下 | RUN",
            "帮我跑一遍账单对账 | RUN",
            "比对一下 BILL_VS_SETTLE | RUN",
            // 重跑
            "再跑一遍 | RETRY",
            "把失败的分片重来一次 | RETRY",
            "从失败处继续 | RETRY",
            // 试算
            "先预演一下这笔规则 | TRIAL_STATUS",
            "沙箱里跑过没有 | TRIAL_STATUS",
            // 审批
            "这条规则能不能过 | APPROVE",
            "给不给过 | APPROVE",
            "可以上线吗 | APPROVE",
            // 看字段
            "这张表里都有啥 | DESCRIBE_TABLE",
            "bill_left 有哪些属性 | DESCRIBE_TABLE",
            "表里放了啥 | DESCRIBE_TABLE",
            // 看数据源
            "都有什么表 | LIST_DATA",
            "数据从哪来 | LIST_DATA",
            "有多少张表 | LIST_DATA",
            // 行数（主语是表）
            "数据有多少笔 | DESCRIBE_TABLE",
            "多少条记录 | DESCRIBE_TABLE",
            // 规则
            "有哪些规则 | LIST_RULES",
            "给我规则列表 | LIST_RULES",
            // 帮助
            "你会啥 | HELP",
            "能干嘛 | HELP",
            // 没有名词的疑问词，不猜
            "有哪些 | HELP",
            "看看 | HELP"
    })
    void 同一意图的不同说法(String message, ChatIntent expected) {
        assertThat(router.route(message).intent()).isEqualTo(expected);
    }

    @Test
    @DisplayName("归一化不能改参数_任务号和差异号必须来自原文")
    void 归一化不动参数() {
        // "第 3 条" 不能因为换了词变成别的号码；任务号同理
        ChatIntentRouter.Call c = router.route("第 3 条差异为什么对不上");
        assertThat(c.intent()).isEqualTo(ChatIntent.EXPLAIN_DIFF);
        assertThat(c.diffId()).isEqualTo(3L);
        assertThat(router.route("任务 12 跑完了吗").jobId()).isEqualTo(12L);
    }
}
