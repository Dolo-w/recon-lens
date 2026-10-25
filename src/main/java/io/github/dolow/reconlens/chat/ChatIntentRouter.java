package io.github.dolow.reconlens.chat;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一句话 → 一个动作。**确定性的**，不调模型。
 *
 * <p>为什么不用模型做意图识别：内建会话的定位是「没有 key 也能用」。如果路由本身要一次模型调用，
 * 那"无 key 可用"就成了"无 key 只能用表单"，会话这一层等于没做。规则式路由的代价是
 * 说法必须落到词表里——所以每个动作都配了 {@link ChatIntent#example()}，帮助里直接告诉用户怎么说。
 *
 * <p>为什么不在这里回落到槽位：路由只回答"这句话想干嘛、里面显式提到了谁"。
 * "提到谁"和"指的是谁"是两件事——前者在句子里，后者在会话里，混在一起写，
 * 就没法单独测"这句话能识别成什么动作"了。
 */
public final class ChatIntentRouter {

    /** 路由结果。{@code ruleBody} 只有"贴规则"时才非空。 */
    public record Call(ChatIntent intent, String ruleCode, Long jobId, Long diffId, String diffType,
                       String ruleBody, String table) {

        static Call of(ChatIntent intent) {
            return new Call(intent, null, null, null, null, null, null);
        }

        Call withRuleCode(String code) {
            return new Call(intent, code, jobId, diffId, diffType, ruleBody, table);
        }

        Call withJob(Long id) {
            return new Call(intent, ruleCode, id, diffId, diffType, ruleBody, table);
        }

        Call withDiff(Long id) {
            return new Call(intent, ruleCode, jobId, id, diffType, ruleBody, table);
        }

        Call withDiffType(String type) {
            return new Call(intent, ruleCode, jobId, diffId, type, ruleBody, table);
        }

        Call withBody(String body) {
            return new Call(intent, ruleCode, jobId, diffId, diffType, body, table);
        }

        Call withTable(String name) {
            return new Call(intent, ruleCode, jobId, diffId, diffType, ruleBody, name);
        }
    }

    /**
     * 表名：小写标识符（snake_case），排除常见英文词。
     *
     * <p>为什么单独一套而不是复用 {@link #RULE_CODE}：规则编码是 {@code BILL_VS_SETTLE} 这种大写，
     * 表名是 {@code bill_left} 这种小写，一个正则同时覆盖两者会把 "YAML" 认成表名、
     * 把 "bill" 认成规则名。宁可两个都不匹配，也不要认错。
     */
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{2,40}");
    private static final Set<String> NOT_A_TABLE = Set.of(
            "yaml", "json", "sql", "the", "and", "ok", "id", "pass", "fail", "llm", "api", "http",
            "sse", "mcp", "rule", "rules", "diff", "diffs", "job", "jobs", "schema", "columns",
            "column", "table", "tables", "select", "from", "where", "order", "group", "limit");

    /** 规则编码：大写开头、至少 3 位。排除常见缩写，免得把 "YAML" 当成规则名。 */
    private static final Pattern RULE_CODE = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\b");
    private static final Set<String> NOT_A_CODE =
            Set.of("YAML", "JSON", "SQL", "HTTP", "SSE", "API", "MCP", "ID", "OK", "PASS", "FAIL", "LLM");

    /** 「123 号」「任务 123」「job 123」「第 123 号任务」。 */
    private static final Pattern JOB_ID = Pattern.compile(
            "(?:任务|job|JOB|批次|单子)\\s*[#第]?\\s*(\\d{1,18})|第?\\s*(\\d{1,18})\\s*号");
    /**
     * 「第 137 条」「差异 3」。
     *
     * <p>允许多说一句"差异 3"是因为人报差异号时常省略"第…条"；
     * 中文数字（"第一条"）也要认——这是 golden set 里"第一条差异帮我看看"暴露的漏。
     * 注意不能把「任务 3」当成差异号：前缀必须真的是"差异"。
     */
    private static final Pattern DIFF_ID = Pattern.compile(
            "第?\\s*([0-9一二三四五六七八九十]{1,6})\\s*条|差异\\s*[#第]?\\s*([0-9一二三四五六七八九十]{1,6})");
    /** 代码块。 */
    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\s*\\n?(.*?)```", Pattern.DOTALL);

    /**
     * 同义说法归一到标准词，作用在关键字匹配之前。
     *
     * <p>为什么要这一层：档位是顺序匹配，"不同说法"靠一条条往词表里加，加到最后没人记得
     * 哪句话会命中哪档。这里把口语先换成标准词，词表只维护标准词，加新说法就加一行替换，
     * 不必再去猜它会不会被前面某一档抢走。
     *
     * <p>只换词、不换意图：替换表里没有"先判哪档"的信息，顺序仍然由下面的 else-if 决定。
     * 注意它只作用于 {@code lower}（判意图用），参数抽取（规则名 / 任务号 / 差异号 / 表名）
     * 一律用原文，免得"第 3 条"被改成"第 3 行"这种参数级错误。
     */
    private static final List<String[]> CANON = List.of(
            // 差异的口语说法
            new String[]{"对不上", "差异"}, new String[]{"不平", "差异"}, new String[]{"差额", "差异"},
            new String[]{"差了几笔", "差了几"}, new String[]{"挂账", "差异"}, new String[]{"差错", "差异"},
            new String[]{"有问题", "差异"}, new String[]{"对不齐", "差异"}, new String[]{"对不平", "差异"},
            // 差异明细
            new String[]{"逐条", "明细"}, new String[]{"一条条", "明细"}, new String[]{"清单", "明细"},
            // 行数 / 条数
            new String[]{"多少条记录", "数据量"}, new String[]{"多少行记录", "数据量"},
            new String[]{"多少条", "多少行"}, new String[]{"多少笔", "多少行"}, new String[]{"几条记录", "数据量"},
            new String[]{"多少记录", "多少行"}, new String[]{"多少数据", "多少行"}, new String[]{"总量", "数据量"},
            // 表结构
            new String[]{"表里都有啥", "表结构"}, new String[]{"表里有什么", "表结构"},
            new String[]{"这表有啥", "表结构"}, new String[]{"表里放了啥", "表结构"},
            new String[]{"都有哪些列", "有哪些列"}, new String[]{"包含哪些列", "有哪些列"},
            new String[]{"有哪些属性", "字段"}, new String[]{"哪些字段", "字段"},
            // 任务总览
            new String[]{"跑完了吗", "跑完了"}, new String[]{"跑完没", "跑完了"},
            new String[]{"跑得怎么样", "怎么样"}, new String[]{"跑的结果", "结果"},
            new String[]{"有没有跑完", "跑完了"}, new String[]{"跑到哪", "进度"},
            new String[]{"扫了多少", "扫多少"}, new String[]{"读了多少", "扫多少"},
            // 跑一次
            new String[]{"跑一遍", "跑一下"}, new String[]{"跑一次", "跑一下"},
            new String[]{"对一下", "对一次"}, new String[]{"比对一下", "对一次"},
            new String[]{"执行一下", "执行"}, new String[]{"开始跑", "开始"},
            // 重跑
            new String[]{"再跑一遍", "重跑"}, new String[]{"重来一次", "重跑"},
            new String[]{"重做", "重跑"}, new String[]{"从失败处继续", "补跑"},
            // 试算
            new String[]{"预演", "试算"}, new String[]{"试跑", "试算"}, new String[]{"沙箱", "试算"},
            // 审批
            new String[]{"能不能过", "能不能批"}, new String[]{"能过吗", "能批吗"},
            new String[]{"给不给过", "能不能批"}, new String[]{"可以上线吗", "能不能批"},
            // 归因
            new String[]{"咋回事", "怎么回事"}, new String[]{"怎么会", "为什么"},
            new String[]{"啥原因", "原因"}, new String[]{"什么意思", "解释"},
            // 帮助
            new String[]{"你会啥", "会什么"}, new String[]{"能干嘛", "能做什么"},
            new String[]{"怎么用你", "怎么用"}, new String[]{"使用说明", "怎么用"},
            // 看数据
            new String[]{"有哪些库", "有哪些库"}, new String[]{"都有什么表", "哪些表"},
            new String[]{"有多少张表", "几张表"}, new String[]{"数据从哪来", "数据在哪"}
    );

    /** 重跑类说法单独先跑一遍替换，避免"再跑一遍"被 RUN 的"跑一遍"抢走。 */
    private static final List<String[]> RETRY_FIRST = List.of(
            new String[]{"再跑一遍", "重跑"}, new String[]{"再跑一次", "重跑"},
            new String[]{"重新跑", "重跑"}, new String[]{"重来一次", "重跑"});

    /** 把口语换成标准词；只影响关键字匹配，不参与参数抽取。 */
    static String canonical(String lower) {
        String s = lower;
        for (String[] pair : RETRY_FIRST) {
            s = s.replace(pair[0], pair[1]);
        }
        for (String[] pair : CANON) {
            if (s.contains(pair[0])) {
                s = s.replace(pair[0], pair[1]);
            }
        }
        return s;
    }

    public Call route(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return Call.of(ChatIntent.HELP);
        }
        // 同义说法先归一，再走档位匹配：词表只维护标准词，新说法进 CANON。
        String lower = canonical(text.toLowerCase(Locale.ROOT));

        // 1) 贴了一份规则（代码块 / YAML / JSON）——先判这个，否则里面的 "left:" 会被别的词抢走
        String body = ruleBody(text);
        if (body != null) {
            return Call.of(ChatIntent.DEFINE_RULE).withBody(body).withRuleCode(ruleCode(text));
        }

        // 2) 帮助
        if (containsAny(lower, "帮助", "能做什么", "会什么", "怎么用", "有什么功能", "help")) {
            return Call.of(ChatIntent.HELP);
        }

        // 3) 审批：只解释流程。真实审批要确认码 + 内容哈希，会话里说什么都不算签字
        if (containsAny(lower, "批准", "审批", "签字", "通过这条规则", "签一下",
                "能不能批", "能批吗", "可以批", "批一下", "批复")) {
            return Call.of(ChatIntent.APPROVE).withRuleCode(ruleCode(text));
        }

        // 4) 试算
        if (containsAny(lower, "试算", "trial", "影子")) {
            return Call.of(ChatIntent.TRIAL_STATUS).withRuleCode(ruleCode(text));
        }

        // 5) 建模型
        if (containsAny(lower, "建模型", "物化", "模型表", "build_model", "预处理模型")) {
            return Call.of(ChatIntent.BUILD_MODEL).withRuleCode(ruleCode(text));
        }

        // 6) 重跑
        if (containsAny(lower, "重跑", "重试", "再跑一遍", "重新跑", "补跑", "retry", "补偿")) {
            return Call.of(ChatIntent.RETRY).withJob(jobId(text)).withRuleCode(ruleCode(text));
        }

        // 6.5) 起草一条新规则：**动作词优先于名词**。
        //      「起草个规则」里既有动词（起草）又有名词（规则）。名词档在后面，
        //      原先它先命中，于是"我要起草"被答成"当前库里有哪几条规则"——
        //      答得通顺、答非所问，而且不报错。规则：动作词定意图，对象词定参数，
        //      名词不单独构成意图。
        if (draftingRequest(lower)) {
            return Call.of(ChatIntent.DEFINE_RULE).withRuleCode(ruleCode(text));
        }

        // 7) 归因：单条 → 整单 → 看差异。顺序不能反：
        //    "为什么差这么多" 里有"差异"，先判"看差异"就会答成明细列表。
        boolean why = containsAny(lower, "为什么", "原因", "咋回事", "怎么回事", "怎么造成", "归因", "解释");
        Long diffId = diffId(text);
        if (diffId != null && (why || containsAny(lower, "差异", "diff", "这条"))) {
            return Call.of(ChatIntent.EXPLAIN_DIFF).withDiff(diffId).withJob(jobId(text));
        }
        if (why) {
            return Call.of(ChatIntent.EXPLAIN_JOB).withJob(jobId(text)).withRuleCode(ruleCode(text));
        }

        // 8) 看差异。带上四类差异的口语说法，否则用户说「左边缺的」会掉到帮助里去
        if (containsAny(lower, "差异", "明细", "差多少", "差了几", "diff", "对不上的",
                "缺", "重复", "不一致", "不等")) {
            return Call.of(ChatIntent.LIST_DIFFS)
                    .withJob(jobId(text))
                    .withDiffType(diffType(lower));
        }

        // 8.5) 「数据有多少行」问的是表，不是任务。
        //      原先"多少行"只挂在任务总览上，于是这句话会被答成"看哪个任务" ——
        //      一句话里出现"表/数据"时，它的主语就是表；要任务号那是"这次跑了多少行"的事。
        // 主语三选一：提到"表/数据"，或者干脆点了表名（"bill_left 有多少行"没有"表"字）。
        String rowTable = tableName(text);
        if ((containsAny(lower, "表", "数据") || rowTable != null)
                && !containsAny(lower, "任务", "这次", "上次", "跑完", "进度")
                && containsAny(lower, "多少行", "几行", "多少条", "几万行", "行数", "条数", "数据量")) {
            return new Call(ChatIntent.DESCRIBE_TABLE, null, null, null, null, null, rowTable);
        }

        // 8.6) 执行历史：问的是"过去跑过什么"，不是"规则目录里有什么"，也不是"现在跑一次"。
        //      必须放在第 9/10 档之前——"之前都执行过哪些规则"这一句里同时有"执行"（第 10 档的动词）
        //      和"规则"（第 12 档的名词），谁先判谁就把它抢走，答出来的都是"当前库有什么"。
        //      只在明显是"要一份清单"时才命中（跑过 / 执行过 / 之前+哪些），
        //      "2 号任务跑过了吗"仍归任务总览——那是问某一次的结果，不是要历史清单。
        boolean pastMark = containsAny(lower, "之前", "以前", "历史", "过去", "曾经");
        boolean historyAsk = containsAny(lower, "跑过", "执行过", "对过账", "扫过")
                || (pastMark && containsAny(lower, "哪些", "什么", "几次", "都", "记录", "清单"));
        if (historyAsk && !containsAny(lower, "了吗", "完成", "进度")) {
            return Call.of(ChatIntent.LIST_JOBS);
        }

        // 9) 任务总览
        if (containsAny(lower, "任务", "job", "概况", "怎么样", "结果", "跑得如何", "总结",
                "跑完了", "跑过了", "完成了吗", "进度", "扫了", "多少行", "扫多少")) {
            return Call.of(ChatIntent.JOB_SUMMARY).withJob(jobId(text)).withRuleCode(ruleCode(text));
        }

        // 10) 跑一次
        // 注意不能把光秃秃的"对账"当动词：'有哪些对账规则'会因此被抢成"跑一次"
        if (containsAny(lower, "跑", "执行", "开始", "来一次", "跑一下",
                "对一次", "对一遍", "做一次", "做一遍")) {
            return Call.of(ChatIntent.RUN).withRuleCode(ruleCode(text));
        }

        // 11) 看数据：问的是"有哪些表/数据源"，不是"有哪些规则"。
        //     这一档是后补的：原先靠裸的"有哪些"命中规则列表，于是「有哪些数据」会答成规则清单，
        //     用户看到的是和上一句一模一样的结果 —— 疑问词不是话题词，不能拿它定意图。
        if (containsAny(lower, "哪些数据", "数据源", "哪些表", "几张表", "表清单",
                "数据在哪", "注册了哪些", "有哪些库")) {
            return Call.of(ChatIntent.LIST_DATA);
        }

        // 11.5) 看某张表的字段
        //     放在"看数据"之后、"规则列表"之前：它比"看数据"具体（指名了表），
        //     但比"规则"更该优先命中——否则「看一下 bill_left 的字段」会被"规则"
        //     那一档里的"列表/规则"之类词抢走（历史上就是这么错的）。
        // 注意不要放裸的「看一下」：它和"看一下规则""看一下差异"是同一种开头，
        // 会把后面两档整条路抢走（这跟上一档"有哪些"踩的是同一个坑）。
        // 点名的表 + 起草类说法 → 看字段（连建议）。放在规则列表之前：
        // "bill_left 怎么配规则"问的是这张表怎么配，不是"规则库里有几条"。
        if (containsAny(lower, "起草", "建议", "推荐", "怎么配", "配什么", "用哪几列")) {
            String named = tableName(text);
            if (named != null) {
                return new Call(ChatIntent.DESCRIBE_TABLE, null, null, null, null, null, named);
            }
        }
        // 「样例 / 长什么样」也归这一档：它们问的是同一件事（这张表里是什么），
        // 只是要不要读数据行的差别交给会话层判断（默认不读）。
        // 「维度」歧义：问表有哪些维度 = 看表；问"按什么维度对账" = 问规则口径。
        if (containsAny(lower, "维度", "主键", "唯一键", "去重键")
                && containsAny(lower, "对账", "规则", "口径", "匹配", "比什么", "怎么比")) {
            return Call.of(ChatIntent.LIST_RULES).withRuleCode(ruleCode(text));
        }

        if (containsAny(lower, "字段", "列名", "有哪些列", "哪几列", "表结构", "这张表", "这表有",
                "表长什么样", "样例", "样本", "几条数据", "几行数据", "维度", "主键", "唯一键",
                "schema", "columns")) {
            return Call.of(ChatIntent.DESCRIBE_TABLE).withTable(tableName(text)).withRuleCode(ruleCode(text));
        }

        // 12) 规则列表
        if (containsAny(lower, "规则", "策略", "列表", "rule")) {
            return Call.of(ChatIntent.LIST_RULES).withRuleCode(ruleCode(text));
        }

        return Call.of(ChatIntent.HELP);
    }

    /**
     * 这句话是不是"要一条新规则"（而不是"看看现有规则"）。
     *
     * <p>判据是**动词**，不是名词：{@code 规则} 这个词在两种句子里都出现，
     * 出现它不代表任何意图。所以这里只认动作词，并且对歧义大的词（新建/新增/加个）
     * 额外要求同句里有对账域的对象词——否则「新增的差异有多少」会被当成起草。
     */
    private static boolean draftingRequest(String lower) {
        // "起草建议"问的是"这张表该怎么配"，不是"给我一条规则"——它要先读表结构，
        // 所以留给后面的"看字段"档（那里才知道是哪张表、有哪些列）。
        if (containsAny(lower, "起草建议", "起草推荐", "起草意见")) {
            return false;
        }
        if (containsAny(lower, "起草", "定义一条", "定义个", "我要定义")) {
            return true;
        }
        boolean create = containsAny(lower, "新建", "新增", "创建", "加一条", "加个", "配一条", "配个",
                "做一条", "建一条", "建个规则", "建个对账", "搞一条");
        boolean object = containsAny(lower, "规则", "对账", "核对", "口径", "比对", "匹配");
        return create && object;
    }

    /** 判断这句话是不是"贴了一份规则"。返回正文，不是则 {@code null}。 */
    private static String ruleBody(String text) {
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            if (!inner.isEmpty()) {
                return inner;
            }
        }
        boolean looksYaml = text.contains("left:") && text.contains("right:");
        boolean looksJson = text.startsWith("{") && text.contains("\"") && text.contains(":");
        if (looksYaml || looksJson) {
            String stripped = text.replaceFirst("^(帮我)?(贴|加|定义|新增|提交|存)(一下)?(一条|一份|个)?(规则)?[：:，,\\s]*", "");
            return stripped.isBlank() ? text : stripped;
        }
        return null;
    }

    /** 从一句话里抠表名：取第一个"像表名"的小写标识符。认不出就返回 null，由调用方反问。 */
    static String tableName(String text) {
        if (text == null) {
            return null;
        }
        String loose = null;
        Matcher m = TABLE_NAME.matcher(text);
        while (m.find()) {
            // 手动判边界而不是用 \b：\b 在 Java 字符串里要写成 \\b，
            // 一旦哪次改动丢了转义就变成退格符（不可见、也不报错，只是永远匹配不上）。
            boolean leftOk = m.start() == 0 || !isWordChar(text.charAt(m.start() - 1));
            boolean rightOk = m.end() == text.length() || !isWordChar(text.charAt(m.end()));
            if (!leftOk || !rightOk) {
                continue;
            }
            String candidate = m.group();
            if (NOT_A_TABLE.contains(candidate)) {
                continue;
            }
            // 带下划线的优先：{@code bill_left} 一眼是表名，{@code look} 不是。
            // 两句英文连在一起时，先命中的往往是动词 —— 所以先记住它，继续找更像的那个。
            if (candidate.indexOf('_') > 0) {
                return candidate;
            }
            if (loose == null) {
                loose = candidate;
            }
        }
        return loose;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String ruleCode(String text) {
        Matcher m = RULE_CODE.matcher(text);
        while (m.find()) {
            if (!NOT_A_CODE.contains(m.group(1))) {
                return m.group(1);
            }
        }
        return null;
    }

    private static Long jobId(String text) {
        Matcher m = JOB_ID.matcher(text);
        if (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            return parseLong(raw);
        }
        return null;
    }

    private static Long diffId(String text) {
        Matcher m = DIFF_ID.matcher(text);
        // "第 137 条差异" 命中；但 "123 号任务" 不该被当成 diffId，所以要求后缀是"条"
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        return parseLong(raw);
    }

    private static String diffType(String lower) {
        if (containsAny(lower, "值不等", "不一致", "金额不等", "不相等")) {
            return "VALUE_MISMATCH";
        }
        if (containsAny(lower, "左缺", "左边缺", "少了", "仅有右")) {
            return "MISSING_LEFT";
        }
        if (containsAny(lower, "右缺", "右边缺", "仅有左")) {
            return "MISSING_RIGHT";
        }
        if (containsAny(lower, "重复", "dup")) {
            return "DUPLICATE_KEY";
        }
        return null;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return chineseNumber(raw);
    }

    /** 「一」…「十九」「二十三」这类口语数字。超出 99 的没人这么说话，直接放弃。 */
    private static Long chineseNumber(String raw) {
        String digits = "零一二三四五六七八九";
        String text = raw.replace("两", "二");
        int ten = text.indexOf('十');
        if (ten < 0) {
            int d = digits.indexOf(text.charAt(text.length() - 1));
            return d < 0 ? null : (long) d;
        }
        int high = ten == 0 ? 1 : digits.indexOf(text.charAt(0));
        int low = ten == text.length() - 1 ? 0 : digits.indexOf(text.charAt(text.length() - 1));
        if (high < 0 || low < 0) {
            return null;
        }
        return (long) (high * 10 + low);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 供测试与帮助文本用：动作清单。 */
    public static List<ChatIntent> intents() {
        return List.of(ChatIntent.values());
    }
}
