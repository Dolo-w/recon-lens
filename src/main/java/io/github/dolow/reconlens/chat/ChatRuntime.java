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

    /** 等用户确认的写操作。与 pendingAction 同一个槽位，只是值固定为它。 */
    static final String CONFIRM_ACTION = "CONFIRM";

    /** 「是」：确认执行。否定先判，所以"不好"不会被当成"好"。 */
    private static final java.util.regex.Pattern AFFIRM =
            java.util.regex.Pattern.compile("^(确认|好|可以|行|对|是|执行|跑|开始|ok|yes|嗯|没问题|就这么办)");

    /** 「否」：取消。 */
    private static final java.util.regex.Pattern NEGATE =
            java.util.regex.Pattern.compile("不|别|取消|算了|不用|先不|不行|停|no");

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

    public ChatRuntime(ChatSessionService sessions, ChatSummaryService summaries, ToolRegistry registry,
            io.github.dolow.reconlens.recall.RecallAugmenter recall,
            io.github.dolow.reconlens.store.ReconRepository repository,
                       ReconEngine engine, RuleCatalog catalog, ObjectMapper json,
                       io.github.dolow.reconlens.chat.plan.ChatPlanner planner, ChatNarrator narrator,
                       io.github.dolow.reconlens.store.ChatDraftRepository chatDrafts, RuleNamer namer,
                       DraftSlotFiller slotFiller) {
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

            ChatSlots slots = readSlots(session);
            // 两段式的第二段：上一轮问过"确认吗"，这一句就是在回答它。
            // 必须放在规划器之前——"确认/取消"本身不构成任何动作，交给规划器只会被判成 HELP。
            if (CONFIRM_ACTION.equals(slots.pendingAction())) {
                Confirmed confirmed = handleConfirm(scope, sessionId, slots, message, tools, sink);
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
            List<String> history = recentUserMessages(scope, sessionId, HISTORY_TURNS);
            // 每轮重建一次系统事实（规则目录 + 最近任务 + 表名）：只读元数据，不读任何数据行。
            // 它同时是"给模型看的索引"和"校验模型输出的依据"——两件事用同一份事实，才不会自相矛盾。
            ContextFacts facts = ContextFacts.build(catalog, repository);
            io.github.dolow.reconlens.chat.plan.ChatPlan plan = planner.plan(message, slots, facts, history,
                            session.summary())
                    .orElseThrow(() -> new IllegalStateException("规划器必须给出结果"));
            ChatIntentRouter.Call call = resumePending(plan.call(), slots, message);
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
            // 先推"怎么理解的"，再推"理解成了什么"：用户第一眼要的是依据，不是枚举名
            sink.accept(ChatEvent.plan(plan));
            sink.accept(ChatEvent.intent(call.intent()));

            Reply reply;
            if (slots.hasPending() && message != null && CANCEL.matcher(message).find()) {
                // 用户在回答"要哪张表"时说"算了"：这是在叫停，不是要重开一个话题
                reply = Reply.text("好，这次不建了；刚记下的参数已经清空。", slots.clearPending());
            } else if (call.intent() == ChatIntent.HELP && plan.hasOptions()) {
                // 带候选的"没听懂"不是"要说明书"：给一句短问句 + 候选项，别甩整页帮助
                reply = Reply.text(clarifyText(plan), slots);
            } else if (needsWriteConfirm(call, message)) {
                // 两段式的第一段：会改数据的动作，先把"我理解成什么"说给用户听，等一句确认
                String summary = describeCall(call);
                String text = narrator == null ? null : narrator.confirm(call.intent(), summary, message);
                reply = Reply.text(text == null ? defaultConfirm(summary) : text,
                        slots.withPending(CONFIRM_ACTION, pendingOf(call, summary)));
            } else {
                reply = narrate(dispatch(scope, call, slots, message, tools, sink), call.intent(), message);
            }
            ChatSlots next = reply.slots().withIntent(call.intent().name());
            // 起草成功才记一条账本：这是"本会话产生过哪些规则版本"的唯一事实来源。
            recordDraftIfNeeded(sessionId, call, reply, null);

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
            return dispatch(scope, call, slots, null, tools, event -> { });
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
     * 写槽位，冲突就重读一次再写。
     *
     * <p>冲突的来源：同一会话里两个人同时发消息（或一个人连发两条）。重试一次足够——槽位是个
     * 小状态机，不是需要合并的文档；再冲突说明高频并发，让它盖过去并留一行 WARN 比无限重试更诚实。
     */
    private boolean saveSlotsWithRetry(SessionScope scope, long sessionId, int rev, ChatSlots slots) {
        String payload = writeSlots(slots);
        if (sessions.saveSlots(scope, sessionId, rev, payload)) {
            return true;
        }
        ChatSession fresh = sessions.require(scope, sessionId);
        if (sessions.saveSlots(scope, sessionId, fresh.stateRev(), payload)) {
            log.info("槽位并发写入已重试成功 session={} rev={}", sessionId, rev);
            return true;
        }
        log.warn("槽位并发写冲突，本轮槽位未落库 session={} rev={}", sessionId, rev);
        return false;
    }

    /**
     * 把引擎原话换成"先结论、后明细"。只改正文，不动工具记录与槽位——
     * 审计看到的是工具真实返回，模型只影响"怎么读"。
     */
    private Reply narrate(Reply reply, ChatIntent intent, String message) {
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
    private void recordDraftIfNeeded(long sessionId, ChatIntentRouter.Call call, Reply reply, String userWords) {
        if (call == null || call.intent() != ChatIntent.DEFINE_RULE
                || reply == null || reply.slots().hasPending()) {
            return;
        }
        String code = reply.slots().ruleCode();
        if (code == null || sessionId <= 0) {
            return;
        }
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
    private Confirmed handleConfirm(SessionScope scope, long sessionId, ChatSlots slots, String message,
                                    List<String> tools, Consumer<ChatEvent> sink) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (NEGATE.matcher(text).find()) {
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
        ChatIntentRouter.Call call = callOf(slots.pendingArgs());
        if (call == null) {
            return new Confirmed(Reply.text("这个操作我记不清了，请再说一遍要做什么。", slots.clearPending()),
                    ChatIntent.HELP);
        }
        // 让用户看到"确认的到底是哪一件"，再动手
        sink.accept(ChatEvent.plan(io.github.dolow.reconlens.chat.plan.ChatPlan.keyword(call, 0.95,
                "已确认：" + slots.pendingArgs().getOrDefault("summary", describeCall(call)))));
        sink.accept(ChatEvent.intent(call.intent()));
        Reply reply = dispatch(scope, call, slots.clearPending(), text, tools, sink);
        recordDraftIfNeeded(sessionId, call, reply, slots.pendingArgs().get("spoken"));
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
     * 写操作要不要先确认。
     *
     * <p>判据不是"是不是写"，而是"**参数是不是用户自己说出来的**"：
     * 用户点名了规则（「跑一下 BILL_VS_SETTLE」）说明他知道自己在做什么，直接执行；
     * 靠事实/槽位/唯一候选补出来的参数，就得让他再看一眼。
     */
    private static boolean needsWriteConfirm(ChatIntentRouter.Call call, String message) {
        if (call == null || !call.intent().isWrite()) {
            return false;
        }
        switch (call.intent()) {
            case RUN, BUILD_MODEL -> {
                if (call.ruleCode() == null) {
                    return false;   // 缺参数：交给各自的"要跑哪条"反问
                }
            }
            case RETRY -> {
                if (call.jobId() == null) {
                    return false;
                }
            }
            case DEFINE_RULE -> {
                // 还没收齐参数时不能确认——那正是要一句句问的时候。
                // 收齐之后由 draftStep 自己生成骨架再问确认（那里才知道要落什么）。
                if (call.ruleBody() == null || call.ruleBody().isBlank()) {
                    return false;
                }
            }
            default -> { }
        }
        String upper = message == null ? "" : message.toUpperCase(java.util.Locale.ROOT);
        if (call.ruleCode() != null && upper.contains(call.ruleCode().toUpperCase(java.util.Locale.ROOT))) {
            return false;
        }
        if (call.jobId() != null && message != null && message.contains(String.valueOf(call.jobId()))) {
            return false;
        }
        return true;
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
     * 上一轮问了"要跑哪条规则"，这一轮用户只回了一个编码 —— 那是答案，不是新指令。
     *
     * <p>只对<b>写动作</b>做续接（跑一次 / 起草），且必须有确定性的证据：
     * 规则编码能在规则库里找到、或者起草参数能被解析出来。找不到就当没续接，
     * 退回正常路由——宁可多问一句，也不要拿"看起来像"的东西去执行。
     */
    private ChatIntentRouter.Call resumePending(ChatIntentRouter.Call call, ChatSlots slots, String message) {
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

    private Reply dispatch(SessionScope scope, ChatIntentRouter.Call call, ChatSlots slots,
                           String message, List<String> tools, Consumer<ChatEvent> sink) {
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
        // 「翻一下顺序」这种话是对调，不是参数；回显要说清楚已经调了，否则看起来像没听见
        boolean swapped = prior.left() != null && prior.right() != null
                && !prior.left().equals(prior.right())
                && prior.left().equals(hints.right()) && prior.right().equals(hints.left());
        // 「就这两个吧」：没有表名，指的是上一句刚列的候选。只有在这种指代明确时才替用户选。
        if (hints.left() == null && hints.right() == null && refersToCandidates(message) && tables.size() >= 2) {
            hints = hints.withTables(tables.get(0), tables.get(1));
        }
        // 知道表之后，把业务词翻成真实列名：「订单和状态」→ dimension， 「金额」→ amount。
        // 不能只在有 hint 时做——用户也可能直接说「比金额」，那同样需要翻译。
        if (hints.left() != null) {
            hints = hints.resolveFrom(columnsOf(hints.left(), tools));
        }
        // 这一句是不是在回答上一问。顺序：模型先接（「订单和状态」这种说法词表翻不出来），
        // 接不住再用裸值兜底（没 key 的部署、「order_no」这种一个词的回答必须仍能工作）。
        if (slots.hasPending() && !hints.complete()) {
            hints = fillWithModel(hints, message, tables, tools);
            String missing = hints.complete() ? "" : hints.missing().get(0);
            if (!hints.complete() && (missing.startsWith("业务键") || missing.startsWith("比对字段"))) {
                hints = DraftIntake.answer(message, hints, missing, columnsFor(hints, tools));
            }
        }
        // 按 key 比 key 永远 0 差异：这不是“能跑”的规则，是“假成功”。
        // 把比对字段退回去再问一次，并告诉用户为什么——只说“无效”等于没说。
        String overlap = hints.compareInKey();
        boolean compareCue = overlap != null && COMPARE_CUE.matcher(message).find();
        if (overlap != null) {
            hints = hints.withoutCompare();
        }
        // 用户这一路的原话（"订单和状态"、"金额"）每轮都攒进 pending：
        // 它只在落库后当账本锚点用，不参与 YAML 生成。攒晚了（只在确认那一步攒）
        // 就会漏掉前面几轮的口述——实测踩过：label 里只剩列名，用户说"那条订单对状态的"永远对不上。
        String spoken = mergeSpoken(slots.pendingArgs().get("spoken"), DraftIntake.spokenWords(message));
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
