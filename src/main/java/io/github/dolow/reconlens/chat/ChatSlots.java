package io.github.dolow.reconlens.chat;

import java.util.List;
import java.util.Map;

/**
 * 会话槽位：这一段对话「现在在说哪条规则、哪个任务、哪条差异、哪张表」，以及
 * 「有没有一个动作正在等用户补参数」。
 *
 * <p>为什么要有它：对账的追问几乎全是省略句——「跑一下」「为什么差这么多」「第 137 条呢」「字段呢」。
 * 没有人会在每一句里重复 ruleCode 和 jobId。把这些指代做成<b>结构化的列</b>而不是从历史里猜，
 * 是 ADR-0001 D7 的核心判断：<b>指代解析不该花一次模型调用</b>，而且猜错一次的代价是
 * 对着错的任务解释差异，看起来还很合理。
 *
 * <p>为什么不把用户原话也放进来：原话在 {@code recon_chat_turn} 里，只增不改。
 * 槽位是"当前焦点"，是可以被覆盖的那一份；两者混在一起，压缩时就会分不清哪个是事实。
 *
 * <p>{@code lastIntent} 也是槽位：用户下一句说"再来一次"，要知道"一次"指的是哪个动作。
 *
 * <p>{@code pendingAction} / {@code pendingArgs} 是"半句话"：写动作需要多个参数，
 * 用户一次只给一个（「左 bill_left」「按 order_no 连」）。没有这组字段，系统就只能把
 * 同一句反问重复一遍 —— 那不是交互，是复读。
 */
public record ChatSlots(String ruleCode, Long jobId, Long diffId, String lastIntent, List<String> focusTypes,
                        String table, String pendingAction, Map<String, String> pendingArgs) {

    public static final ChatSlots EMPTY =
            new ChatSlots(null, null, null, null, List.of(), null, null, Map.of());

    public ChatSlots {
        focusTypes = focusTypes == null ? List.of() : List.copyOf(focusTypes);
        pendingArgs = pendingArgs == null ? Map.of() : Map.copyOf(pendingArgs);
        table = table == null || table.isBlank() ? null : table;
        pendingAction = pendingAction == null || pendingAction.isBlank() ? null : pendingAction;
    }

    public ChatSlots withRuleCode(String code) {
        return new ChatSlots(code == null || code.isBlank() ? ruleCode : code, jobId, diffId, lastIntent,
                focusTypes, table, pendingAction, pendingArgs);
    }

    /** 换了任务就清掉差异焦点：上一条任务的 diffId 放到新任务上毫无意义，留着只会答错。 */
    public ChatSlots withJobId(Long id) {
        return new ChatSlots(ruleCode, id == null ? jobId : id, null, lastIntent, focusTypes, table,
                pendingAction, pendingArgs);
    }

    public ChatSlots withDiffId(Long id) {
        return new ChatSlots(ruleCode, jobId, id, lastIntent, focusTypes, table, pendingAction, pendingArgs);
    }

    public ChatSlots withIntent(String intent) {
        return new ChatSlots(ruleCode, jobId, diffId, intent, focusTypes, table, pendingAction, pendingArgs);
    }

    public ChatSlots withFocusTypes(List<String> types) {
        return new ChatSlots(ruleCode, jobId, diffId, lastIntent, types, table, pendingAction, pendingArgs);
    }

    /** 记住"刚说到的那张表"：下一句「字段呢」「多少行」就不用再报一次表名。 */
    public ChatSlots withTable(String name) {
        return new ChatSlots(ruleCode, jobId, diffId, lastIntent, focusTypes, name, pendingAction, pendingArgs);
    }

    /** 进入"等参数"状态：动作名 + 已经收到的部分参数。 */
    public ChatSlots withPending(String action, Map<String, String> args) {
        return new ChatSlots(ruleCode, jobId, diffId, lastIntent, focusTypes, table, action, args);
    }

    public ChatSlots withPendingArg(String key, String value) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(pendingArgs);
        if (value != null && !value.isBlank()) {
            merged.put(key, value);
        }
        return withPending(pendingAction, merged);
    }

    /** 动作做完了（或放弃了）就清掉等待状态，否则下一句会被错当成参数。 */
    public ChatSlots clearPending() {
        return new ChatSlots(ruleCode, jobId, diffId, lastIntent, focusTypes, table, null, Map.of());
    }

    /**
     * 并发写冲突后的合并：<b>以最新落库的那份为底，本轮写过的字段覆盖上去</b>。
     *
     * <p>为什么不能"重试时直接盖掉最新那份"：两个人同时说话时，A 刚把焦点切到任务 7，
     * B 的这一轮还拿着旧 rev 和旧 jobId；直接覆盖等于把 A 的焦点抹掉——用户看到的是
     * "我刚说的它忘了"，而库里没有任何痕迹。
     *
     * <p>为什么必须由调用方先读最新再合并、而不是在这里查库：槽位是纯值对象，
     * 它不该知道会话表长什么样（ChatRuntime 知道）。
     *
     * <p>{@code pending} 以<b>本轮</b>为准：半句话是"刚刚问出去的"，旧的那份已经过期，
     * 合并进来只会让用户下一句被填进上一个动作。
     */
    public ChatSlots mergeWith(ChatSlots newer) {
        if (newer == null) {
            return this;
        }
        return new ChatSlots(
                ruleCode != null ? ruleCode : newer.ruleCode,
                jobId != null ? jobId : newer.jobId,
                diffId != null ? diffId : newer.diffId,
                lastIntent != null ? lastIntent : newer.lastIntent,
                focusTypes.isEmpty() ? newer.focusTypes : focusTypes,
                table != null ? table : newer.table,
                hasPending() ? pendingAction : newer.pendingAction,
                hasPending() ? pendingArgs : newer.pendingArgs);
    }

    /** 有 jobId 才能答"差异 / 归因"；没有就该反问，而不是默认最近一条。 */
    public boolean hasJob() {
        return jobId != null;
    }

    public boolean hasPending() {
        return pendingAction != null;
    }

    /**
     * 上一轮系统问的是什么。
     *
     * <p>给规划器看：用户这一句很可能是在回答它，而不是在开新话题。没有这句话，
     * 模型只能看到「order_no」这一个词，既判断不出意图，也判断不出该填哪个参数。
     */
    public String pendingQuestion() {
        if (!hasPending()) {
            return "";
        }
        String asked = pendingArgs.get("question");
        if (asked != null && !asked.isBlank()) {
            return asked;
        }
        return switch (pendingAction) {
            case "RUN" -> "要跑哪条规则？（规则编码）";
            case "DESCRIBE_TABLE" -> "看哪张表的字段？";
            default -> "还缺一个参数";
        };
    }
}
