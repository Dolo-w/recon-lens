package io.github.dolow.reconlens.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.domain.RuleLifecycle;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.tool.ToolNames;
import io.github.dolow.reconlens.tool.ToolOutcome;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.domain.ApprovalMode;
import io.github.dolow.reconlens.domain.TrialState;
import io.github.dolow.reconlens.rule.RuleCatalog;
import io.github.dolow.reconlens.store.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 内建会话的运行时：一句话 → 识别动作 → 调**同一个**工具 → 回写轮次与槽位。
 *
 * <p>三条设计线，缺一条这个类就会变成第二套实现：
 * <ol>
 *   <li><b>工具是同一份</b>：全部走 {@link ReconMcpTools} / {@link ReconEngine}，和 MCP 客户端、
 *       REST 控制台用的是同一段代码。会话层只负责"把自然语言翻译成参数"。</li>
 *   <li><b>闸门不绕过</b>：{@code defineRule} 走规则库的校验与草稿态；{@code run} 走引擎的分片与容差。
 *       这个类里没有任何一句"直接改状态"的写操作。审批更是一句都不执行——只说流程。</li>
 *   <li><b>不依赖 agent 包</b>：{@code AgentBoundaryTest} 禁止 agent 包以外的类依赖它。
 *       所以这里没有 LLM 起草；无 key 时的"起草一句话"就回落成表单（列出动作清单）。</li>
 * </ol>
 *
 * <p>槽位是这一层的记忆：这一句没说 ruleCode，就拿上一句的；换了 job 就清掉 diffId。
 */
public class ChatRuntime {

    private static final Logger log = LoggerFactory.getLogger(ChatRuntime.class);

    /** 给规划器的"最近几轮原话"条数：多了撑 prompt，少了接不住"它/刚才那单"。 */
    private static final int HISTORY_TURNS = 6;

    /**
     * 理解阶段多带几轮：预算给到 4000 token，装得下 8 轮还不会截半句。
     *
     * <p>「刚才那个任务」「它」这类指代，6 轮常常刚好差一轮；理解阶段多要两轮是最便宜的对策，
     * 真正放不下时由 {@code TurnBudget.rounds} 整轮丢弃，不会留下半句。
     */
    private static final int HISTORY_TURNS_UNDERSTANDING = 8;

    /** 槽位写入的最大尝试次数（第 1 次 + 冲突后最多再合并重试 2 次）。 */
    private static final int SLOT_SAVE_ATTEMPTS = 3;

    /** 从 {@code recon_list_tables} 的人话输出里认表名（行首带「·」的那些）。 */
    private static final java.util.regex.Pattern TABLE_LINE =
            java.util.regex.Pattern.compile("·\\s*([A-Za-z_][A-Za-z0-9_]*)");

    /** 「就这两个吧 / 都要 / 随便」：指的是上一句刚列出来的候选，而不是重新报了两个名字。 */
    private static final java.util.regex.Pattern CANDIDATE_REF =
            java.util.regex.Pattern.compile("这(两个|俩|些)|就这|都要|都行|随便|两个都|它们");

    /** 「算了 / 取消」：正在等参数时，这句话是"别问了"，不是新指令。 */
    private static final java.util.regex.Pattern CANCEL =
            java.util.regex.Pattern.compile("算了|取消|不建了|不用了|先不|不弄了|放弃");

    /** 「展示/看看草稿」：收参数途中也要能看见全貌。 */
    private static final java.util.regex.Pattern PREVIEW =
            java.util.regex.Pattern.compile("展示|看看|看一下|长什么样|预览|完整|草稿");

    /**
     * 用户这句话是不是在说"比什么"。
     *
     * <p>模型填槽时常把业务键顺手也复制进比对槽：用户只是答"业务键是 order_no"，
     * 它却理解成"用 order_no 比"。这时候要静默丢掉比对值，别对着用户念"按它比没有差异"——
     * 他根本还没说要比。只有真说了"比/核对/对比"才值得解释一句。
     */
    private static final java.util.regex.Pattern COMPARE_CUE =
            java.util.regex.Pattern.compile("比|核对|对一下|对比");

    /**
     * 回答"我准备就按这一列写，对吗"时的肯定。
     *
     * <p>只在这种确认之后的一轮里看（槽位里有 {@code guessed} 标记），所以放宽一点没风险；
     * 但没有"去掉"这一支——那是由收集器自己的否定词处理的。
     */
    private static final java.util.regex.Pattern GUESS_YES =
            java.util.regex.Pattern.compile("^(是|对|嗯|好|行|可以|没错|就是|就用|按这个|就这样)");

    /** 等用户确认的写操作。与 pendingAction 同一个槽位，只是值固定为它。 */
    static final String CONFIRM_ACTION = "CONFIRM";

    /** 历史结论有两种说法，等用户挑一个。 */
    static final String PICK_CLAIM = "PICK_CLAIM";

    /** 用户挑的说法：第一个/前者 或 第二个/后者。 */
    private static final java.util.regex.Pattern PICK_FIRST =
            java.util.regex.Pattern.compile("第一|前者|按1|按一|^1$|^a$|^A$");
    private static final java.util.regex.Pattern PICK_SECOND =
            java.util.regex.Pattern.compile("第二|后者|按2|按二|^2$|^b$|^B$");

    /** 从一句话里捞规则编码：用来推历史结论的作用域。 */
    private static final java.util.regex.Pattern RULE_CODE_IN_TEXT =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{2,40}");

    /** 「是」：确认执行。否定先判，所以"不好"不会被当成"好"。 */
    private static final java.util.regex.Pattern AFFIRM =
            java.util.regex.Pattern.compile("^(确认|好|可以|行|对|是|执行|跑|开始|ok|yes|嗯|没问题|就这么办)");

    /**
     * 「否」：取消。**只认句首的干脆否定**，且不认疑问句。
     *
     * <p>为什么不能像以前那样满句子找 `不`：用户看到回执后常会反问一句
     * 「不是三个维度吗」——那是他在质疑刚才的理解，不是让系统撤单。
     * 满句子匹配 `不` 会把这一问当成取消，回一句"好，这次不做"，
     * 既答非所问，又把待确认的动作静默丢掉。
     */
    private static final java.util.regex.Pattern NEGATE =
            java.util.regex.Pattern.compile("^(不|别|取消|算了|不用|先不|先别|不行|停|no)");

    /** 疑问标记：带它的句子一律不算"干脆的取消"。 */
    private static final java.util.regex.Pattern QUESTION = java.util.regex.Pattern.compile("[吗呢么？?]");

    /** 一次回复：正文（给人看）+ 工具记录（给审计）+ 新槽位。 */
    record Reply(String text, String toolJson, ChatSlots slots) {
        static Reply text(String text, ChatSlots slots) {
            return new Reply(text, null, slots);
        }
    }

    /** 「要看样例」的说法。刻意收窄：只认明确的词，不认"看一下"。 */
    private static final java.util.regex.Pattern WANTS_SAMPLE =
            java.util.regex.Pattern.compile("样例|样本|长什么样|几条数据|几行数据|空值|多少种|不同值");

    /** 「起草/怎么配/给个建议」——只有这些词才值得附上骨架，不然看结构的时候很吵。 */
    private static final java.util.regex.Pattern WANTS_ADVICE =
            java.util.regex.Pattern.compile("起草|建议|推荐|怎么配|配什么|怎么对|用哪几列");

    private final ChatSessionService sessions;
    private final ChatSummaryService summaries;
    private final ToolRegistry registry;
    private final ReconEngine engine;
    private final RuleCatalog catalog;
    private final ObjectMapper json;
    private final io.github.dolow.reconlens.recall.RecallAugmenter recall;
    private final io.github.dolow.reconlens.store.ReconRepository repository;

    /** 会话草稿账本：本会话起草过哪些规则版本（事实层，只增）。 */
    private final io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts;

    /** 给新规则起名。可空：没有模型时退回 AUTO_ 编码。 */
    private final RuleNamer namer;

    /**
     * 意图层：模型优先理解、词表只留快路径（{@link io.github.dolow.reconlens.chat.plan.CompositePlanner}）。
     *
     * <p>为什么把整层抽成接口而不是加一个 {@code if (llm != null)}：模型这一层要能被
     * 整体关掉（无 key 的部署）、被熔断（网关挂掉）、被替换（换供应商），三种情况都不该
     * 让 {@code handle()} 里出现分支。它只负责问"这句话想干嘛"，答不上来时词表那层还在。
     */
    private final io.github.dolow.reconlens.chat.plan.ChatPlanner planner;

    /**
     * 把结构化结果说成人话。可空：没有它（无 key、或显式关掉）时，回复就是引擎原文，
     * 与 P1 行为完全一致——这一层只改"怎么读"，不改"读到什么"。
     */
    private final ChatNarrator narrator;

    /**
     * 起草时"这句在回答哪一问"的模型层。可空：没有 key 时退回 {@link DraftIntake#answer} 的确定性兜底。
     *
     * <p>为什么不并进规划器：规划器输出"执行哪个动作"，起草参数是"动作内部的状态"。
     * 分开之后，没有 key 的部署仍然是 P1 的完整行为，多出来的只是一个可选字段。
     */
    private final DraftSlotFiller slotFiller;

    /**
     * 会话结论板：把定下来的事按主题记住（后写覆盖先写），每轮拼在摘要后面给模型看。
     *
     * <p>为什么需要它：压缩只让旧话变短，不会让旧话失效；用户改口之后，摘要里的旧结论
     * 仍可能被当成当前有效。结论板不压过程，只保留"此刻还算数的那一句"。
     */
    private final ChatClaims claims;

    /** 系统事实的短命缓存：同一份目录 30 秒内只拼一次（见 {@link FactsCache}）。 */
    private final FactsCache factsCache;

    /**
     * 会话级 token 账本：累计过线之后把预算档从理解档收到执行档（见 {@link TurnLedger}）。
     *
     * <p>为什么不并进会话状态：累计值只是"这个会话聊了多久"的近似，不是账务数据；
     * 放进 {@code ChatSession} 的 JSON 要走仓储/服务/迁移三处，换来的只是重启后不清零。
     */
    private final TurnLedger ledger;

    /**
     * 对话装法：判意图（老路径）还是让模型自己走（自由循环，{@code recon.chat.mode}）。
     *
     * <p>它不是"更聪明的路由"，而是**换掉编排本身**：循环模式里没有槽位改写、没有词表快路径、
     * 没有起草收集器，模型看到的是事实 + 记忆 + 工具 + 手册，自己决定下一步。
     * 两种装法共用同一套执行层（{@code dispatch}/{@code dispatchTool}）和同一条写动作回执。
     */
    private final ChatMode mode;

    /** 自由循环最多几跳（含第一跳）。0 表示不开放多跳，行为与单步一样。 */
    private final int loopHops;

    public ChatRuntime(ChatSessionService sessions, ChatSummaryService summaries, ToolRegistry registry,
            io.github.dolow.reconlens.recall.RecallAugmenter recall,
            io.github.dolow.reconlens.store.ReconRepository repository,
                       ReconEngine engine, RuleCatalog catalog, ObjectMapper json,
                       io.github.dolow.reconlens.chat.plan.ChatPlanner planner, ChatNarrator narrator,
                       io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts, RuleNamer namer,
                       DraftSlotFiller slotFiller, ChatClaims claims, FactsCache factsCache,
                       TurnLedger ledger) {
        this(sessions, summaries, registry, recall, repository, engine, catalog, json, planner, narrator,
                chatDrafts, namer, slotFiller, claims, factsCache, ledger, ChatMode.LEGACY, 0);
    }

