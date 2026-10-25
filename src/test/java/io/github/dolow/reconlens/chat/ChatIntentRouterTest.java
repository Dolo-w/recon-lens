package io.github.dolow.reconlens.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由是确定性的，所以这些用例是"契约"而不是"跑一次看看"：
 * 每一行都是某个真实说法，改关键词表时这里会立刻报警。
 */
class ChatIntentRouterTest {

    private final ChatIntentRouter router = new ChatIntentRouter();

    @Test
    void 帮助与空消息都落到帮助() {
        assertThat(router.route("你能做什么").intent()).isEqualTo(ChatIntent.HELP);
        assertThat(router.route("  ").intent()).isEqualTo(ChatIntent.HELP);
        assertThat(router.route("随便说一句").intent()).isEqualTo(ChatIntent.HELP);
    }

    @Test
    void 列出规则() {
        assertThat(router.route("有哪些规则").intent()).isEqualTo(ChatIntent.LIST_RULES);
    }

    @Test
    void 起草类动词优先于名词_提到规则不代表要看规则目录() {
        assertThat(router.route("起草个规则：订单号和状态对账").intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(router.route("帮我新建一条对账规则").intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(router.route("我想加一条按订单号对的口径").intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(router.route("搞一条规则出来").intent()).isEqualTo(ChatIntent.DEFINE_RULE);
    }

    @Test
    void 新建新增出现在非起草句里_不抢意图() {
        // "新增"在这里修饰的是差异，不是"新增一条规则"——差一个同句对象词就换了一个动作
        assertThat(router.route("新增的差异有多少").intent()).isEqualTo(ChatIntent.LIST_DIFFS);
        assertThat(router.route("这单新增了哪些差异").intent()).isEqualTo(ChatIntent.LIST_DIFFS);
    }

    @Test
    void 跑一次会带出规则编码() {
        ChatIntentRouter.Call call = router.route("跑一下 BILL_VS_SETTLE");
        assertThat(call.intent()).isEqualTo(ChatIntent.RUN);
        assertThat(call.ruleCode()).isEqualTo("BILL_VS_SETTLE");
    }

    @Test
    void 任务号能从几种说法里抽出来() {
        assertThat(router.route("123 号任务怎么样").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
        assertThat(router.route("123 号任务怎么样").jobId()).isEqualTo(123L);
        assertThat(router.route("任务 45 的结果").jobId()).isEqualTo(45L);
    }

    @Test
    void 为什么优先于看差异() {
        // "为什么差这么多" 里有"差异"两个字，如果先判"看差异"就会答成明细列表
        assertThat(router.route("为什么差这么多").intent()).isEqualTo(ChatIntent.EXPLAIN_JOB);
        assertThat(router.route("看差异").intent()).isEqualTo(ChatIntent.LIST_DIFFS);
    }

    @Test
    void 单条差异归因能认出第几条() {
        ChatIntentRouter.Call call = router.route("第 137 条差异为什么");
        assertThat(call.intent()).isEqualTo(ChatIntent.EXPLAIN_DIFF);
        assertThat(call.diffId()).isEqualTo(137L);
    }

    @Test
    void 差异类型能被识别成枚举名() {
        assertThat(router.route("值不等的差异有哪些").diffType()).isEqualTo("VALUE_MISMATCH");
        assertThat(router.route("左边缺的").diffType()).isEqualTo("MISSING_LEFT");
        assertThat(router.route("重复的有多少").diffType()).isEqualTo("DUPLICATE_KEY");
    }

    @Test
    void 贴YAML就是定义规则并抽出正文() {
        ChatIntentRouter.Call call = router.route("帮我定义这条规则：\n"
                + "code: BILL_VS_SETTLE\nleft: {table: bill_left}\nright: {table: bill_right}\nkeys: [biz_no]");
        assertThat(call.intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(call.ruleBody()).contains("left:").contains("bill_right");
        assertThat(call.ruleCode()).isEqualTo("BILL_VS_SETTLE");
    }

    @Test
    void 代码块优先于其它关键词() {
        ChatIntentRouter.Call call = router.route("这个能跑吗\n```yaml\ncode: X_DEMO\nleft: {table: a}\nright: {table: b}\n```");
        assertThat(call.intent()).isEqualTo(ChatIntent.DEFINE_RULE);
        assertThat(call.ruleBody()).isEqualTo("code: X_DEMO\nleft: {table: a}\nright: {table: b}");
    }

    @Test
    void 审批只识别不执行_且不会误判成定义规则() {
        assertThat(router.route("我要批准这条规则").intent()).isEqualTo(ChatIntent.APPROVE);
        assertThat(router.route("这条规则试算通过了吗").intent()).isEqualTo(ChatIntent.TRIAL_STATUS);
    }

    @Test
    void 重跑建模型各自归位() {
        assertThat(router.route("重跑 123 号任务").intent()).isEqualTo(ChatIntent.RETRY);
        assertThat(router.route("重跑 123 号任务").jobId()).isEqualTo(123L);
        assertThat(router.route("给 BILL_VS_SETTLE 建模型").intent()).isEqualTo(ChatIntent.BUILD_MODEL);
        assertThat(router.route("给 BILL_VS_SETTLE 建模型").ruleCode()).isEqualTo("BILL_VS_SETTLE");
    }

    @Test
    void 常见缩写不会被当成规则编码() {
        ChatIntentRouter.Call call = router.route("跑一下，贴的是 YAML 不是表");
        assertThat(call.ruleCode()).isNull();
    }

    // ---- 下面 8 例来自 golden set 实测（2026-10-24）：一开始这些都路由错了 ----

    @Test
    void 问有哪些对账规则不能被当成跑一次() {
        assertThat(router.route("现在有哪些对账规则").intent()).isEqualTo(ChatIntent.LIST_RULES);
    }

    @Test
    void 对一次账也是跑一次() {
        assertThat(router.route("按 BILL_VS_SETTLE 对一次账").intent()).isEqualTo(ChatIntent.RUN);
    }

    @Test
    void 问跑完了吗是任务总览不是跑一次() {
        assertThat(router.route("这次对账跑完了吗").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
    }

    @Test
    void 问扫了多少行是任务总览() {
        assertThat(router.route("这单扫了多少行").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
    }

    @Test
    void 差异加数字是指某一条而不是整单() {
        ChatIntentRouter.Call call = router.route("差异 3 是什么原因");
        assertThat(call.intent()).isEqualTo(ChatIntent.EXPLAIN_DIFF);
        assertThat(call.diffId()).isEqualTo(3L);
    }

    @Test
    void 中文序数也能指出第几条差异() {
        ChatIntentRouter.Call call = router.route("第一条差异帮我看看");
        assertThat(call.intent()).isEqualTo(ChatIntent.EXPLAIN_DIFF);
        assertThat(call.diffId()).isEqualTo(1L);
    }

    @Test
    void 重新跑一遍是重跑() {
        assertThat(router.route("把失败的分片重新跑一遍").intent()).isEqualTo(ChatIntent.RETRY);
    }

    @Test
    void 问能不能批是审批不是列规则() {
        assertThat(router.route("规则能不能批").intent()).isEqualTo(ChatIntent.APPROVE);
    }

    @Test
    void 任务号不会被当成差异号() {
        ChatIntentRouter.Call call = router.route("任务 12 的差异看一下");
        assertThat(call.jobId()).isEqualTo(12L);
        assertThat(call.diffId()).isNull();
    }

    /**
     * 「有哪些数据」曾经被路由成「有哪些规则」——因为规则列表那一档拿裸的"有哪些"做关键词，
     * 于是用户问数据，屏幕上回的是一份和上一句一模一样的规则清单，看起来像"没反应"。
     * 疑问词不是话题词：判意图要看用户说的名词。
     */
    @Test
    void 问有哪些数据要答数据不是规则() {
        assertThat(router.route("有哪些数据").intent()).isEqualTo(ChatIntent.LIST_DATA);
        assertThat(router.route("库里有几张表").intent()).isEqualTo(ChatIntent.LIST_DATA);
        assertThat(router.route("数据源在哪儿").intent()).isEqualTo(ChatIntent.LIST_DATA);
    }

    @Test
    void 问规则仍然是规则() {
        assertThat(router.route("有哪些规则").intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(router.route("规则列表").intent()).isEqualTo(ChatIntent.LIST_RULES);
    }

    @Test
    void 只说有哪些没有名词_老实说没听懂() {
        assertThat(router.route("有哪些").intent()).isEqualTo(ChatIntent.HELP);
    }

    @Test
    void 看字段会带出表名() {
        ChatIntentRouter.Call explicit = router.route("看一下 bill_left 的字段");
        assertThat(explicit.intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(explicit.table()).isEqualTo("bill_left");

        ChatIntentRouter.Call columns = router.route("bill_right 这张表有哪些列");
        assertThat(columns.intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(columns.table()).isEqualTo("bill_right");

        // 没点名表也要认得出意图，由会话去反问"看哪张"
        assertThat(router.route("这张表有哪些列").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("看一下表结构").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
    }

    /**
     * 守卫：新增「看字段」这一档时不能把「看一下 X」整类说法抢走。
     * 上一轮刚因为这个（裸的「有哪些」）把「有哪些数据」答成规则清单。
     */
    @Test
    void 看字段不抢规则与差异的说法() {
        assertThat(router.route("看一下规则").intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(router.route("看一下差异明细").intent()).isEqualTo(ChatIntent.LIST_DIFFS);
        assertThat(router.route("看一下任务 3").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
    }

    @Test
    void 英文表名不会被当成规则编码() {
        ChatIntentRouter.Call call = router.route("look at bill_left columns");
        assertThat(call.intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(call.ruleCode()).isNull();
        assertThat(call.table()).isEqualTo("bill_left");
    }

    @Test
    @DisplayName("说起草建议_归到看字段_因为建议要基于真实结构")
    void 起草建议归到看字段() {
        assertThat(router.route("看一下 bill_left 怎么配规则").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("bill_left 给个起草建议").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
    }

    @Test
    @DisplayName("问数据有多少行_问的是表不是任务_否则会被答成看哪个任务")
    void 数据行数归到表() {
        assertThat(router.route("数据有多少行").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("库里有多少条数据").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("bill_left 有多少行").table()).isEqualTo("bill_left");
        // 带"这次/任务"的仍然是任务总览：主语变了，意图就该变
        assertThat(router.route("这次跑了多少行").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
        assertThat(router.route("任务 3 扫了多少行").intent()).isEqualTo(ChatIntent.JOB_SUMMARY);
    }

    @Test
    @DisplayName("维度这两个字要看主语_表上的维度是看表_按什么维度对账是问规则")
    void 维度分主语() {
        assertThat(router.route("数据有哪些维度").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("bill_left 有哪些维度").table()).isEqualTo("bill_left");
        assertThat(router.route("这张表有什么维度").intent()).isEqualTo(ChatIntent.DESCRIBE_TABLE);
        assertThat(router.route("按什么维度对账").intent()).isEqualTo(ChatIntent.LIST_RULES);
        assertThat(router.route("规则按什么维度匹配").intent()).isEqualTo(ChatIntent.LIST_RULES);
    }
}