    public ChatRuntime(ChatSessionService sessions, ChatSummaryService summaries, ToolRegistry registry,
            io.github.dolow.reconlens.recall.RecallAugmenter recall,
            io.github.dolow.reconlens.store.ReconRepository repository,
                       ReconEngine engine, RuleCatalog catalog, ObjectMapper json,
                       io.github.dolow.reconlens.chat.plan.ChatPlanner planner, ChatNarrator narrator,
                       io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts, RuleNamer namer,
                       DraftSlotFiller slotFiller, ChatClaims claims, FactsCache factsCache,
                       TurnLedger ledger, ChatMode mode, int loopHops) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.registry = registry;
        this.recall = recall;
        this.repository = repository;
        this.engine = engine;
        this.catalog = catalog;
        this.json = json;
        this.planner = planner;
        this.narrator = narrator;
        this.chatDrafts = chatDrafts;
        this.namer = namer;
        this.slotFiller = slotFiller;
        this.claims = claims;
        this.factsCache = factsCache;
        this.ledger = ledger == null ? new TurnLedger() : ledger;
        this.mode = mode == null ? ChatMode.LEGACY : mode;
        this.loopHops = Math.max(0, loopHops);
    }

    /**
     * 处理一句话。事件按发生顺序推给 {@code sink}；异常不外抛——SSE 已经开始了，
     * 抛出去客户端只会看到一个断掉的连接。
     */
    public void handle(SessionScope scope, long sessionId, String message, Consumer<ChatEvent> sink) {
        List<String> tools = new ArrayList<>();
        try {
            ChatSession session = sessions.require(scope, sessionId);
            sessions.append(scope, sessionId, "user", message);
            // 这一句的轮次：append 之后 = 追加前的轮数 + 1。结论板用它标"这是第几轮定的"。
            int userSeq = session.turnCount() + 1;

            ChatSlots slots = readSlots(session);
            // 会话刚开、话里却带着"刚才/上面"：这句在续一段本会话里没有的上下文
            // （常见于服务重启后内存库清零，或换了会话）。不装作接得上，也不去猜——
            // 猜错的代价是拿错对象去执行，比多问一句贵得多。
            if (session.turnCount() == 0 && slots.pendingAction() == null && refersToEarlier(message)) {
                String text = "这条会话里没有更早的记录，你说的「" + brief(message) + "」我接不上。"
                        + "如果刚才贴过规则、跑过任务，把那段话再贴一次或给个任务号，我就能接着做。";
                sessions.append(scope, sessionId, "assistant", text,
                        envelope(ChatIntent.HELP, tools));
                ChatSlots next = slots.withIntent(ChatIntent.HELP.name());
                saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
                emit(sink, Reply.text(text, next), next, sessionId);
                return;
            }
            // 历史结论出现两种说法：上一轮已经问过"以哪个为准"，这一句是在回答它。
            if (PICK_CLAIM.equals(slots.pendingAction())) {
                Reply picked = resolvePick(session, slots, message, userSeq);
                if (picked != null) {
                    ChatSlots next = picked.slots().withIntent(ChatIntent.HELP.name());
                    sessions.append(scope, sessionId, "assistant", picked.text(),
                            envelope(ChatIntent.HELP, tools));
                    saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
                    emit(sink, picked, next, sessionId);
                    return;
                }
                // 不是在挑：当新话题处理，别让这个问题永远挂着
                slots = slots.clearPending();
            }
            // 两段式的第二段：上一轮问过"确认吗"，这一句就是在回答它。
            // 必须放在规划器之前——"确认/取消"本身不构成任何动作，交给规划器只会被判成 HELP。
            if (CONFIRM_ACTION.equals(slots.pendingAction())) {
                Confirmed confirmed = handleConfirm(scope, sessionId, slots, message, tools, sink, userSeq);
                if (confirmed != null) {
                    ChatSlots next = confirmed.reply().slots().withIntent(confirmed.intent().name());
                    sessions.append(scope, sessionId, "assistant", confirmed.reply().text(),
                            envelope(confirmed.intent(), tools));
                    saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
                    summaries.maybeSummarize(scope, sessionId);
                    emit(sink, confirmed.reply(), next, sessionId);
                    return;
                }
                // 既不是"是"也不是"否"：分两种。
                // 起草的确认是"审阅点"不是"执行点"——用户在这时改一句参数（"比金额"）很常见，
                // 得把这句当成对草稿的修改继续往下走，不能把攒了几轮的参数一起丢掉。
                if (ChatIntent.DEFINE_RULE.name().equals(slots.pendingArgs().get("intent"))) {
                    slots = slots.withPending(DraftIntake.ACTION, slots.pendingArgs());
                } else {
                    // 跑批/重建这类写操作没有"草稿可改"：新指令就是放弃，别让旧动作被后一句"好"误触发
                    slots = slots.clearPending();
                }
            }
            // 预算档由累计用量决定，不由这一句话的长度决定：短会话一律给理解档，
            // 因为"理解错了后面全错"的代价远大于多花的那点 token。
            TurnBudget.Phase phase = ledger.phaseOf(sessionId);
            int historyLimit = phase == TurnBudget.Phase.UNDERSTANDING ? HISTORY_TURNS_UNDERSTANDING : HISTORY_TURNS;
            // 自由循环里"记忆"得是双向的：只给用户原话，模型看不到自己上一轮问过什么、查过什么，
            // 于是每轮都把两张表重新 describe 一遍、把同一个问题再问一遍（实测里连着三轮）。
            List<String> history = mode.loop()
                    ? recentDialogue(scope, sessionId, historyLimit)
                    : recentUserMessages(scope, sessionId, historyLimit);
            // 每轮重建一次系统事实（规则目录 + 最近任务 + 表名）：只读元数据，不读任何数据行。
            // 它同时是"给模型看的索引"和"校验模型输出的依据"——两件事用同一份事实，才不会自相矛盾。
            ContextFacts facts = factsCache.get(factsCache.revision(),
                    () -> ContextFacts.build(catalog, repository));
            // 摘要（压缩过的历史）+〔当前有效〕（还没被推翻的结论）+〔历史结论〕（同一操作人跨会话）。
            // 顺序就是优先级：越靠后越硬，当前会话的结论压过历史结论。
            String contextText = composeContext(session.summary(), claims.render(sessionId),
                    sharedBlock(session, slots, message));
            io.github.dolow.reconlens.chat.plan.ChatPlan plan = planner.plan(message, slots, facts, history,
                            contextText, phase)
                    .orElseGet(this::planFallback);
            ChatIntentRouter.Call call = plan.call();
            // 起草的收集是状态机：上一问还没答完时，"这句是不是在回答它"由槽位判定，
            // 不能由模型今天愿不愿意多聊一句决定。实测：问完左侧表，用户回「bill_left」，
            // 模型把它接过去答了一段，已经收下的表当场丢了，下一句又从"左侧用哪张表"开始。
            // 只读工具（真的换了话题）与起草/写工具仍然放行。
            //
            // 自由循环里这段整体关闭：那正是"编排"本身——参数怎么收、什么时候问，交给模型；
            // 它想查列就查列，想问就问。留在循环里就成了两套状态机抢同一句话。
            if (!mode.loop()) {
            boolean intakePending = DraftIntake.ACTION.equals(slots.pendingAction());
            boolean readOnlyTool = plan.isToolCall() && !ToolNames.WRITES.contains(plan.toolName())
                    && !ToolNames.DRAFT_RULE.equals(plan.toolName());
            // 只读工具也要让路的场合：这一句本来就是在回答起草的问题。问"是要去掉这一维，
            // 还是它对应哪一列"，用户回「bill_right」，模型十有八九读成"看这张表"——
            // 那是把参数当指令，收集器就得从头再来。
            boolean draftAnswer = readOnlyTool && parsesAsDraftAnswer(message, slots, tools);
            boolean intakeOwnsTurn = intakePending && (!readOnlyTool || draftAnswer);
            if (intakeOwnsTurn || (!plan.isToolCall() && !plan.isReply())) {
                // 这两步都在改写"按什么理解"：工具调用是模型自己给出的完整动作，
                // 拿槽位/草稿账本去覆盖它，等于把它的判断丢掉——还留下一个与 args 不一致的 Call。
                ChatIntentRouter.Call resumed = resumePending(call, slots, message, tools);
                if (intakeOwnsTurn && plan.isReply() && resumed == call) {
                    // 模型自己答了、而且这句确实不是起草参数：按换话题处理，把半截草稿放下，
                    // 否则用户问「这几张表什么关系」会一直被拉回"左侧用哪张表"。
                    slots = slots.clearPending();
                } else {
                    call = resumed;
                    ChatIntentRouter.Call referenced = applyDraftReference(sessionId, call, message);
                    if (referenced != call) {
                        // 「第二条」指的是本会话账本里的第 N 条草稿：解析结果要回执，用户才看得出按哪条理解的
                        call = referenced;
                        int index = draftIndex(message);
                        plan = io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 0.85,
                                index >= 1
                                        ? "按会话草稿账本第 " + index + " 条：" + call.ruleCode()
                                        : "按会话草稿描述匹配：" + call.ruleCode());
                    }
                    if (call != plan.call()) {
                        // 续接上一轮的"半句话"：这一句本身可能被判成 HELP（比如只回了一个规则编码），
                        // 但它在回答上一轮的追问，所以要把"按什么理解"如实说成人话，别让用户看到 HELP。
                        plan = io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 0.85,
                                "续接上一轮：" + slots.pendingAction() + "（" + message.trim() + "）");
                    }
                }
            }
            }  // end of !mode.loop()
            // 先推"怎么理解的"，再推"理解成了什么"：用户第一眼要的是依据，不是枚举名
            sink.accept(ChatEvent.plan(plan));
            sink.accept(ChatEvent.intent(call.intent()));
            // 这一轮进提示词的大致总量：用户、事实、摘要+结论板、历史。它是估算，不是账单——
            // 但"它为什么忘了上一句"这种事，靠这个数就能自查，不用翻日志猜。
            int turnTokens = TurnBudget.estimate(message)
                    + TurnBudget.estimate(facts.text())
                    + TurnBudget.estimate(contextText)
                    + history.stream().mapToInt(turn -> TurnBudget.estimate(turn)).sum();
            int tokensUsed = ledger.add(sessionId, turnTokens);
            log.info("本轮预算 session={} phase={} turn≈{} tokensUsed≈{}",
                    sessionId, phase, turnTokens, tokensUsed);
            sink.accept(ChatEvent.budget(turnTokens, tokensUsed, phase.name()));

            // 历史结论里同一件事有两种说法：不替用户挑，先把两种摆出来问。
            // 放在执行之前——按错口径跑一遍，差异清单会把人带沟里，比多问一句贵得多。
            ChatClaims.Conflict conflict = conflictFor(session, call);
            if (conflict != null) {
                java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
                args.put("intent", call.intent().name());
                args.put("ruleCode", call.ruleCode());
                args.put("scope", conflict.scope());
                args.put("key", conflict.key());
                args.put("a", conflict.a().value());
                args.put("b", conflict.b().value());
                args.put("summary", "历史口径二选一");
                Reply ask = Reply.text(conflict.describe(), slots.withPending(PICK_CLAIM, args));
                ChatSlots next = ask.slots().withIntent(ChatIntent.HELP.name());
                sessions.append(scope, sessionId, "assistant", ask.text(),
                        envelope(ChatIntent.HELP, tools));
                saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
                emit(sink, ask, next, sessionId);
                return;
            }

            Reply reply;
            // 这一轮真正"最后执行的是哪个动作"：开了第二步时会与 plan 里的不同，
            // 槽位要记的是实际发生的那个，否则下一轮模型会以为上一步是 LIST_DIFFS。
            java.util.concurrent.atomic.AtomicReference<ChatIntent> executed =
                    new java.util.concurrent.atomic.AtomicReference<>(call.intent());
            // 写动作只有一个判据：不管是工具形态（recon_run）还是意图形态（RUN），
            // 只要是"写、且参数齐了"，就必须先回执。判据曾经按形态分岔，结果同一句话
            // 在两种形态下一边问一边不问——安全规则不能取决于模型这次选了哪种 JSON。
            boolean write = writeAction(plan);
            if (slots.hasPending() && message != null && CANCEL.matcher(message).find()) {
                // 用户在回答"要哪张表"时说"算了"：这是在叫停，不是要重开一个话题
                reply = Reply.text("好，这次不建了；刚记下的参数已经清空。", slots.clearPending());
            } else if (mode.loop()) {
                // 自由循环：没有"这一步该选哪个动作"的分发，只有"做到哪儿了"的推进。
                // 取消、写回执、收尾都在 loopTurn 里按执行结果决定，narrate 也不参与——
                // 循环里模型自己就是说话的人，再让总结层改一遍等于二次创作。
                reply = loopTurn(scope, message, facts, history, contextText, plan, slots, tools, sink, executed);
            } else if (call.intent() == ChatIntent.HELP && plan.hasOptions()) {
                // 带候选的"没听懂"不是"要说明书"：给一句短问句 + 候选项，别甩整页帮助
                reply = Reply.text(clarifyText(plan), slots);
            } else if (plan.isReply()) {
                // 模型自己选了「说话/反问」：它的原话就是回答，任何总结层改写都算篡改
                reply = Reply.text(plan.reply(), slots);
            } else if (write) {
                // 两段式的第一段：会改数据的动作，先把"我理解成什么"说给用户听，等一句确认
                String summary = plan.isToolCall() ? describeTool(plan) : describeCall(call);
                String text = narrator == null ? null : narrator.confirm(call.intent(), summary, message);
                reply = Reply.text(text == null ? defaultConfirm(summary) : text,
                        slots.withPending(CONFIRM_ACTION, pendingOf(plan, summary)));
            } else {
                // 守卫话术（IntentReview）必须逐字到达用户：它是"我先不动手"的凭据，
                // 过一遍总结模型就会变成"好的，我来帮你执行"——上一轮实测就是这么翻车的。
                boolean verbatim = !plan.isToolCall()
                        && io.github.dolow.reconlens.chat.plan.IntentReview.question(message, call.intent()) != null;
                Reply first = dispatch(scope, plan, slots, message, tools, sink);
                Reply merged = plan.isToolCall()
                        ? first
                        : followUp(scope, sessionId, message, facts, call, first, tools, sink, executed);
                reply = verbatim ? merged : narrate(merged, call.intent(), message);
            }
            ChatSlots next = reply.slots().withIntent(executed.get().name());
            // 起草成功才记一条账本：这是"本会话产生过哪些规则版本"的唯一事实来源。
            recordDraftIfNeeded(session, sessionId, call, reply, null, userSeq);

            sessions.append(scope, sessionId, "assistant", reply.text(), envelope(call.intent(), tools));
            saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
            // 压摘要放在最后：它只影响"下一次"的上下文，这一轮的输出必须原样送出去
            summaries.maybeSummarize(scope, sessionId);
            emit(sink, reply, next, sessionId);
        } catch (SessionNotFoundException | SessionNotWritableException e) {
            // 权限问题在建轮次之前就抛了，此时还没有半截状态可回滚
            sink.accept(ChatEvent.error(e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("会话处理失败 session={} message={}", sessionId, message, e);
            sink.accept(ChatEvent.error(friendly(e)));
        }
    }

    /**
     * 规划器没给出结果时怎么办。
     *
     * <p>老路径当成 bug：词表规划器必须总能命中，命中不了说明代码坏了，500 比瞎答诚实。
     * 自由循环里它是一次真实的模型失败（超时、回了个坏 JSON、熔断），用户该听到一句话。
     */
    private io.github.dolow.reconlens.chat.plan.ChatPlan planFallback() {
        if (!mode.loop()) {
            throw new IllegalStateException("规划器必须给出结果");
        }
        log.warn("自由循环这一轮没拿到计划，回一句兜底话");
        return io.github.dolow.reconlens.chat.plan.ChatPlan.reply(
                "这次模型没给出可用的决定，你换个说法再试一次？", 0.3);
    }

    /**
     * 自由循环：做一步、看真实结果、再问模型下一步，直到它说够了、跳数用完、或撞上写动作回执。
     *
     * <p><b>它和 {@code followUp} 的区别是"谁在决定"</b>：{@code followUp} 问的是"还差一步吗"，
     * 门槛是只读、不重复、证据一致；这里问的是"现在最该做什么"，模型可以查列、可以反问、
     * 可以连续调两个只读工具。唯一不让步的是写：一到写动作就停，把回执交给用户。
     *
     * <p><b>为什么中间步骤只回灌给模型、不直接给用户</b>：一步的真实结果（整张表结构、一列差异）
     * 是下一跳的依据，不是用户要的答案；用户要看的是模型消化之后那一句。跳数用尽时模型没来得及
     * 总结，才把最后一步的原样抬出来兜底——那时"看到原始结果"也好过"什么都没看到"。
     *
     * @param executed 出参：最后真正执行的动作，槽位要记这个而不是第一跳的
     */
    private Reply loopTurn(SessionScope scope, String message, ContextFacts facts, List<String> history,
                           String contextText, io.github.dolow.reconlens.chat.plan.ChatPlan first,
                           ChatSlots startSlots, List<String> tools, Consumer<ChatEvent> sink,
                           java.util.concurrent.atomic.AtomicReference<ChatIntent> executed) {
        StringBuilder said = new StringBuilder();
        // 说给模型看的（每一跳的依据）和说给用户看的（模型最后的判断）不是一回事：
        // 实测里 if 全拼起来，用户会先看到两张表的完整结构、再看到一句结论。
        String modelSaid = null;
        String lastStepText = null;
        ChatSlots slots = startSlots;
        io.github.dolow.reconlens.chat.plan.ChatPlan plan = first;
        int hop = 0;
        int limit = Math.max(1, loopHops);
        String blockedWrite = null;
        while (true) {
            hop++;
            // 起草在 writeAction 里不算写（词表形态下它走收集器）；循环里没人替模型收参数，
            // 它一样是个"点了就要人点头"的动作，所以这里把它也算进来。
            boolean write = writeAction(plan)
                    || (plan.isToolCall() && ToolNames.WRITES.contains(plan.toolName()));
            // 模型带着"按订单、资源对账"这种半句话就来点 recon_draft_rule 时，不给用户回执：
            // 先把"被打回"告诉模型，让它自己决定是不是还缺东西。实测里直接给回执，用户只能
            // 顺着说"确认"，然后拿一句含糊描述去后台起草；说别的（"bill_left"）也只是再来一次
            // 回执——模型和用户都被卡在同一个地方。
            //
            // 只在"描述明显太薄"时这么做：写动作该拦还是要拦，回执不能因为模型话少就不给。
            String callKey = plan.isToolCall() ? plan.toolName() + plan.toolArgs() : null;
            boolean firstBlockedWrite = write && thinDraft(plan)
                    && !(callKey != null && callKey.equals(blockedWrite));
            Reply step;
            if (firstBlockedWrite) {
                blockedWrite = callKey;
                step = Reply.text("（系统：这条写动作没有执行——它需要用户点头。"
                        + "如果有参数或口径还没问清，趁现在问用户；问清了再调一次。）", slots);
            } else if (write) {
                // 写动作在循环里也只有一个去处：回执。不走 dispatchTool —— 那条路的
                // "已拦下"是防线，不是正常路径；正常路径里用户根本不该看到一次工具事件。
                ChatIntent writing = plan.call() == null ? ChatIntent.HELP : plan.call().intent();
                String summary = plan.isToolCall() ? describeTool(plan) : describeCall(plan.call());
                String text = narrator == null ? null : narrator.confirm(writing, summary, message);
                step = Reply.text(text == null ? defaultConfirm(summary) : text,
                        slots.withPending(CONFIRM_ACTION, pendingOf(plan, summary)));
            } else if (plan.isReply()) {
                step = Reply.text(plan.reply(), slots);
            } else {
                step = dispatch(scope, plan, slots, message, tools, sink);
            }
            slots = step.slots();
            executed.set(plan.call() == null ? ChatIntent.HELP : plan.call().intent());
            appendSaid(said, step.text());
            if (!firstBlockedWrite) {
                lastStepText = step.text();
            }
            if (plan.isReply()) {
                modelSaid = step.text();
            }
            boolean awaitingConfirm = CONFIRM_ACTION.equals(slots.pendingAction());
            if ((write && !firstBlockedWrite) || awaitingConfirm || plan.isReply() || hop >= limit) {
                break;
            }
            java.util.Optional<io.github.dolow.reconlens.chat.plan.ChatPlan> next =
                    planner.continueLoop(message, slots, facts, history, contextText, said.toString(), hop);
            if (next.isEmpty()) {
                break;
            }
            // 下一跳也是"模型给的判断"，用户要能看到——否则界面上只有第一跳的依据
            plan = next.get();
            sink.accept(ChatEvent.plan(plan));
            sink.accept(ChatEvent.intent(plan.call() == null ? ChatIntent.HELP : plan.call().intent()));
        }
        if (modelSaid == null && lastStepText != null && hop < limit
                && !CONFIRM_ACTION.equals(slots.pendingAction())) {
            // 模型查完就收手、没给结论：再要一句人话。多花一次调用，换掉一整张表的原始输出——
            // 实测里用户收到的是 bill_right 的六行列定义，而问题只是"资源指哪一列"。
            appendSaid(said, "（系统：查到这里够了。用一两句话把结论告诉用户，不要再调工具。）");
            java.util.Optional<io.github.dolow.reconlens.chat.plan.ChatPlan> wrap =
                    planner.continueLoop(message, slots, facts, history, contextText, said.toString(), hop);
            if (wrap.isPresent() && wrap.get().isReply()) {
                modelSaid = wrap.get().reply();
                sink.accept(ChatEvent.plan(wrap.get()));
            }
        }
        log.info("自由循环结束 hops={} chars={}", hop, said.length());
        // 模型自己说过话，就把它的判断给用户；它只查了没总结（跳数用尽），退回最后一步的输出，
        // 免得用户这边一片空白。中间的每一步都进了 transcript，下一跳的模型看得见。
        String visible = modelSaid != null ? modelSaid : lastStepText;
        if (visible == null || visible.isBlank()) {
            // 只有一种情况会走到这儿：第一拍就是一条被打回的薄写动作，模型也没接着说。
            visible = "这一步还差点信息——把要处理的表和要比的字段补一句？";
        }
        return Reply.text(visible == null ? "" : visible.strip(), slots);
    }

    private static void appendSaid(StringBuilder said, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (said.length() > 0) {
            said.append("\n\n");
        }
        said.append(text.strip());
    }

    /**
     * 表单提交：参数是**结构化**的，不过意图路由。
     *
     * <p>与 {@link #handle} 走同一个 {@link #dispatch}：表单和聊天是两条入口、一套动作实现。
     * 分成两套的话，"会话里跑一次"和"表单里跑一次"迟早会有一边忘了打槽位或者忘了闸门。
     */
    public Transcript submit(SessionScope scope, long sessionId, ChatIntentRouter.Call call) {
        ChatSession session = sessions.require(scope, sessionId);
        List<String> tools = new ArrayList<>();
        ChatSlots slots = readSlots(session);
        // 先记 user 轮次再执行：执行失败时历史里也要看得出"他点了什么"，
        // 否则失败只留在日志里，会话里那段是空白。
        sessions.append(scope, sessionId, "user", describe(call), envelope(call.intent(), List.of()));
        Reply reply = runGuarded(scope, call, slots, tools);
        ChatSlots next = reply.slots().withIntent(call.intent().name());
        sessions.append(scope, sessionId, "assistant", reply.text(), envelope(call.intent(), tools));
        boolean saved = saveSlotsWithRetry(scope, sessionId, session.stateRev(), next);
        summaries.maybeSummarize(scope, sessionId);
        return new Transcript(reply.text(), tools, next, saved);
    }

    /**
     * 表单入口的异常兜底：**把这个动作没做成的原因说出来，而不是回 500**。
     *
     * <p>实测发现的坑：对一条还在草稿的规则点「跑一次」，引擎抛的是 {@code RuleException}，
     * 一路冒到 Spring 变成 500 —— 用户看到的是"服务器错误"，而真实情况只是"这条规则还没生效"。
     * 聊天入口一直有这层兜底（SSE 已经开始推了，不能外抛），表单入口漏了。
     *
     * <p>只兜 {@link RuntimeException} 里的"业务性失败"；{@link SessionNotFoundException} /
     * {@link SessionNotWritableException} 必须原样抛——那是 404/403，不能变成 200 里的一句话。
     */
    private Reply runGuarded(SessionScope scope, ChatIntentRouter.Call call, ChatSlots slots, List<String> tools) {
        try {
            // 表单是"用户点名动作 + 填了参数"的一次提交，等价于已经确认过——
            // 再问一句"确认执行吗"只会让人点两次。聊天入口仍由 handle 里那道闸拦。
            return dispatch(scope, io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 0.9, "表单动作"),
                    slots, null, tools, event -> { }, true);
        } catch (SessionNotFoundException | SessionNotWritableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("表单动作 {} 执行失败：{}", call.intent(), e.toString());
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return Reply.text("这个动作没做成：" + reason, slots);
        }
    }

    /** 一次表单动作的结果。 */
    public record Transcript(String text, List<String> tools, ChatSlots slots, boolean slotsSaved) {
    }

    private void emit(Consumer<ChatEvent> sink, Reply reply, ChatSlots next, long sessionId) {
        for (String line : reply.text().split("\n")) {
            sink.accept(ChatEvent.delta(line + "\n"));
        }
        sink.accept(ChatEvent.slot(next));
        sink.accept(ChatEvent.done(sessionId));
    }

    /**
     * 写槽位：冲突就回读最新一份、合并后再写，最多 {@value #SLOT_SAVE_ATTEMPTS} 次。
     *
     * <p>冲突的来源：同一会话里两个人同时发消息（或一个人连发两条）。
     * <b>为什么不能只重试一次并且原样覆盖</b>：两个人同时说话时，A 刚把焦点切到任务 7，
     * B 这一轮还拿着旧 rev 和旧 jobId，直接覆盖等于把 A 的焦点抹掉——用户看到的是"刚说的它忘了"，
     * 而库里没有任何痕迹可查。
     *
     * <p>合并规则在 {@link ChatSlots#mergeWith(ChatSlots)}：本轮写过的字段覆盖，其余取最新，
     * 半句话（pending）以本轮为准。仍然冲突到第三次，说明吞吐已经超过"槽位"这个粒度的设计意图，
     * 留一行 WARN 比无限重试更诚实。
     */
    private boolean saveSlotsWithRetry(SessionScope scope, long sessionId, int rev, ChatSlots slots) {
        ChatSlots mine = slots;
        if (sessions.saveSlots(scope, sessionId, rev, writeSlots(mine))) {
            return true;
        }
        for (int attempt = 2; attempt <= SLOT_SAVE_ATTEMPTS; attempt++) {
            ChatSession fresh = sessions.require(scope, sessionId);
            // 回读最新再合并：直接盖掉最新那份，等于把别人刚切过去的焦点抹掉（用户看到的是"它忘了"）。
            mine = mine.mergeWith(readSlots(fresh));
            if (sessions.saveSlots(scope, sessionId, fresh.stateRev(), writeSlots(mine))) {
                log.info("槽位并发写入已重试成功 session={} rev={} attempt={}", sessionId, rev, attempt);
                return true;
            }
        }
        log.warn("槽位并发写冲突 {} 次仍未落库 session={} rev={}", SLOT_SAVE_ATTEMPTS, sessionId, rev);
        return false;
    }

    /**
     * 「第二步」：上一步是读动作、且开关打开时，问规划器还差不差一个动作。
     *
     * <p>三条硬约束写在这里，不写在提示词里（提示词是建议，这里是闸）：
     * <ol>
     *   <li>写动作不续跑——回执必须等用户说"确认"，续跑等于绕过回执；</li>
     *   <li>第二步也只能是读动作——就算模型硬给了一个 RUN，这里直接丢掉；</li>
     *   <li>第二步失败不影响第一步——已经拿到的结果照常回给用户。</li>
     * </ol>
     *
     * <p>叙述只做一次：合并后的文本在 {@link #handle} 里统一过 {@link #narrate}。
     * 分两次叙述会让用户听到两句"总结"，而第二句是在总结第一句的总结。
     */
    private Reply followUp(SessionScope scope, long sessionId, String message,
                           ContextFacts facts, ChatIntentRouter.Call call, Reply first,
                           List<String> tools, Consumer<ChatEvent> sink,
                           java.util.concurrent.atomic.AtomicReference<ChatIntent> executed) {
        if (call.intent().isWrite()) {
            return first;
        }
        io.github.dolow.reconlens.chat.plan.ChatPlan done =
                io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 1.0, "上一步");
        java.util.Optional<io.github.dolow.reconlens.chat.plan.ChatPlan> next =
                planner.continuePlan(message, first.slots(), facts, done, first.text());
        if (next.isEmpty()) {
            // 常见情况：模型判定这一步已经收尾，或两步环没开（词表规划器永远返回空）
            log.debug("两步环：没有第二步 session={} 第一步={}（模型判 done / 开关关闭 / 模型不可用）",
                    sessionId, call.intent());
            return first;
        }
        ChatIntent second = next.get().call().intent();
        if (second == ChatIntent.HELP || second.isWrite() || second == call.intent()) {
            log.debug("两步环：第二步被拒 session={} 第一步={} 第二步={} 原因={}", sessionId, call.intent(), second,
                    second == ChatIntent.HELP ? "模型给了 HELP"
                            : second.isWrite() ? "第二步是写动作（写动作用户得自己确认）" : "与第一步相同");
            return first;
        }
        log.info("两步环：接着做第二步 session={} {} → {}", sessionId, call.intent(), second);
        // 先推"接着做哪一步"，再执行：控制台上看得到这一轮其实发生了两次动作
        executed.set(second);
        sink.accept(ChatEvent.plan(next.get()));
        sink.accept(ChatEvent.intent(second));
        Reply extra;
        try {
            extra = dispatch(scope, next.get(), first.slots(), message, tools, sink);
        } catch (RuntimeException e) {
            log.warn("第二步没做成，已拿到的结果照常返回 session={} intent={}", sessionId, second, e);
            return first;
        }
        return Reply.text(first.text() + "\n\n———— 接着把下一步也做了：" + extra.text(), extra.slots());
    }

    /**
     * 把引擎原话换成"先结论、后明细"。只改正文，不动工具记录与槽位——
     * 审计看到的是工具真实返回，模型只影响"怎么读"。
     */
    private Reply narrate(Reply reply, ChatIntent intent, String message) {
        return narrate(reply, intent, message, false);
    }

    /**
     * @param verbatim 逐字返回，不过总结层、也不做 agent 尾巴清理。
     *                 只给两种回复用：模型自己写的 reply，和 {@code IntentReview} 的守卫话术——
     *                 后者是"我先不动手"的证据，改写它等于把证据换成了承诺。
     */
    private Reply narrate(Reply reply, ChatIntent intent, String message, boolean verbatim) {
        if (verbatim) {
            return reply;
        }
        // 带 YAML 骨架的回执是"待确认的正式文本"：改一个字段就是要落库的东西。
        // 实测里总结层给它添过一个用户没说过的"以 updated_at 为准"——结构化内容一律不许改写。
        if (reply.text() != null && reply.text().contains("```yaml")) {
            return reply;
        }
        // 规则清单已经是按 lifecycle 写好的人话；再交给总结模型只会把"调 recon_xxx"带回来
        if (intent == ChatIntent.LIST_RULES) {
            return reply;
        }
        if (narrator == null || !narrator.active()) {
            // 没有总结层时也要清掉写给宿主的「下一步：recon_xxx」——读者仍是人
            String clean = ChatSpeech.stripAgentTail(ChatSpeech.stripInternal(reply.text()));
            return clean.equals(reply.text()) ? reply : new Reply(clean, reply.toolJson(), reply.slots());
        }
        String text = narrator.decorate(intent, message, reply.text());
        if (text == null || text.equals(reply.text())) {
            return reply;
        }
        // 总结模型也可能把材料里的 recon_xxx / /api 路径原样带出来，出口再过一遍
        String clean = ChatSpeech.stripAgentTail(ChatSpeech.stripInternal(text));
        return new Reply(clean, reply.toolJson(), reply.slots());
    }

    /**
     * 澄清：一句短问句 + 就算选项。不打印整页帮助——用户要的是"你是指哪个"，不是操作手册。
     */
    private static String clarifyText(io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        StringBuilder sb = new StringBuilder("没太确定你指的是哪件事");
        if (!plan.reason().isBlank()) {
            sb.append("（").append(plan.reason()).append("）");
        }
        sb.append("。点下面的选项，或者说得更具体一点：\n");
        for (String option : plan.options()) {
            sb.append("· ").append(labelOf(option)).append('\n');
        }
        return sb.toString();
    }

    /** 动作名 → 中文说法。认不出就原样显示，别让一个未知动作把澄清整段吞掉。 */
    private static String labelOf(String action) {
        try {
            return ChatIntent.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT)).form();
        } catch (RuntimeException e) {
            return action;
        }
    }

    // ---------------------------------------------------------------- 草稿账本

    /** 「第一条 / 第二个」：按本会话账本的创建顺序取。 */
    private static final java.util.regex.Pattern ORDINAL =
            java.util.regex.Pattern.compile("第\\s*([一二三四五六七八九十\\d]{1,3})\\s*(条|个)");

    /**
     * 起草成功后记一条账本。这是"这个会话产生过哪些规则版本"的唯一事实来源。
     *
     * <p>写失败只记日志：账本是辅助索引，不能因为它写不进去，把一次已经成功的起草变成错误。
     */
    private void recordDraftIfNeeded(ChatSession session, long sessionId, ChatIntentRouter.Call call, Reply reply,
                                     String userWords, int userSeq) {
        if (call == null || call.intent() != ChatIntent.DEFINE_RULE
                || reply == null || reply.slots().hasPending()) {
            return;
        }
        String code = reply.slots().ruleCode();
        if (code == null || sessionId <= 0) {
            return;
        }
        String operator = session == null ? null : session.operator();
        try {
            // 注意：不能用 catalog.latestIfPresent —— 它只认 ACTIVE，而刚起草的是 DRAFT，
            // 那样账本会永远是空的（实测踩过）。要按"任意状态的最新一版"取。
            List<RuleRepository.RuleRow> versions = catalog.versions(code);
            if (versions.isEmpty()) {
                return;
            }
            RuleRepository.RuleRow latest = versions.stream()
                    .max(Comparator.comparingInt(RuleRepository.RuleRow::version))
                    .orElseThrow();
            RuleSpec spec = catalog.parseSnapshot(latest.specJson(), "账本草稿");
            String label = labelOfSpec(spec);
            if (userWords != null && !userWords.isBlank()) {
                // 原话也存：规则体里是 order_no，用户嘴里是"订单"，两者都要能对上
                label = label + "，用户说：" + userWords;
            }
            chatDrafts.record(sessionId, code, latest.version(), label);
            // 同一编码后起草的那些参数，覆盖的应当是同一主题：不能记成两条各说各的。
            // 主题用编码而不是"第 N 条"——用户改的是这条规则的参数，不是"那次起草"。
            claims.record(sessionId, operator, "draft:" + code, label, userSeq,
                    io.github.dolow.reconlens.store.ChatClaimRepository.AUTHORITY_DRAFT);
        } catch (RuntimeException e) {
            log.warn("草稿账本写入失败 code={}：{}", code, e.toString());
        }
    }

    /** 一句话描述一条规则，给「那条按订单和状态对账的」这类指代当锚点。 */
    private static String labelOfSpec(RuleSpec spec) {
        String key = "?";
        if (spec.left() != null && spec.left().keyField() != null) {
            key = spec.left().keyField();
        } else if (spec.left() != null && !spec.left().dimensionColumns().isEmpty()) {
            key = String.join("+", spec.left().dimensionColumns());
        }
        String compare = spec.compare().stream().map(FieldRule::field)
                .collect(java.util.stream.Collectors.joining("、"));
        // 表名也要写进去：用户经常说「那条 bill_left 对 bill_right 的」
        String tables = (spec.left() == null || spec.left().table() == null ? "?" : spec.left().table())
                + "→"
                + (spec.right() == null || spec.right().table() == null ? "?" : spec.right().table());
        return tables + "，维度 " + key + "，比 " + compare;
    }

    /** 「第 N 条」里的 N；没有就返回 -1。 */
    static int draftIndex(String message) {
        if (message == null) {
            return -1;
        }
        java.util.regex.Matcher matcher = ORDINAL.matcher(message);
        return matcher.find() ? ordinalValue(matcher.group(1)) : -1;
    }

    /** 把「第 N 条」解析成账本里的具体 (code, version)。只有唯一命中才用。 */
    private ChatIntentRouter.Call applyDraftReference(long sessionId, ChatIntentRouter.Call call, String message) {
        if (call == null || call.ruleCode() != null) {
            return call;
        }
        int index = draftIndex(message);
        java.util.List<io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft> drafts =
                chatDrafts.list(sessionId);
        if (index >= 1 && index <= drafts.size()) {
            io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft draft = drafts.get(index - 1);
            return new ChatIntentRouter.Call(call.intent(), draft.ruleCode(), call.jobId(), call.diffId(),
                    call.diffType(), call.ruleBody(), call.table());
        }
        // 没写"第几条"或条数越界，都继续往下试描述匹配 ——
        // 早先这里对 index<1 直接 return，结果"那条订单对状态的"永远匹配不上（实测踩过）。
        // 描述匹配：「那条按订单和状态对账的」——按 label 打分，只有唯一最高分才用。
        if (!needsRuleCode(call.intent()) || drafts.size() < 2) {
            return call;
        }
        io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft best = null;
        int bestScore = 0;
        boolean tie = false;
        for (io.github.dolow.reconlens.store.ChatDraftRepository.ChatDraft draft : drafts) {
            int score = matchScore(message, draft.label());
            if (score > bestScore) {
                bestScore = score;
                best = draft;
                tie = false;
            } else if (score == bestScore && score > 0) {
                tie = true;
            }
        }
        if (best == null || tie || bestScore < 2) {
            // 打平或分数太低就不猜：宁可反问，也不要改错规则版本
            return call;
        }
        return new ChatIntentRouter.Call(call.intent(), best.ruleCode(), call.jobId(), call.diffId(),
                call.diffType(), call.ruleBody(), call.table());
    }

    /** 中文里的高频虚词格：它们出现在任何一句话里，拿它们匹配等于没匹配。 */
    private static final java.util.Set<String> STOP_GRAMS = java.util.Set.of(
            "对账", "规则", "一下", "这个", "那个", "帮我", "给我", "看看", "的是", "是的");

    /**
     * 描述匹配打分：英文词（表名/列名）权重 2，中文二元组权重 1。
     *
     * <p>为什么中文要按二元组：没有分词器，而业务词大多是两字词（订单、状态、金额）。
     * 停用格先剔掉，否则"对账"两个字会让每条草稿都命中。
     */
    static int matchScore(String message, String label) {
        if (message == null || label == null || label.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : label.split("[^A-Za-z0-9_]+")) {
            if (token.length() > 2 && message.contains(token)) {
                score += 2;
            }
        }
        int at = label.indexOf("用户说：");
        String words = at >= 0 ? label.substring(at + 4) : "";
        String cjk = words.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 2 <= cjk.length(); i++) {
            String gram = cjk.substring(i, i + 2);
            if (!STOP_GRAMS.contains(gram) && message.contains(gram)) {
                score++;
            }
        }
        return score;
    }

    /** 这些动作才吃 ruleCode；别的动作拿它没用，硬填只会污染槽位。 */
    private static boolean needsRuleCode(ChatIntent intent) {
        return switch (intent) {
            case RUN, RETRY, BUILD_MODEL, TRIAL_STATUS -> true;
            default -> false;
        };
    }

    /** 一 / 二 / … / 十二 / "3" 都能读。 */
    static int ordinalValue(String token) {
        String text = token == null ? "" : token.trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            // 继续按中文数字走
        }
        int value = 0;
        for (char c : text.toCharArray()) {
            int digit = switch (c) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '十' -> 10;
                default -> 0;
            };
            if (digit == 0) {
                return 0;
            }
            if (digit == 10) {
                value = value == 0 ? 10 : value * 10;
            } else {
                value = value == 0 ? digit : value + digit;
            }
        }
        return value;
    }

    // ---------------------------------------------------------------- 两段式：写操作确认

    /** 一次"已确认"的执行：正文 + 它实际执行的动作（轮次信封要用）。 */
    private record Confirmed(Reply reply, ChatIntent intent) {
    }

    /**
     * 两段式的第二段：判断这句话是"确认"还是"取消"，并把上一轮暂存的动作真的执行掉。
     *
     * <p>返回 {@code null} 表示这句话两者都不是 —— 交给正常路由处理。
     * 这一点很重要：用户在确认前改主意说「等等，先看一下差异」，不能被当成"确认"，
     * 也不能被待确认状态挡住。
     */
    /**
     * 这句话是不是一句干脆的取消。
     *
     * <p>三条都要满足：不能太长、不能带疑问标记、句首就是否定词。
     * 「不是三个维度吗」这种反问被排除在取消之外——用户是在质疑理解，不是撤单。
     */
    static boolean isCancel(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.strip();
        if (t.length() > 12) {
            return false;
        }
        if (QUESTION.matcher(t).find()) {
            return false;
        }
        return NEGATE.matcher(t).find();
    }

    /** 这句话是不是在指"刚才/上面"那一段——本会话没有它，就说明上下文断了。 */
    private static boolean refersToEarlier(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return java.util.regex.Pattern.compile("刚才|刚刚|上一条|上面|前面那|那个|那次|继续|接着")
                .matcher(text).find();
    }

    /** 回执里引用用户原话时截短，别把一整段 YAML 贴回去。 */
    private static String brief(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }

    private Confirmed handleConfirm(SessionScope scope, long sessionId, ChatSlots slots, String message,
                                    List<String> tools, Consumer<ChatEvent> sink, int userSeq) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isCancel(text)) {
            // 「不是，是 CUSTOMER_STATUS_RECON」：这是在改主意，不是在取消
            if (ruleCodeMentioned(text) != null) {
                return null;
            }
            return new Confirmed(Reply.text("好，这次不做；刚才准备的操作已经取消。", slots.clearPending()),
                    ChatIntent.HELP);
        }
        if (!AFFIRM.matcher(text).find()) {
            return null;
        }
        // 确认之后要还原的可能是两种东西：老路径的 Call，或者模型点名的工具。
        // 工具必须连参数一起还原——回执只问了"要不要做"，参数是上一轮模型给的，
        // 用户确认的是那组参数，不能确认完再让工具自己去猜。
        io.github.dolow.reconlens.chat.plan.ChatPlan plan;
        ChatIntentRouter.Call call;
        String pendingTool = slots.pendingArgs().get("tool");
        if (pendingTool != null && !pendingTool.isBlank()) {
            Map<String, String> toolArgs = readToolArgs(slots.pendingArgs().get("toolArgs"));
            call = io.github.dolow.reconlens.chat.plan.ToolCallPlanner.callFor(pendingTool, toolArgs);
            plan = io.github.dolow.reconlens.chat.plan.ChatPlan.tool(call, pendingTool, toolArgs, 0.95,
                    "已确认：" + slots.pendingArgs().getOrDefault("summary", "执行 " + pendingTool),
                    io.github.dolow.reconlens.chat.plan.ChatPlan.Evidence.CONSISTENT);
        } else {
            call = callOf(slots.pendingArgs());
            if (call == null) {
                return new Confirmed(Reply.text("这个操作我记不清了，请再说一遍要做什么。", slots.clearPending()),
                        ChatIntent.HELP);
            }
            // 让用户看到"确认的到底是哪一件"，再动手
            plan = io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 0.95,
                    "已确认：" + slots.pendingArgs().getOrDefault("summary", describeCall(call)));
        }
        sink.accept(ChatEvent.plan(plan));
        sink.accept(ChatEvent.intent(call.intent()));
        Reply reply = dispatch(scope, plan, slots.clearPending(), text, tools, sink, true);
        recordDraftIfNeeded(sessionOrNull(scope, sessionId), sessionId, call, reply,
                slots.pendingArgs().get("spoken"), userSeq);
        return new Confirmed(Reply.text(reply.text(), reply.slots().clearPending()), call.intent());
    }

    /** 从槽位里把上一轮暂存的动作还原出来。 */
    private static ChatIntentRouter.Call callOf(Map<String, String> args) {
        try {
            ChatIntent intent = ChatIntent.valueOf(args.getOrDefault("intent", ""));
            Long jobId = args.get("jobId") == null ? null : Long.parseLong(args.get("jobId"));
            Long diffId = args.get("diffId") == null ? null : Long.parseLong(args.get("diffId"));
            return new ChatIntentRouter.Call(intent, args.get("ruleCode"), jobId, diffId, null,
                    args.get("body"), args.get("table"));
        } catch (RuntimeException e) {
            log.debug("暂存的动作还原失败 args={}", args, e);
            return null;
        }
    }

    private static Map<String, String> pendingOf(ChatIntentRouter.Call call, String summary) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("intent", call.intent().name());
        if (call.ruleCode() != null) {
            args.put("ruleCode", call.ruleCode());
        }
        if (call.jobId() != null) {
            args.put("jobId", String.valueOf(call.jobId()));
        }
        if (call.diffId() != null) {
            args.put("diffId", String.valueOf(call.diffId()));
        }
        if (call.ruleBody() != null) {
            args.put("body", call.ruleBody());
        }
        if (call.table() != null) {
            args.put("table", call.table());
        }
        args.put("summary", summary);
        return args;
    }

    /**
     * 工具版回执：除了 {@link #pendingOf(ChatIntentRouter.Call, String)} 的字段，还记下工具名与参数。
     *
     * <p>参数用的是模型给的那一份，原样存成 JSON。确认之后不再重算——重算等于让工具
     * 自己再猜一遍参数，"确认"就名不副实了。
     */
    private Map<String, String> pendingOf(io.github.dolow.reconlens.chat.plan.ChatPlan plan, String summary) {
        Map<String, String> args = pendingOf(plan.call(), summary);
        if (!plan.isToolCall()) {
            return args;
        }
        try {
            args.put("toolArgs", json.writeValueAsString(plan.toolArgs()));
            args.put("tool", plan.toolName());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 存不下参数就别存工具名：按 Call 还原虽然会丢参数，但至少不会"确认了却调错"
            log.warn("暂存工具参数失败 tool={}", plan.toolName(), e);
        }
        return args;
    }

    /**
     * 回执文案里的"这一步要做什么"。
     *
     * <p>不能报工具名：{@code recon_draft_rule} 是给宿主大模型看的注册名，人看到的是
     * "执行 recon_draft_rule"这种内部术语（实测用户当场问"这是什么"）。工具的意图映射能为空
     * （Agent 系工具没有对应枚举），所以这里留一张中文话术表，认不出的工具退化成不带名字的一句。
     */
    private static String describeTool(io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        ChatIntentRouter.Call call = plan.call();
        if (call != null && call.intent() != ChatIntent.HELP) {
            return describeCall(call);
        }
        String ruleCode = plan.toolArgs().get("ruleCode");
        String table = plan.toolArgs().get("table");
        String tail = ruleCode != null ? " " + ruleCode : (table != null ? " " + table : "");
        return switch (plan.toolName()) {
            case ToolNames.DRAFT_RULE -> "起草一条对账规则";
            case ToolNames.RUN -> "跑一次对账" + tail;
            case ToolNames.SUBMIT_APPROVAL -> "把这条草稿提交审批";
            case ToolNames.REGISTER_TABLE -> "把一张表登记进对账范围" + tail;
            case ToolNames.IMPORT_FILE -> "导入一个数据文件" + tail;
            case ToolNames.TRIAL_STATUS -> "看起草与试算的进度";
            default -> "做这一步";
        };
    }

    /** 回执里存下来的参数（JSON 对象）。坏数据当空参数，由工具自己校验并报错。 */
    private Map<String, String> readToolArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("暂存的工具参数读不出来 raw={}", raw, e);
            return Map.of();
        }
    }

    /** 一句话说清楚"这一下会做什么"（模型挂了也能用的兜底）。 */
    private static String describeCall(ChatIntentRouter.Call call) {
        return switch (call.intent()) {
            case RUN -> "跑一次对账" + (call.ruleCode() == null ? "" : " " + call.ruleCode());
            case RETRY -> "重跑任务" + (call.jobId() == null ? "" : " #" + call.jobId());
            case BUILD_MODEL -> "给 " + (call.ruleCode() == null ? "这条规则" : call.ruleCode()) + " 建预处理模型";
            case DEFINE_RULE -> "起草一条新规则";
            default -> call.intent().form();
        };
    }

    private static String defaultConfirm(String summary) {
        return "我理解你要" + summary + "，确认执行吗？（回「确认」或「取消」）";
    }

    /**
     * 模型给的起草描述是不是"太薄"——只有意图，没有表、键、字段。
     *
     * <p>只用来决定"这一拍要不要先把话退给模型"，不是参数校验：真正的校验在 Agent 的闸门里。
     * 40 这个数取的是"一句话里至少说清了对哪两张表、按什么键"——实测里
     * 「按订单、资源对账」是 9 个字，模型补完表的问法之后回的是 60 多字的结构化描述。
     */
    private static boolean thinDraft(io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        if (!plan.isToolCall() || !ToolNames.DRAFT_RULE.equals(plan.toolName())) {
            return false;
        }
        String desc = plan.toolArgs().get("businessDescription");
        return desc != null && desc.strip().length() < 40;
    }

    /**
     * 写动作的统一判据（工具形态与意图形态共用）。
     *
     * <p>参数没齐时不回执——那正是要一句句问的时候，先问参数再问确认，不能问两遍。
     * 参数齐了就一律回执，**不看用户原话里有没有点名规则**：那句话在工具形态下照样会回执，
     * 两种形态必须给人同一种回答，否则"问不问"变成模型今天选哪种 JSON 的运气。
     *
     * <p>起草是例外，它不是一次动作：先要把两张表、业务键、比对字段问清楚。那种"问"由
     * {@code draftStep} 自己带着（收齐后给出骨架再请确认），所以这里不提前拦一道。
     */
    private static boolean writeAction(io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        if (plan.isToolCall()) {
            return !ToolNames.DRAFT_RULE.equals(plan.toolName())
                    && ToolNames.WRITES.contains(plan.toolName());
        }
        return plan.call() != null && writeReady(plan.call());
    }

    /** 意图形态的"参数齐了"：缺参时交给各分支自己的追问，不在这里拦。 */
    private static boolean writeReady(ChatIntentRouter.Call call) {
        if (!call.intent().isWrite()) {
            return false;
        }
        return switch (call.intent()) {
            case RUN, BUILD_MODEL -> call.ruleCode() != null;
            case RETRY -> call.jobId() != null;
            // 还没收齐参数时不能确认——那正是要一句句问的时候。
            // 收齐之后由 draftStep 自己生成骨架再问确认（那里才知道要落什么）。
            case DEFINE_RULE -> call.ruleBody() != null && !call.ruleBody().isBlank();
            default -> false;
        };
    }

    /**
     * 最近几轮**对话**（从旧到新，用户与助手都在），只给自由循环用。
     *
     * <p>助手那侧只留前 120 字：它是"我上一轮说了什么"，用来避免同一个问题问两遍、
     * 同一张表查两遍；整段工具输出留在当轮的 transcript 里就够了，跨轮再带一遍纯属烧 token。
     */
    private List<String> recentDialogue(SessionScope scope, long sessionId, int limit) {
        try {
            List<ChatTurn> turns = sessions.turns(scope, sessionId);
            List<String> out = new ArrayList<>();
            int seenUser = 0;
            for (int i = turns.size() - 1; i >= 0 && seenUser < limit; i--) {
                ChatTurn turn = turns.get(i);
                String content = turn.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                if ("user".equals(turn.role())) {
                    seenUser++;
                    out.add(0, "用户：" + content);
                } else if ("assistant".equals(turn.role())) {
                    String clipped = content.strip();
                    if (clipped.length() > 120) {
                        clipped = clipped.substring(0, 120) + "…";
                    }
                    out.add(0, "助手：" + clipped);
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("读会话历史失败 session={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 最近几轮用户原话（从旧到新），给规划器当"指代可指的东西"。
     *
     * <p>只取 {@code role=user}：工具结果是"答案"，不是"意图线索"，塞进去只会把 prompt 撑爆；
     * 而"它/那单/刚才那个"要找的，永远是用户自己说过的话。
     */
    private List<String> recentUserMessages(SessionScope scope, long sessionId, int limit) {
        try {
            List<ChatTurn> turns = sessions.turns(scope, sessionId);
            List<String> out = new ArrayList<>();
            for (int i = turns.size() - 1; i >= 0 && out.size() < limit; i--) {
                ChatTurn turn = turns.get(i);
                if ("user".equals(turn.role()) && turn.content() != null && !turn.content().isBlank()) {
                    out.add(0, turn.content());
                }
            }
            return out;
        } catch (RuntimeException e) {
            // 历史读不出来不该让这一轮挂掉：没有历史只是"指代差一点"，不是"答不了"
            log.debug("读会话历史失败 session={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 规划器的"上下文"一格 = 压缩过的历史 + 此刻还算数的结论。
     *
     * <p>顺序是刻意的：摘要先说"过去发生过什么"，结论板再说"现在按哪句"。
     * 没有摘要时结论板单独成段；两者都没有时返回 {@code null}，与原来"没有摘要"等价。
     */
    private static String composeContext(String summary, String claimBlock, String sharedBlock) {
        String s = summary == null ? "" : summary.trim();
        String c = claimBlock == null ? "" : claimBlock.trim();
        String h = sharedBlock == null ? "" : sharedBlock.trim();
        StringBuilder sb = new StringBuilder();
        if (!s.isEmpty()) {
            sb.append(s);
        }
        if (!c.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(c);
        }
        if (!h.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(h);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 本轮要查的历史结论作用域：当前规则、当前表，以及这句话里点到的规则编码。
     *
     * <p>作用域只从"系统已经确认过的焦点"和"字面上的编码"来：宁可少带，
     * 不要按相似度把不相干的规则结论塞进来——塞错一条比不塞更糟。
     */
    private String sharedBlock(ChatSession session, ChatSlots slots, String message) {
        if (!claims.sharedEnabled() || session.operator() == null || session.operator().isBlank()) {
            return "";
        }
        java.util.LinkedHashSet<String> scopes = new java.util.LinkedHashSet<>();
        if (slots.ruleCode() != null) {
            scopes.add("rule:" + slots.ruleCode());
        }
        if (slots.table() != null) {
            scopes.add("table:" + slots.table());
        }
        if (message != null) {
            java.util.regex.Matcher m = RULE_CODE_IN_TEXT.matcher(message);
            while (m.find() && scopes.size() < 3) {
                scopes.add("rule:" + m.group());
            }
        }
        if (scopes.isEmpty()) {
            return "";
        }
        return claims.renderShared(session.operator(), new java.util.ArrayList<>(scopes), session.id());
    }

    /** 本轮要动的那条规则，历史结论里有没有两种同级不同值的说法。 */
    private ChatClaims.Conflict conflictFor(ChatSession session, ChatIntentRouter.Call call) {
        if (!claims.sharedEnabled() || session == null || call == null || call.ruleCode() == null) {
            return null;
        }
        if (call.intent() != ChatIntent.RUN && call.intent() != ChatIntent.BUILD_MODEL
                && call.intent() != ChatIntent.DEFINE_RULE) {
            return null;
        }
        return claims.conflict(session.operator(), "rule:" + call.ruleCode(), session.id());
    }

    /**
     * 用户在"历史口径二选一"里挑了第一个/第二个。
     *
     * <p>挑完记一条 {@code OPERATOR_STATED}：从这一刻起，这个作用域里这个主题以它为准；
     * 别的会话再问到同一件事，看到的也是这一条——它比草稿高一级，但压不过审批过的结论。
     */
    private Reply resolvePick(ChatSession session, ChatSlots slots, String message, int userSeq) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String text = message.trim();
        boolean first = PICK_FIRST.matcher(text).find();
        boolean second = PICK_SECOND.matcher(text).find();
        if (first == second) {
            // 两个都中（"不是第一个是第二个"）或都不中：交给正常路由，别猜
            return null;
        }
        String value = slots.pendingArgs().get(first ? "a" : "b");
        String key = slots.pendingArgs().get("key");
        if (value == null || key == null) {
            return Reply.text("我记不清刚才问的是哪两样了，请再说一遍要按哪个口径。", slots.clearPending());
        }
        String operator = session == null ? null : session.operator();
        long sessionId = session == null ? 0L : session.id();
        claims.record(sessionId, operator, key, value, userSeq,
                io.github.dolow.reconlens.store.ChatClaimRepository.AUTHORITY_STATED);
        return Reply.text("记下了：" + value + "。之后按这个口径走，同一操作人的其他会话也算数。",
                slots.clearPending());
    }

    /** 取会话，取不到就当没有（只影响"这条结论能不能跨会话"）。 */
    private ChatSession sessionOrNull(SessionScope scope, long sessionId) {
        try {
            return sessions.require(scope, sessionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 上一轮问了"要跑哪条规则"，这一轮用户只回了一个编码 —— 那是答案，不是新指令。
     *
     * <p>只对<b>写动作</b>做续接（跑一次 / 起草），且必须有确定性的证据：
     * 规则编码能在规则库里找到、或者起草参数能被解析出来。找不到就当没续接，
     * 退回正常路由——宁可多问一句，也不要拿"看起来像"的东西去执行。
     */
    private ChatIntentRouter.Call resumePending(ChatIntentRouter.Call call, ChatSlots slots, String message,
                                                List<String> tools) {
        if (!slots.hasPending() || message == null || message.isBlank()) {
            return call;
        }
        if (DraftIntake.ACTION.equals(slots.pendingAction())) {
            // 上一轮在问"左侧用哪张表"，这一句却没被识别成任何动作 —— 那就是在回答它
            // （「就这两个吧」「bill_left」「按订单号连」都长这样）。
            // 只在 HELP 时接管：说「有哪些规则」这种明确换了话题的句子不抢。
            if (call.intent() == ChatIntent.HELP) {
                return ChatIntentRouter.Call.of(ChatIntent.DEFINE_RULE);
            }
            // 模型有时把"左 bill_left"读成"看 bill_left 这张表"（只读动作）。
            // 但上一轮问的是起草参数，只可能由起草来回答：只要能解析出参数就继续起草，
            // 别让只读意图把半截草稿顶掉。短句 + 解析出参数才接管，避免抢走真正的问题。
            if (call.intent() != ChatIntent.DEFINE_RULE && message.trim().length() <= 24) {
                List<String> tables = tableNames(call(ToolNames.LIST_TABLES, java.util.Map.of(), tools).text());
                if (!DraftIntake.parse(message, tables, null).toMap().isEmpty()) {
                    return ChatIntentRouter.Call.of(ChatIntent.DEFINE_RULE);
                }
            }
            // 「确认」这种短答：参数还没收齐，没有可确认的动作，应该继续问缺的那一项，
            // 而不是让模型把它猜成"审批"。
            String trimmed = message == null ? "" : message.trim();
            if (trimmed.length() <= 6 && AFFIRM.matcher(trimmed).find()) {
                return ChatIntentRouter.Call.of(ChatIntent.DEFINE_RULE);
            }
            return call;
        }
        if (ChatIntent.RUN.name().equals(slots.pendingAction())) {
            String code = ruleCodeMentioned(message);
            if (code != null) {
                return ChatIntentRouter.Call.of(ChatIntent.RUN).withRuleCode(code);
            }
        }
        if (ChatIntent.DESCRIBE_TABLE.name().equals(slots.pendingAction())) {
            // 刚问过"看哪张表的字段"，这一句如果就是一个裸表名，那就是答案。
            // 只认"整句就是一个标识符"：说"字段"或说一整句话都不算，免得把别的问题当表名。
            String trimmed = message.trim();
            if (trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return ChatIntentRouter.Call.of(ChatIntent.DESCRIBE_TABLE).withTable(trimmed);
            }
        }
        return call;
    }

    /** 这句话有没有给起草补上新参数（表名、列名、业务键都算）。比的是"和上一轮比变了没有"。 */
    private boolean parsesAsDraftAnswer(String message, ChatSlots slots, List<String> tools) {
        if (message == null || message.isBlank()) {
            return false;
        }
        try {
            List<String> tables = tableNames(call(ToolNames.LIST_TABLES, java.util.Map.of(), tools).text());
            DraftIntake.Hints prior = DraftIntake.Hints.from(slots.pendingArgs());
            return !DraftIntake.parse(message, tables, prior).equals(prior);
        } catch (RuntimeException e) {
            // 读表失败不该把这一轮变成"抢话题"：按"没补上参数"处理，走模型那条路
            log.debug("起草参数试探失败 message={}", message, e);
            return false;
        }
    }

    /** 这句话里提到的、规则库真实存在的规则编码。 */
    private String ruleCodeMentioned(String message) {
        String upper = message.toUpperCase(java.util.Locale.ROOT);
        try {
            for (String code : catalog.codes()) {
                if (upper.contains(code.toUpperCase(java.util.Locale.ROOT))) {
                    return code;
                }
            }
        } catch (RuntimeException e) {
            log.debug("规则库不可读，跳过续接判定", e);
        }
        return null;
    }

    private Reply dispatch(SessionScope scope, io.github.dolow.reconlens.chat.plan.ChatPlan plan, ChatSlots slots,
                           String message, List<String> tools, Consumer<ChatEvent> sink) {
        return dispatch(scope, plan, slots, message, tools, sink, false);
    }

    /**
     * @param confirmed 这次调用是用户点头之后发起的（确认回执的下半程）。只有它能为真，
     *                  写工具的防线才会让路——否则"确认"会被再要一次确认，套娃。
     */
    private Reply dispatch(SessionScope scope, io.github.dolow.reconlens.chat.plan.ChatPlan plan, ChatSlots slots,
                           String message, List<String> tools, Consumer<ChatEvent> sink, boolean confirmed) {
        // 工具是先于意图的一等动作：模型点了哪个工具就执行哪个，不再反推成枚举再走固定分支。
        // 这条分支放在守卫之前是有意的——守卫是"词表既然分不清这是问还是做"的补丁，
        // 而模型已经用 name+args 说清楚了要做什么，再拿它去拦只会把合法的工具调用变成反问。
        if (plan.isToolCall()) {
            // 起草不是一个"点一下就完"的工具：一句"按订单、资源对账"里没有表、没有键，
            // 直接开后台起草只会拿一份猜出来的骨架去试算。词表形态下模型点了 recon_draft_rule，
            // 就把它接到同一条收集路径（defineRule → draftStep）：先问表、再翻列，
            // 翻不出来的字段点名问，收齐了把骨架摆出来请确认。
            //
            // 自由循环里不接：那头是模型自己在收参数（它自己查列、自己问）。
            // 两条状态机同时跑，实测会互相踩——模型问「资源是哪一列」、收集器同时问
            // 「左侧用哪张表」，用户看到两个声音；用户回给模型的那句又进不了收集器。
            if (!mode.loop() && ToolNames.DRAFT_RULE.equals(plan.toolName())) {
                ChatIntentRouter.Call draft = new ChatIntentRouter.Call(
                        ChatIntent.DEFINE_RULE, null, null, null, null, null, null);
                return defineRule(scope, draft, slots, message, tools, sink);
            }
            return dispatchTool(plan, slots, tools, sink, confirmed);
        }
        if (plan.isReply()) {
            return Reply.text(plan.reply(), slots);
        }
        ChatIntentRouter.Call call = plan.call();
        // 意图形态的写动作也要停在回执：这里的 execute 分支是"确认之后"才应该走到的地儿。
        // 与 dispatchTool 里的那道一样，它是防线而不是入口——入口在 handle 里已经拦了，
        // 但第二步、表单、以及其他调用方都从这里进，漏一个就是"没点头就跑了"。
        if (!confirmed && writeReady(call)) {
            log.warn("意图写动作绕过了回执，已拦下 intent={} ruleCode={} jobId={}",
                    call.intent(), call.ruleCode(), call.jobId());
            String summary = describeCall(call);
            return Reply.text(defaultConfirm(summary),
                    slots.withPending(CONFIRM_ACTION, pendingOf(plan, summary)));
        }
        // 执行前的事实复核：把"这是问我、还是要我做"这一步从词表/模型手里拿回来。
        // 挡在这里是因为它是所有执行路径的唯一收口（主路径、第二步、表单、确认后执行都走它）。
        String question = io.github.dolow.reconlens.chat.plan.IntentReview.question(message, call.intent());
        if (question != null) {
            return Reply.text(question, slots);
        }
        return switch (call.intent()) {
            case HELP -> Reply.text(helpText(), slots);
            case LIST_RULES -> listRules(slots, tools, sink, message);
            case LIST_JOBS -> listJobs(slots, tools, sink);
            case LIST_DATA -> listData(slots, tools, sink);
            case DESCRIBE_TABLE -> describeTable(call, slots, message, tools, sink);
            case DEFINE_RULE -> defineRule(scope, call, slots, message, tools, sink);
            case RUN -> run(call, slots, tools, sink);
            case JOB_SUMMARY -> jobSummary(call, slots, tools, sink);
            case LIST_DIFFS -> listDiffs(call, slots, tools, sink);
            case EXPLAIN_JOB -> explainJob(call, slots, tools, sink);
            case EXPLAIN_DIFF -> explainDiff(call, slots, tools, sink);
            case RETRY -> retry(call, slots, tools, sink);
            case BUILD_MODEL -> buildModel(scope, call, slots, tools, sink);
            case TRIAL_STATUS -> trialStatus(call, slots);
            case APPROVE -> Reply.text(approveText(call, slots), slots);
        };
    }

    /**
     * 模型直接点名的工具：查名字、执行、把工具原话当正文。
     *
     * <p>与各动作分支的区别只有一点：<b>没有中间那层"意图 → 固定实现"的映射</b>。
     * 分支里每句话都是手写的参数拼装（"用槽位里的 ruleCode 补"之类），工具路径的参数
     * 全部来自模型，所以这里不做任何补全——它说什么就调什么，剩下的交给工具自己的校验。
     */
    private Reply dispatchTool(io.github.dolow.reconlens.chat.plan.ChatPlan plan, ChatSlots slots,
                               List<String> tools, Consumer<ChatEvent> sink, boolean confirmed) {
        String name = plan.toolName();
        java.util.Map<String, String> args = plan.toolArgs();
        // 防线，不是入口：写动作在 handle 里已经变成回执了。真走到这里，说明有路径漏了闸，
        // 宁可再要一次确认，也不能在没人点头的时候改数据。
        if (!confirmed && ToolNames.WRITES.contains(name)) {
            log.warn("工具写动作绕过了回执，已拦下 tool={} args={}", name, args);
            String summary = describeTool(plan);
            sink.accept(ChatEvent.tool(name, "已拦下：写动作要先确认"));
            return Reply.text(defaultConfirm(summary),
                    slots.withPending(CONFIRM_ACTION, pendingOf(plan, summary)));
        }
        java.util.Optional<io.github.dolow.reconlens.tool.ToolSpec> spec = registry.find(name);
        if (spec.isEmpty()) {
            log.warn("模型点了一个不存在的工具 tool={}", name);
            sink.accept(ChatEvent.tool(name, "没有这个工具"));
            return Reply.text("模型想调「" + name + "」，但工具清单里没有它，这次没有执行。", slots);
        }
        java.util.Map<String, Object> callArgs = new LinkedHashMap<>(args);
        tools.add(name);
        ToolOutcome out;
        try {
            out = spec.get().invoke(callArgs);
        } catch (RuntimeException e) {
            log.warn("模型选的工具执行失败 tool={} args={}", name, args, e);
            sink.accept(ChatEvent.tool(name, "执行失败"));
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return Reply.text("「" + name + "」没做成：" + reason, slots);
        }
        String firstLine = out.text() == null ? "" : out.text().lines().findFirst().orElse("");
        sink.accept(ChatEvent.tool(name, firstLine));
        // 参数里带的焦点写回槽位：下一句「那第 3 条呢」靠它接得上，否则每轮都得重新报 id
        return Reply.text(out.text(), focusOf(slots, plan));
    }

    /** 工具参数 → 槽位焦点。只认四个字段，其余参数（limit 之类）不是焦点。 */
    private static ChatSlots focusOf(ChatSlots slots, io.github.dolow.reconlens.chat.plan.ChatPlan plan) {
        ChatSlots next = slots;
        String ruleCode = plan.toolArgs().get("ruleCode");
        if (ruleCode != null && !ruleCode.isBlank()) {
            next = next.withRuleCode(ruleCode);
        }
        Long jobId = parseId(plan.toolArgs().get("jobId"));
        if (jobId != null) {
            next = next.withJobId(jobId);
        }
        Long diffId = parseId(plan.toolArgs().get("diffId"));
        if (diffId != null) {
            next = next.withDiffId(diffId);
        }
        String table = plan.toolArgs().get("table");
        if (table != null && !table.isBlank()) {
            next = next.withTable(table);
        }
        return next;
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 会话侧唯一的一次工具调用入口：<b>名字与实现在 {@link ToolRegistry} 里，不在这里</b>。
     *
     * <p>之前这段是 8 处 {@code mcp.xxx(...)} 加 8 处手写工具名。问题不在重复，而在"名字写在两个地方"：
     * 工具改名时 MCP 那边改了、会话这边没改，表现是「调用成功但结果为空」，只有用户能发现。
     * 现在名字记一次（{@link ToolNames}），轨迹里记的也是同一个常量。
     */
    /** 定义规则的两个参数：正文必填，操作人可空（没有操作人的起草也算数，只是不留签字人）。 */
    private static Map<String, Object> body(SessionScope scope, String ruleBody) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ruleBody", ruleBody);
        if (scope != null && scope.operator() != null) {
            args.put("operator", scope.operator());
        }
        return args;
    }

    private ToolOutcome call(String name, Map<String, Object> args, List<String> tools) {
        tools.add(name);
        return registry.invoke(name, args);
    }

    // ---------------------------------------------------------------- 各动作

    private Reply listRules(ChatSlots slots, List<String> tools, Consumer<ChatEvent> sink, String message) {
        // 仍然真实调一次工具：审计与统计要看到"这次读了规则库"，只是不复用它的 agent 文案。
        call(ToolNames.LIST_RULES, Map.of(), tools);
        sink.accept(ChatEvent.tool(ToolNames.LIST_RULES, "读规则库（含草稿/待审）"));
        // 清单由 ChatSpeech 按 lifecycle 重写：原文是"调 recon_run 时把 code 当 ruleCode"，
        // 且只列 ACTIVE——问草稿就只答草稿。
        return Reply.text(ChatSpeech.ruleList(catalog.summaries(), mentionsDrafts(message)), slots);
    }

    /** 用户这句是不是在问「草稿/待审/未生效」的规则。 */
    private static boolean mentionsDrafts(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("草稿") || message.contains("待审") || message.contains("未生效")
                || message.contains("没生效") || message.contains("审核中") || message.contains("审中");
    }

    /**
     * 「之前都执行过哪些规则」——问的是<b>跑过什么</b>，不是规则目录里<b>写着什么</b>。
     *
     * <p>这两件事必须分开：规则库回答"能对什么账"，任务历史回答"对过什么账"。
     * 前者在规则被改口径、换版本之后仍然成立，后者才是"上次是怎么跑的"的唯一凭据。
     * 早先两句话都落进 {@code recon_list_rules}，于是答得通顺但答非所问
     * （用户看到当前规则的清单，以为系统没听懂"之前"）。数据源是 {@code recon_job}，
     * 试算任务不算——见 {@code ReconToolRegistry#listJobs}。
     */
    private Reply listJobs(ChatSlots slots, List<String> tools, Consumer<ChatEvent> sink) {
        sink.accept(ChatEvent.tool(ToolNames.LIST_JOBS, "读任务历史，按规则汇总跑了多少次"));
        return Reply.text(call(ToolNames.LIST_JOBS, Map.of(), tools).text(), slots);
    }

    /**
     * 「有哪些数据」——问的是库里有什么可对账的表，不是「有哪些规则」。
     *
     * <p>答案只能来自注册表（{@code recon_registered_table}）：那是闸门 1 白名单的运行时半边，
     * 也是"这张表什么时候、被谁放进来的"的唯一记录。不猜、不从规则反推 ——
     * 规则里出现的表可能早已被撤出白名单，按规则反推会给出"看起来能对账"的假答案。
     */
    /**
     * 看一张表的字段。
     *
     * <p>表名从三个地方来，按可信度排序：话里点名的 &gt; 会话里记着的规则用的左表 &gt; 反问。
     * 不猜"最近说过的那张表"——猜错的代价是用户拿到另一张表的结构，还以为是自己看错了。
     */
    private Reply describeTable(ChatIntentRouter.Call call, ChatSlots slots, String message,
                                List<String> tools, Consumer<ChatEvent> sink) {
        String table = firstNonNull(call.table(), slots.table());
        String from = call.table() != null ? "话里" : "会话里刚说过的表";
        if (table == null && slots.ruleCode() != null) {
            try {
                table = catalog.resolve(slots.ruleCode()).spec().left().table();
                from = "会话里的规则 " + slots.ruleCode();
            } catch (RuntimeException e) {
                // 槽位里的规则可能已被删/改名：退回反问，别把一条"未找到规则"当成表名错误抛出去
                table = null;
            }
        }
        if (table == null) {
            // 只问"哪张表"并把候选报出来，不要把整份清单连行数再倒一遍 ——
            // 用户上一句刚问过「有哪些数据」，再回同一段会像没听懂。
            String listing = call(ToolNames.LIST_TABLES, java.util.Map.of(), tools).text();
            List<String> names = tableNames(listing);
            String hint = names.isEmpty()
                    ? "还没有注册过可对账的表。"
                    : "看哪张表的字段？可选：" + String.join(" / ", names);
            return Reply.text(hint + "\n（说「看一下 bill_left 的字段」就行。）",
                    slots.withPending(ChatIntent.DESCRIBE_TABLE.name(), Map.of("question", hint)));
        }
        // "看样例"要显式说：默认一个数据行都不读，这是"看结构"对业务库的承诺
        boolean withSample = message != null && WANTS_SAMPLE.matcher(message).find();
        // "起草建议"也要显式说：建议里含一份可粘贴的规则骨架，不想起草的人不该被它刷屏
        boolean withAdvice = message != null && WANTS_ADVICE.matcher(message).find();
        String note = "读表结构" + (withSample ? " + 有限窗口采样" : "") + (withAdvice ? " + 起草建议" : "");
        sink.accept(ChatEvent.tool(ToolNames.DESCRIBE_TABLE, note + "（" + from + "：" + table + "）"));
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("table", table);
        if (withSample) {
            args.put("sample", true);
        }
        if (withAdvice) {
            args.put("advice", true);
        }
        return Reply.text(call(ToolNames.DESCRIBE_TABLE, args, tools).text(), slots.withTable(table));
    }

    private Reply listData(ChatSlots slots, List<String> tools, Consumer<ChatEvent> sink) {
        sink.accept(ChatEvent.tool(ToolNames.LIST_TABLES, "读表注册表（闸门白名单）"));
        return Reply.text(call(ToolNames.LIST_TABLES, Map.of(), tools).text(), slots);
    }

    private Reply defineRule(SessionScope scope, ChatIntentRouter.Call call, ChatSlots slots, String message,
                             List<String> tools, Consumer<ChatEvent> sink) {
        call = withLocalRuleBody(call, message);
        if (call.ruleBody() == null || call.ruleBody().isBlank()) {
            return draftStep(scope, call, slots, message, tools, sink);
        }
        sink.accept(ChatEvent.tool(ToolNames.DEFINE_RULE, "校验 + 入库（草稿/生效由闸门决定）"));
        ToolOutcome outcome = call(ToolNames.DEFINE_RULE, body(scope, call.ruleBody()), tools);
        // 工具文本是写给宿主大模型的（「下一步：recon_trial_status(...)」），人看的是同一份事实的中文说法
        String text = ChatSpeech.defineRule(outcome);
        String code = firstNonNull(call.ruleCode(), codeOfBody(call.ruleBody()));
        return Reply.text(text, code == null ? slots : slots.withRuleCode(code).clearPending());
    }

    /**
     * 补回被模型路由丢掉的规则正文。
     *
     * <p>为什么放在这里而不是让模型带上：{@code LlmPlanner} 的输出只有意图与槽位，
     * 它复述 YAML 既没必要（正文本来就在用户消息里）也不可靠（八百行抄一遍必错）。
     * 词表路由走的是 {@link ChatIntentRouter#ruleBody}，两条路径的正文必须来自<b>同一个</b>确定性提取器，
     * 否则"用户贴的规则到底有没有被采用"会随路由方式变化——这正是"贴了 YAML 却只回了左右两张表、
     * 还反问业务键"的成因。
     *
     * <p>只在 {@code DEFINE_RULE} 且正文为空时补，已带正文的（比如起草骨架确认那一步）原样不动。
     */
    static ChatIntentRouter.Call withLocalRuleBody(ChatIntentRouter.Call call, String message) {
        if (call == null || call.intent() != ChatIntent.DEFINE_RULE
                || (call.ruleBody() != null && !call.ruleBody().isBlank())) {
            return call;
        }
        String body = ChatIntentRouter.ruleBody(message);
        if (body == null || body.isBlank()) {
            return call;
        }
        return new ChatIntentRouter.Call(call.intent(), call.ruleCode(), call.jobId(), call.diffId(),
                call.diffType(), body, call.table());
    }

    /**
     * 起草的一段：把这一句并进已收集的参数，缺就只问下一个缺的，齐了就生成骨架去校验。
     *
     * <p>为什么"只问一个"：一次列四样等于让用户填一张表，而人说话是一条一条来的。
     * 已经收到的要明确回显（"已记下：左 bill_left"），否则用户不知道自己那句有没有被算数。
     *
     * <p>生成的是<b>最小骨架</b>，之后照常走 {@code recon_define_rule} 的校验、影子试算与审批闸门——
     * 会话只负责"听懂"，不负责"放行"。
     */
    private Reply draftStep(SessionScope scope, ChatIntentRouter.Call call, ChatSlots slots, String message,
                            List<String> tools, Consumer<ChatEvent> sink) {
        String listing = call(ToolNames.LIST_TABLES, Map.of(), tools).text();
        List<String> tables = tableNames(listing);
        DraftIntake.Hints prior = DraftIntake.Hints.from(slots.pendingArgs());
        DraftIntake.Hints hints = DraftIntake.parse(message, tables, prior);
        // 上一轮问过「「日期」在列里没找到，是去掉还是对应哪一列？」：这一句如果点了列名，
        // 就是那道问的答案，补进维度再往下走——否则用户答了等于没答（实测「就用 updated_at 吧」被无视）。
        String hinted = slots.pendingArgs().get("hinted");
        if (hinted != null && !hinted.isBlank() && message != null && !message.isBlank()) {
            String forTable = hints.left() != null ? hints.left() : prior.left();
            List<String> cols = forTable == null ? List.of() : columnsOf(forTable, tools);
            String column = cols.isEmpty() ? null : columnIn(message, cols);
            if (column != null) {
                hints = withExtraKey(hints, column);
                log.info("起草：上一轮问的「{}」对上了列 {}，补进维度", hinted, column);
            }
        }
        // 「翻一下顺序」这种话是对调，不是参数；回显要说清楚已经调了，否则看起来像没听见
        boolean swapped = prior.left() != null && prior.right() != null
                && !prior.left().equals(prior.right())
                && prior.left().equals(hints.right()) && prior.right().equals(hints.left());
        // 左侧已经收下、只差右侧时，句子里单说的那张表是"右"，不是"左"。
        // 解析器不知道上一问问的是哪一边：它一律把第一张表当左，于是「bill_right」会把左覆盖掉，
        // 最后收出"左 bill_right，右 bill_right"——按同一张表对账，永远 0 差异。
        boolean noSideWord = message != null && !message.contains("左") && !message.contains("右");
        if (noSideWord && prior.left() != null && prior.right() == null && hints.right() == null
                && hints.left() != null && !hints.left().equals(prior.left())) {
            hints = hints.withSides(prior.left(), hints.left());
        }
        // 「就这两个吧」：没有表名，指的是上一句刚列的候选。只有在这种指代明确时才替用户选。
        if (hints.left() == null && hints.right() == null && refersToCandidates(message) && tables.size() >= 2) {
            hints = hints.withTables(tables.get(0), tables.get(1));
        }
        // 知道表之后，把业务词翻成真实列名：「订单和状态」→ dimension， 「金额」→ amount。
        // 不能只在有 hint 时做——用户也可能直接说「比金额」，那同样需要翻译。
        if (hints.left() != null) {
            hints = hints.resolveFrom(columnsOf(hints.left(), tools));
            // 点名的字段翻不成列名时**先确认，不静默丢**：用户说"客户、订单、地区"，
            // 表里没有地区，替他删掉这一维等于改了规则口径，而他毫不知情。
            if (hints.hint() != null && !hints.hint().isBlank()) {
                List<String> cols = columnsOf(hints.left(), tools);
                String ask = "「" + hints.hint() + "」在 " + hints.left() + " 的列里没找到（现有："
                        + String.join("、", cols) + "）。\n是要去掉这一维，还是它对应哪一列？"
                        + "\n（你不回的话，这一维就先不写进规则——改主意随时说列名。）";
                // 这一问也要落进槽位：否则用户下一句「bill_right」会被当成又一句新需求，
                // 已经收到的左侧表当场丢掉（实测：追问字段之后整段收集从头再来）。
                // 落进去的那份要**先摘掉认不出的词**，不然下一轮 resolveFrom 会拿它再问一遍。
                Map<String, String> askPending = new LinkedHashMap<>(hints.withoutHint().toMap());
                askPending.put("question", ask);
                // 把认不出的那个词记下来：下一句点列名时才知道它是在回答哪一问
                askPending.put("hinted", hints.hint());
                String spokenSoFar = mergeSpoken(slots.pendingArgs().get("spoken"),
                        DraftIntake.spokenWords(message));
                if (!spokenSoFar.isBlank()) {
                    askPending.put("spoken", spokenSoFar);
                }
                return Reply.text(ask, slots.withPending(DraftIntake.ACTION, askPending));
            }
        }
        // 用户这一路的原话（"订单和状态"、"金额"）每轮都攒进 pending：
        // 它只在落库后当账本锚点用，不参与 YAML 生成。攒晚了（只在确认那一步攒）
        // 就会漏掉前面几轮的口述——实测踩过：label 里只剩列名，用户说"那条订单对状态的"永远对不上。
        String spoken = mergeSpoken(slots.pendingArgs() == null ? null : slots.pendingArgs().get("spoken"),
                DraftIntake.spokenWords(message));
        // 这一句是不是在回答上一问。顺序：模型先接（「订单和状态」这种说法词表翻不出来），
        // 接不住再用裸值兜底（没 key 的部署、「order_no」这种一个词的回答必须仍能工作）。
        if (slots.hasPending() && !hints.complete()) {
            hints = fillWithModel(hints, message, tables, tools);
            String missing = hints.complete() ? "" : hints.missing().get(0);
            if (!hints.complete() && (missing.startsWith("业务键") || missing.startsWith("比对字段"))) {
                hints = DraftIntake.answer(message, hints, missing, columnsFor(hints, tools));
            }
        }
        // 模型补进来的槽位必须"用户自己的话指得到"。它很擅长把"日期"认成 updated_at：
        // 列是真的，可用户没这么说，写进骨架就是替他改口径——实测里 alice 说的"消费日期"
        // 被写成 updated_at，下一句纠正也照旧。这里问一句，不静默写。
        String guessMark = slots.pendingArgs() == null ? null : slots.pendingArgs().get("guessed");
        boolean guessAccepted = guessMark != null && acceptsGuess(message);
        if (guessMark != null && !guessAccepted && rejectsGuess(message)) {
            hints = hints.withoutColumns(java.util.Arrays.asList(guessMark.split("、")));
            guessAccepted = true;
        }
        List<String> fresh = new ArrayList<>(hints.keyColumns());
        fresh.removeAll(prior.keyColumns());
        if (hints.compare() != null && !hints.compare().equals(prior.compare())) {
            fresh.add(hints.compare());
        }
        List<String> guessed = guessAccepted
                ? List.of()
                : hints.untraceable(fresh, (message == null ? "" : message) + " " + spoken,
                        columnsFor(hints, tools));
        if (!guessed.isEmpty()) {
            List<String> cols = columnsFor(hints, tools);
            String colHint = cols.isEmpty() ? "" : "（现有：" + String.join("、", cols) + "）";
            String ask = "「" + String.join("、", guessed) + "」表里是有的，但你刚才那句话里没有能对上它的说法。\n"
                    + "我准备就按它写；是的话回「是」，不对就说列名" + colHint + "，或者回「去掉」。";
            // 猜的那几列先留在槽位里，但打个标记：下一轮只有用户点头（或否掉）才往下走，
            // 这样"是"之后不用把同一句再问一遍，丢掉又捡不回来。
            Map<String, String> askPending = new LinkedHashMap<>(hints.toMap());
            askPending.put("guessed", String.join("、", guessed));
            askPending.put("question", ask);
            if (!spoken.isBlank()) {
                askPending.put("spoken", spoken);
            }
            log.info("起草：模型补的列用户在话里没提过，先问一句 {}", guessed);
            return Reply.text(ask, slots.withPending(DraftIntake.ACTION, askPending));
        }
        // 按 key 比 key 永远 0 差异：这不是“能跑”的规则，是“假成功”。
        // 把比对字段退回去再问一次，并告诉用户为什么——只说“无效”等于没说。
        String overlap = hints.compareInKey();
        boolean compareCue = overlap != null && COMPARE_CUE.matcher(message).find();
        if (overlap != null) {
            hints = hints.withoutCompare();
        }

        if (!hints.complete()) {
            Map<String, String> pendingArgs = new LinkedHashMap<>(hints.toMap());
            if (!spoken.isBlank()) {
                pendingArgs.put("spoken", spoken);
            }
            // 把"我在问什么"存进槽位：下一句只回「order_no」时，规划器得知道它在回答什么
            pendingArgs.put("question", hints.ask());
            // 「给我完整展示下」：用户要的是"现在长什么样"，不是再问一遍。
            // 把已收集的、以及还空着的（写 ?）一起摆出来，他能直接看出缺哪一样。
            if (wantsPreview(message)) {
                return Reply.text(previewText(hints), slots.withPending(DraftIntake.ACTION, pendingArgs));
            }
            String ask = draftAsk(hints, tables, swapped);
            if (swapped) {
                ask = "已对调左右：左 " + hints.left() + "，右 " + hints.right() + "\n\n" + ask;
            }
            // 回了话、参数却没往前一步：明说"没接上"，别复读同一个问题让他以为系统卡了
            if (overlap == null && slots.hasPending() && hints.equals(prior)) {
                ask = "这句我没接上。我还在等：" + hints.missing().get(0) + "\n\n" + ask;
            }
            if (compareCue) {
                ask = overlap + "。\n\n" + ask;
            }
            return Reply.text(ask, slots.withPending(DraftIntake.ACTION, pendingArgs));
        }
        String code = hints.code();
        if (code == null && namer != null) {
            // 让模型按业务词起个能认出来的名字（ORDER_STATUS_RECON），而不是 AUTO_ 拼表名
            code = namer.propose(message, hints.left(), hints.right(), hints.key(), hints.compare())
                    .orElse(null);
        }
        if (code == null) {
            code = autoCode(hints.left(), hints.right());
        } else {
            code = RuleNamer.unique(code, catalog.codes());
        }
        String yaml = hints.toYaml(code);
        // 两段式的第一段：收齐之后**不立刻落库**，先把骨架摆出来问一句。
        // 这一步同时满足"给我完整展示"——展示与确认是同一个动作。
        ChatIntentRouter.Call draft = new ChatIntentRouter.Call(ChatIntent.DEFINE_RULE, code,
                null, null, null, yaml, null);
        String summary = "起草规则 " + code + "（维度 " + hints.key() + "，比 " + hints.compare() + "）";
        String ask = narrator == null ? null : narrator.confirm(ChatIntent.DEFINE_RULE, summary, message);
        Map<String, String> pending = pendingOf(draft, summary);
        // 确认状态里也要留着草稿的四个参数：用户回"比金额"时，这句话是改参数，
        // 不是一句新指令——没有这几个值，就只能从头再问一遍。
        pending.putAll(hints.toMap());
        if (!spoken.isBlank()) {
            pending.put("spoken", spoken);
        }
        String text = (ask == null ? defaultConfirm(summary) : ask)
                + "\n\n将落成草稿的骨架：\n```yaml\n" + yaml + "```";
        if (swapped) {
            text = "已对调左右：左 " + hints.left() + "，右 " + hints.right() + "\n\n" + text;
        }
        return Reply.text(text, slots.withPending(CONFIRM_ACTION, pending));
    }

    private static String mergeSpoken(String existing, String next) {
        String a = existing == null ? "" : existing.trim();
        String b = next == null ? "" : next.trim();
        if (b.isEmpty() || a.contains(b)) {
            return a;
        }
        return a.isEmpty() ? b : a + "，" + b;
    }

    /** 读一张表的列名（用来把业务词翻成列名）。失败就是空表，后面会照常反问。 */
    private List<String> columnsOf(String table, List<String> tools) {
        try {
            return DraftIntake.columnsIn(call(ToolNames.DESCRIBE_TABLE, Map.of("table", table), tools).text());
        } catch (RuntimeException e) {
            log.debug("读列名失败 table={}", table, e);
            return List.of();
        }
    }

    /**
     * 用户句子里点到的列名（先看整句里出现没出现，再按分词逐个翻）。
     *
     * <p>不能直接把整句丢给 {@code matchColumn}：它按"一个词"翻，
     * 「就用 updated_at 吧」这种带语气词的句子会被判成翻不出来。
     */
    private static String columnIn(String message, List<String> columns) {
        String lower = message.toLowerCase();
        for (String column : columns) {
            if (lower.contains(column.toLowerCase())) {
                return column;
            }
        }
        for (String token : message.split("\\s*(?:和|与|、|,|，|\\+|\\s|：|:)\\s*")) {
            String hit = DraftIntake.matchColumn(token, columns);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 把一道追问的答案（列名）并进维度：已有的不动，重了不加；hint 位清空，不需要再问。 */
    private static DraftIntake.Hints withExtraKey(DraftIntake.Hints hints, String column) {
        String key = hints.key();
        if (key == null || key.isBlank()) {
            key = column;
        } else if (key.contains(column)) {
            return hints;
        } else {
            key = key + "和" + column;
        }
        return new DraftIntake.Hints(hints.code(), hints.name(), hints.left(), hints.right(), key,
                hints.compare(), null);
    }

    /**
     * 让模型回答"上一轮问的那一问"。模型只提值：候选表 / 真实列由 {@link DraftSlotFiller} 校验。
     *
     * @return 补过空位的新参数；模型没配置、或一个都填不上时原样返回
     */
    private DraftIntake.Hints fillWithModel(DraftIntake.Hints hints, String message, List<String> tables,
                                            List<String> tools) {
        if (slotFiller == null || !slotFiller.configured()) {
            return hints;
        }
        List<String> leftColumns = hints.left() == null ? List.of() : columnsOf(hints.left(), tools);
        List<String> rightColumns = hints.right() == null ? List.of() : columnsOf(hints.right(), tools);
        DraftIntake.Hints merged = hints.withSlots(
                slotFiller.fill(message, hints, tables, leftColumns, rightColumns).orElse(Map.of()));
        if (merged.equals(hints)) {
            return hints;
        }
        // 新补进来的可能是一张表名：业务词要重新翻一次，否则「订单和状态」还挂在 hint 上
        String table = merged.left() != null ? merged.left() : merged.right();
        return table == null ? merged : merged.resolveFrom(columnsOf(table, tools));
    }

    /** 回答上一问时可用的列：键 / 比对字段必须是两侧都有的列，只有一侧知道时就只用那一侧。 */
    private List<String> columnsFor(DraftIntake.Hints hints, List<String> tools) {
        List<String> left = hints.left() == null ? List.of() : columnsOf(hints.left(), tools);
        List<String> right = hints.right() == null ? List.of() : columnsOf(hints.right(), tools);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left.stream().filter(column -> right.stream().anyMatch(column::equalsIgnoreCase)).toList();
    }

    /** 「就这两个吧」「都要」「随便」：指的是上一句刚列出来的候选。 */
    private static boolean refersToCandidates(String message) {
        return message != null && CANDIDATE_REF.matcher(message).find();
    }

    /** 「展示/看看草稿」这类请求：在收参数途中也要能看见全貌。 */
    private static boolean wantsPreview(String message) {
        return message != null && PREVIEW.matcher(message).find();
    }

    /** 用户认了上一问里"我准备按这一列写"的提议。只在 {@code guessed} 标记还在时被问。 */
    private static boolean acceptsGuess(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (GUESS_YES.matcher(text).find()) {
            return true;
        }
        return text.contains("就用") || text.contains("按这个") || text.contains("没错");
    }

    /** 用户否掉了那一列（"去掉""不用它"）。同样只看标记还在的那一轮。 */
    private static boolean rejectsGuess(String message) {
        String text = message == null ? "" : message.trim();
        return text.contains("去掉") || text.contains("不要") || text.contains("不用") || text.contains("不需要");
    }

    private static String previewText(DraftIntake.Hints hints) {
        String got = describeHints(hints);
        StringBuilder sb = new StringBuilder();
        sb.append("当前这份草稿还没收齐，先给你看看长什么样");
        if (!got.isEmpty()) {
            sb.append("（已记下：").append(got).append("）");
        }
        sb.append("：\n```yaml\n").append(hints.toYamlPreview()).append("```\n");
        sb.append("还缺：").append(String.join("、", hints.missing()))
                .append("。补齐之后我会校验并落成草稿（草稿不生效，审批通过才生效）。");
        return sb.toString();
    }

    /** 已收集的回显 + 下一个问题 + （问表时）候选表清单。 */
    private static String draftAsk(DraftIntake.Hints hints, List<String> tables, boolean skipAck) {
        StringBuilder sb = new StringBuilder();
        String got = describeHints(hints);
        if (!got.isEmpty() && !skipAck) {
            sb.append("已记下：").append(got).append("\n\n");
        }
        String next = hints.ask();
        sb.append(next);
        if (next.contains("哪张表") && !tables.isEmpty()) {
            sb.append("\n可对账的表：").append(String.join(" / ", tables));
        }
        sb.append("\n\n（一次说一样也行；也可以直接把 YAML/JSON 正文贴进来。）");
        return sb.toString();
    }

    private static String describeHints(DraftIntake.Hints hints) {
        List<String> parts = new ArrayList<>();
        if (hints.left() != null) {
            parts.add("左 " + hints.left());
        }
        if (hints.right() != null) {
            parts.add("右 " + hints.right());
        }
        if (hints.key() != null) {
            parts.add("业务键 " + hints.key());
        }
        if (hints.compare() != null) {
            parts.add("比 " + hints.compare());
        }
        if (hints.hint() != null) {
            // 还没翻译成列名的业务词也要回显：否则用户说完"根据订单和状态"会以为没被记住
            parts.add("待定字段「" + hints.hint() + "」");
        }
        return String.join("，", parts);
    }

    /** 从 {@code recon_list_tables} 的人话输出里取表名，去重保序。 */
    private static List<String> tableNames(String listing) {
        if (listing == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = TABLE_LINE.matcher(listing);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * 自动编码：用户没说编码时给一个能认出来的。
     *
     * <p>带 {@code AUTO_} 前缀是刻意的——看规则列表时一眼能看出"这条是对话起草的"，
     * 而不是和人工命名的规则混在一起。重名直接加序号，绝不覆盖已有规则。
     */
    private String autoCode(String left, String right) {
        String base = ("AUTO_" + left + "_" + right).toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String code = base;
        int n = 2;
        while (catalog.codes().contains(code)) {
            code = base + "_" + n++;
        }
        return code;
    }

    /**
     * 从正文里认领规则编码。
     *
     * <p>为什么不能只靠 {@code call.ruleCode()}：走会话时路由能从「定义规则 BILL_X …」里抠出编码，
     * 但<b>表单只提交正文</b>，没有那句话。不补这一步，表单起草完槽位里就没有规则名，
     * 用户接下来每问一句都要重新报一遍编码——这正是实测里 3/4/5/6/7 场景全挂的原因。
     *
     * <p>为什么用 {@code inlineYaml/inlineJson} 而不是正则：它们走的是和入库同一套校验，
     * 编码在 {@code code:} 里怎么写都能解析出来；正则会在缩进、引号、JSON 形态上各漏一次。
     * 解析失败时不在这里报错——正文有错该由 {@code defineRule} 给出准确的错误位置。
     */
    private String codeOfBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            String trimmed = body.stripLeading();
            RuleSpec spec = trimmed.startsWith("{")
                    ? catalog.inlineJson(body, "会话起草").spec()
                    : catalog.inlineYaml(body, "会话起草").spec();
            return spec.code();
        } catch (RuntimeException e) {
            log.debug("正文解析不出规则编码，交给 defineRule 报错", e);
            return null;
        }
    }

    private Reply run(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools, Consumer<ChatEvent> sink) {
        String code = firstNonNull(call.ruleCode(), slots.ruleCode(), onlyRuleCode());
        if (code == null) {
            // 把"在等一个规则名"记进槽位：下一句用户只回「BILL_VS_SETTLE」也能接上，
            // 不然那句话会被判成 HELP，用户得把整句话再说一遍。
            return Reply.text("要跑哪条规则？现在规则库里有：\n\n"
                    + call(ToolNames.LIST_RULES, Map.of(), tools).text()
                    + "\n说「跑一下 规则编码」就行。",
                    slots.withPending(ChatIntent.RUN.name(),
                            Map.of("question", "要跑哪条规则？（规则编码）")));
        }
        // 先看能不能跑，再动手：草稿被"未找到规则"打发掉会让人以为规则没存进去
        String blocked = whyNotRunnable(code);
        if (blocked != null) {
            return Reply.text(blocked, slots.withRuleCode(code).clearPending());
        }
        sink.accept(ChatEvent.tool(ToolNames.RUN, "分片并行对账：" + code));
        ToolOutcome outcome = call(ToolNames.RUN,
                Map.of("ruleCode", code, "remark", "内建会话发起"), tools);
        Object jobId = outcome.structured() == null ? null : outcome.structured().get("jobId");
        ChatSlots next = slots.withRuleCode(code).clearPending();
        if (jobId instanceof Number n) {
            next = next.withJobId(n.longValue());
        }
        return Reply.text(outcome.text(), next);
    }

    /**
     * 这条规则现在能不能跑；能跑返回 {@code null}，不能跑返回一句人话。
     *
     * <p>为什么要单独判一次：{@code RuleCatalog.resolve} 只认 ACTIVE，草稿会被报成
     * 「未找到规则 XXX」——那句话对用户是误导，他刚在同一个会话里看到"已落库为草稿"。
     */
    private String whyNotRunnable(String code) {
        List<RuleRepository.RuleRow> versions;
        try {
            versions = catalog.versions(code);
        } catch (RuntimeException e) {
            return null; // 查不到就交给引擎自己报错，别在这里吞掉真正的失败
        }
        if (versions == null || versions.isEmpty()) {
            return "规则库里没有 " + code + "。现在有：" + String.join(" / ", catalog.codes());
        }
        boolean active = versions.stream()
                .anyMatch(row -> row.lifecycle() == RuleLifecycle.ACTIVE);
        if (active) {
            return null;
        }
        RuleRepository.RuleRow latest = versions.stream()
                .max(Comparator.comparingInt(RuleRepository.RuleRow::version))
                .orElse(versions.get(0));
        return code + " 最新是 v" + latest.version() + "（" + latest.lifecycle() + "），还没生效。\n"
                + "草稿要先看试算结论，再在控制台领确认码签字——会话里说「批准」不算。";
    }

    private Reply jobSummary(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools,
                             Consumer<ChatEvent> sink) {
        Long jobId = resolveJob(call.jobId(), slots);
        boolean assumed = false;
        if (jobId == null && (jobId = latestJobId()) != null) {
            assumed = true;
        }
        if (jobId == null) {
            return Reply.text("要看哪个任务？给我任务号就行，比如「123 号任务怎么样」。", slots);
        }
        sink.accept(ChatEvent.tool(ToolNames.JOB_SUMMARY, "任务 " + jobId));
        ToolOutcome outcome = call(ToolNames.JOB_SUMMARY, Map.of("jobId", jobId), tools);
        String head = assumed ? assumedJobNote(jobId) : "";
        return Reply.text(head + outcome.text(), slots.withJobId(jobId));
    }

    private Reply listDiffs(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools,
                            Consumer<ChatEvent> sink) {
        Long jobId = resolveJob(call.jobId(), slots);
        boolean assumed = false;
        if (jobId == null && (jobId = latestJobId()) != null) {
            assumed = true;
        }
        if (jobId == null) {
            return Reply.text("要看哪个任务的差异？给我任务号，比如「123 号任务的差异」。", slots);
        }

        // diffType 在 DSL 里是自由字符串（业务可以写 AMOUNT_MISMATCH），而用户说的是语义类别
        // （"值不等"）。直接把 VALUE_MISMATCH 拿去过滤会一条都查不到——所以先看这个任务
        // 实际产出了哪些类型，再把语义类别翻译成它。
        ToolOutcome summary = call(ToolNames.JOB_SUMMARY, Map.of("jobId", jobId), tools);
        List<String> actualTypes = actualDiffTypes(summary.structured());
        String filter = null;
        String note = "";
        if (call.diffType() != null) {
            filter = translateDiffType(call.diffType(), actualTypes);
            if (filter == null) {
                return Reply.text((assumed ? assumedJobNote(jobId) : "")
                + "任务 " + jobId + " 里没有「" + call.diffType() + "」这一类差异。实际产出："
                                + (actualTypes.isEmpty() ? "（无）" : String.join(" / ", actualTypes))
                                + "\n\n可以直接说「看差异」看全部。",
                        slots.withJobId(jobId));
            }
            note = "（" + call.diffType() + " → " + filter + "）";
        }
        sink.accept(ChatEvent.tool(ToolNames.LIST_DIFFS,
                "任务 " + jobId + (filter == null ? "" : " · " + filter)));
        // 槽位里存「实际用的那个 diffType」，不是用户说的语义类别——
        // 下一句如果省略类型，回落时要能直接拿去过滤。
        List<String> nextFocus = filter == null ? List.of() : List.of(filter);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("jobId", jobId);
        args.put("limit", 20);
        if (filter != null) {
            args.put("diffType", filter);
        }
        return Reply.text((assumed ? assumedJobNote(jobId) : "")
                + call(ToolNames.LIST_DIFFS, args, tools).text() + (note.isEmpty() ? "" : "\n" + note),
                slots.withJobId(jobId).withFocusTypes(nextFocus));
    }

    /** 任务实际产出过哪些 diffType（自由字符串）。 */
    @SuppressWarnings("unchecked")
    private static List<String> actualDiffTypes(Map<String, Object> view) {
        Object raw = view == null ? null : view.get("diffByType");
        if (raw instanceof Map<?, ?> map) {
            List<String> types = new ArrayList<>();
            for (Object key : map.keySet()) {
                types.add(String.valueOf(key));
            }
            return types;
        }
        return List.of();
    }

    /**
     * 语义类别 → 这个任务里真实的 diffType。
     *
     * <p>对不上的时候返回 {@code null} 而不是硬塞一个：说"0 条差异"和"没有这一类"是两件事，
     * 前者会让人以为对上了。
     */
    private static String translateDiffType(String semantic, List<String> actualTypes) {
        for (String actual : actualTypes) {
            boolean hit = switch (semantic) {
                case "VALUE_MISMATCH" -> actual.endsWith("MISMATCH") && !actual.startsWith("MISSING");
                case "MISSING_LEFT", "MISSING_RIGHT", "DUPLICATE_KEY" -> actual.equals(semantic);
                default -> actual.equals(semantic);
            };
            if (hit) {
                return actual;
            }
        }
        return null;
    }

    private Reply explainJob(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools,
                             Consumer<ChatEvent> sink) {
        Long jobId = resolveJob(call.jobId(), slots);
        boolean assumed = false;
        if (jobId == null && (jobId = latestJobId()) != null) {
            assumed = true;
        }
        if (jobId == null) {
            return Reply.text("要解释哪个任务？给我任务号，比如「123 号任务为什么差这么多」。", slots);
        }
        sink.accept(ChatEvent.tool(ToolNames.EXPLAIN_JOB, "任务 " + jobId + " 的整单归因"));
        String text = call(ToolNames.EXPLAIN_JOB, Map.of("jobId", jobId, "limit", 10), tools).text();
        // 整单归因后按"任务里出现最多的差异类型"给一次案例提示，不要逐条刷屏
        try {
            String ruleCode = repository.findJob(jobId).map(j -> j.ruleCode()).orElse(null);
            var diffs = repository.listDiffs(jobId, null, 0, 1);
            if (ruleCode != null && !diffs.isEmpty()) {
                String extra = recall.augment(ruleCode, diffs.get(0).diffType(), diffs.get(0).field(), null,
                        repository.jobFacts(jobId)
                            .map(io.github.dolow.reconlens.store.ReconRepository.JobFacts::datasetVersion).orElse(null));
                if (!extra.isEmpty()) {
                    text = text + extra;
                    tools.add("recall");
                }
            }
        } catch (RuntimeException e) {
            log.warn("记忆检索失败，整单归因照常返回：{}", e.getMessage());
        }
        return Reply.text((assumed ? assumedJobNote(jobId) : "") + text, slots.withJobId(jobId));
    }

    private Reply explainDiff(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools,
                              Consumer<ChatEvent> sink) {
        Long diffId = call.diffId() != null ? call.diffId() : slots.diffId();
        if (diffId == null) {
            return Reply.text("要解释第几条差异？比如「第 137 条差异为什么」。", slots);
        }
        sink.accept(ChatEvent.tool(ToolNames.EXPLAIN_DIFF, "差异 " + diffId));
        String text = call(ToolNames.EXPLAIN_DIFF, Map.of("diffId", diffId), tools).text();
        // 归因之后补两段"记忆"：同类案例怎么处理的、口径文档怎么规定的。
        // 检索本身失败不能影响归因，所以整段包在 try 里，出错就当没有记忆。
        try {
            var diff = repository.findDiff(diffId);
            if (diff.isPresent()) {
                String ruleCode = slots.ruleCode();
                if (ruleCode == null) {
                    ruleCode = repository.findJob(diff.get().jobId()).map(j -> j.ruleCode()).orElse(null);
                }
                String extra = recall.augment(ruleCode, diff.get().diffType(), diff.get().field(),
                        diff.get().bizKey(),
                        repository.jobFacts(diff.get().jobId())
                                .map(io.github.dolow.reconlens.store.ReconRepository.JobFacts::datasetVersion)
                                .orElse(null));
                if (!extra.isEmpty()) {
                    text = text + extra;
                    tools.add("recall");
                }
            }
        } catch (RuntimeException e) {
            log.warn("记忆检索失败，归因照常返回：{}", e.getMessage());
        }
        return Reply.text(text, slots.withDiffId(diffId));
    }

    private Reply retry(ChatIntentRouter.Call call, ChatSlots slots, List<String> tools,
                        Consumer<ChatEvent> sink) {
        Long jobId = resolveJob(call.jobId(), slots);
        if (jobId == null) {
            return Reply.text("要重跑哪个任务？给我任务号，比如「重跑 123 号任务」。", slots);
        }
        sink.accept(ChatEvent.tool("recon_retry", "任务 " + jobId + " 只重跑失败分片"));
        engine.retry(jobId, true);
        ToolOutcome outcome = call(ToolNames.JOB_SUMMARY, Map.of("jobId", jobId), tools);
        return Reply.text("已按失败分片重跑任务 " + jobId + "（跑过的分片不会重算）。\n\n" + outcome.text(),
                slots.withJobId(jobId));
    }

    private Reply buildModel(SessionScope scope, ChatIntentRouter.Call call, ChatSlots slots,
                             List<String> tools, Consumer<ChatEvent> sink) {
        String code = firstNonNull(call.ruleCode(), slots.ruleCode(), onlyRuleCode());
        if (code == null) {
            return Reply.text("给哪条规则建模型？给我规则编码，比如「给 BILL_VS_SETTLE 建模型」。", slots);
        }
        sink.accept(ChatEvent.tool(ToolNames.BUILD_MODEL, "物化预处理表：" + code));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ruleCode", code);
        if (scope.operator() != null) {
            args.put("operator", scope.operator());
        }
        return Reply.text(call(ToolNames.BUILD_MODEL, args, tools).text(), slots.withRuleCode(code));
    }

    private Reply trialStatus(ChatIntentRouter.Call call, ChatSlots slots) {
        String code = firstNonNull(call.ruleCode(), slots.ruleCode());
        if (code == null) {
            return Reply.text("看哪条规则的试算？给我规则编码。", slots);
        }
        List<RuleRepository.RuleRow> versions = catalog.versions(code);
        if (versions.isEmpty()) {
            return Reply.text("规则 " + code + " 不存在。", slots);
        }
        StringBuilder sb = new StringBuilder("规则 " + code + " 的版本：\n");
        for (RuleRepository.RuleRow row : versions) {
            sb.append("\n· v").append(row.version()).append("  ").append(row.lifecycle())
                    .append(row.enabled() ? "" : "（已停用）")
                    .append("  来源=").append(row.origin());
            if (row.draftReason() != null && !row.draftReason().isBlank()) {
                sb.append("\n  起草原因：").append(row.draftReason());
            }
            appendTrial(sb, code, row.version());
        }
        sb.append("\n\n「能不能批」只由试算结论决定，签字必须在控制台用确认码完成——会话里说「批准」不算。");
        return Reply.text(sb.toString(), slots.withRuleCode(code));
    }

    /**
     * 把试算结论与可批性写成两行。
     *
     * <p>为什么非要显示可批性：{@code PASS} 不等于能签（样本只覆盖了一半也得人来判断），
     * {@code INCONCLUSIVE} 也不等于不能签。<b>结论只有一档，可批性是另一档</b>，
     * 只报结论会让用户以为"没有 PASS 就不能批"。
     */
    private void appendTrial(StringBuilder sb, String code, int version) {
        TrialState state = catalog.trialState(code, version).orElse(new TrialState.NotRun());
        if (state instanceof TrialState.NotRun) {
            sb.append("\n  试算：没跑过");
            return;
        }
        if (state instanceof TrialState.Skipped skipped) {
            sb.append("\n  试算：跳过（").append(skipped.skipReason()).append("）");
            sb.append("\n  可批性：").append(approvalText(state.approval()));
            return;
        }
        TrialState.Trialed trialed = (TrialState.Trialed) state;
        sb.append("\n  试算：").append(trialed.verdict())
                .append("（覆盖 ").append(trialed.coverage());
        if (!trialed.reasons().isEmpty()) {
            sb.append("，理由 ").append(trialed.reasons());
        }
        sb.append("）");
        sb.append("\n  可批性：").append(approvalText(trialed.approval()));
    }

    private static String approvalText(ApprovalMode mode) {
        return switch (mode) {
            case NO_SIGNOFF_REQUIRED -> "NO_SIGNOFF_REQUIRED —— 试算全量通过，不需要人签字";
            case NEED_SIGN -> "NEED_SIGN —— 可以签字生效";
            case BLOCK_SIGNABLE -> "BLOCK_SIGNABLE —— 系统不建议批，但人可以签字接受（留痕）";
            case BLOCK_HARD -> "BLOCK_HARD —— 不能签，先把问题改掉";
            case NOT_APPROVABLE -> "NOT_APPROVABLE —— 还没试算，签不了";
        };
    }

    private String approveText(ChatIntentRouter.Call call, ChatSlots slots) {
        String code = firstNonNull(call.ruleCode(), slots.ruleCode());
        return "审批这一步<b>不在会话里完成</b>，这是有意的：\n\n"
                + "1. 先看试算结论（问「" + (code == null ? "规则编码" : code) + " 试算通过了吗」）；\n"
                + "2. 到控制台 POST /api/rule-reviews/{code}/{version}/confirmation 领一次性确认码"
                + "（要带 X-Api-Key 与 X-Operator）；\n"
                + "3. 用那个码调 recon_submit_approval，或让人在界面上点确认。\n\n"
                + "会话里说「我同意」不构成签字——审批只认确认码 + 内容哈希，"
                + "规则内容改一个字节就作废（ADR-0001 N5）。";
    }

    // ---------------------------------------------------------------- 槽位

    private ChatSlots readSlots(ChatSession session) {
        String raw = session.stateJson();
        if (raw == null || raw.isBlank()) {
            return ChatSlots.EMPTY;
        }
        try {
            ChatSlots parsed = json.readValue(raw, ChatSlots.class);
            return parsed == null ? ChatSlots.EMPTY : parsed;
        } catch (JsonProcessingException e) {
            // 槽位坏了不该让整场对话挂掉：退回空槽位，让用户重新指一次
            log.warn("会话槽位解析失败 session={} raw={}", session.id(), raw);
            return ChatSlots.EMPTY;
        }
    }

    private String writeSlots(ChatSlots slots) {
        try {
            return json.writeValueAsString(slots);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("槽位序列化失败", e);
        }
    }

    private Long resolveJob(Long explicit, ChatSlots slots) {
        return explicit != null ? explicit : slots.jobId();
    }

    /**
     * 只读动作缺任务号时的兜底：取最近一次生产任务。
     *
     * <p>和"规则库只有一条时能不能省规则名"是同一个判断：信息只有一种可能时，反问是刁难。
     * 但这里多做一件事 —— 告诉用户按哪个任务答的（{@link #assumedJobNote}），
     * 因为"最近一次"是猜测，猜错时要让人一眼看出猜的是几号。
     *
     * <p>只给读动作用。重跑会改数据，缺任务号必须反问，不能替他挑一个。
     */
    private Long latestJobId() {
        return repository.latestProdJobId().orElse(null);
    }

    private static String assumedJobNote(Long jobId) {
        return "（没给任务号，按最近一次生产任务 #" + jobId + " 回答）\n\n";
    }

    /** 规则库只有一条时，允许省略规则名（少一次反问）。 */
    private String onlyRuleCode() {
        List<String> codes = catalog.codes();
        return codes.size() == 1 ? codes.get(0) : null;
    }

    private String helpText() {
        StringBuilder sb = new StringBuilder("我能在会话里直接做的事（不用 key，也不用接宿主）：\n\n");
        for (ChatIntent intent : ChatIntent.values()) {
            sb.append("· ").append(intent.form()).append("　试试说「").append(intent.example()).append("」\n");
        }
        sb.append("\n规则正文是贴给我看的，不是发给模型的——校验、试算、审批都在服务端跑。");
        sb.append("\n说「跑一下 BILL_VS_SETTLE」开始，跑完接着问「为什么差这么多」就行。");
        return sb.toString();
    }

    private static String friendly(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "处理失败：" + e.getClass().getSimpleName();
        }
        return msg;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 把一次表单动作记成一句人话，写进 {@code user} 轮次。
     *
     * <p>为什么不留空或者存 JSON：这段历史将来要给人看、要进摘要、要被模型读。塞一段
     * {@code {"action":"RUN","ruleCode":...}} 进去，读者得先学一遍 schema 才知道他干了什么。
     */
    static String describe(ChatIntentRouter.Call call) {
        StringBuilder sb = new StringBuilder("〔表单〕").append(call.intent().name());
        if (call.intent() == ChatIntent.DEFINE_RULE) {
            String body = call.ruleBody() == null ? "" : call.ruleBody();
            sb.append(" 正文=").append(body.length() > 200 ? body.substring(0, 200) + "…" : body);
            return sb.toString();
        }
        if (call.ruleCode() != null) {
            sb.append(" 规则=").append(call.ruleCode());
        }
        if (call.jobId() != null) {
            sb.append(" 任务=").append(call.jobId());
        }
        if (call.diffId() != null) {
            sb.append(" 差异=").append(call.diffId());
        }
        if (call.diffType() != null) {
            sb.append(" 类型=").append(call.diffType());
        }
        return sb.toString();
    }

    /**
     * 这一轮的「发生了什么」信封（写进 {@code recon_chat_turn.tool_calls}）。
     *
     * <p>为什么不只记工具名：P1 要看「用户那句话被认成什么动作」的命中率，而意图此前只存在
     * {@code session.state_json} 的 {@code lastIntent} 里——那是**会话级**的最后一个值，
     * 一个会话跑十轮只会剩一个，分布量不出来。放进轮次里才有历史。
     *
     * <p>为什么加键而不是加列：老数据是 {@code {"tools":[...]}}，加一个 {@code intent} 键两头都能读
     * （前端本来就判 {@code Array.isArray}），而加列要迁移两张 schema、两个 profile，
     * 收益只是一个字段。等真要做「按意图建索引」时再升列。
     */
    String envelope(ChatIntent intent, List<String> tools) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (intent != null) {
            payload.put("intent", intent.name());
        }
        payload.put("tools", tools);
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 只记工具的旧形态，保留给老调用点与测试。 */
    String toolsJson(List<String> tools) {
        return envelope(null, tools);
    }
}
