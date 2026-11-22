package io.github.dolow.reconlens.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 普通动作的通用槽位协议 —— 业界说的 missingSlots / slot filling。
 *
 * <p><b>起草（DEFINE_RULE）不归这里管</b>：它有 {@link DraftIntake} + {@link DraftSlotFiller}
 * 那一整套状态机（候选表白名单、一次一问、模型补齐），比这里细得多。本类只管
 * "还差一个编号"这一类简单追问：重跑哪个任务、看哪个任务的差异、解释第几条。
 *
 * <p><b>协议只有三条</b>：
 * <ol>
 *   <li>追问必须<b>挂起</b>（{@code pendingAction}）：否则用户回「123」会被判成 HELP
 *       ——在引入本协议之前这正是实况，四个读动作的反问全是空问；</li>
 *   <li>挂起要<b>带时间戳</b>（{@link #STAMP}）：挂起状态有过期时间。用户第二天才回「好」，
 *       不能把这句话粘到一个昨天问的写操作上——过期的确认不是确认，是误触发；</li>
 *   <li>补答<b>只填参数，不重判意图</b>：上一问是「要看哪个任务」，这一句「123」就是答案，
 *       不许再被理解成别的动作。填不上（换了话题）就落回正常路由。</li>
 * </ol>
 *
 * <p>为什么时间戳放在 {@code pendingArgs} 里而不是给 {@link ChatSlots} 加字段：
 * 槽位是整块 JSON 存库的，加一个约定键就够，不用动表结构，老会话里没有时间戳的挂起
 * 也不受影响（视为不过期）。
 */
public final class SlotProtocol {

    /** 挂起创建时间在 pendingArgs 里的约定键。 */
    public static final String STAMP = "__at";

    /** 挂起多久算过期。15 分钟：长过一次正常的走神，短过"先吃个饭再回来"。 */
    static final long TTL_MS = 15 * 60 * 1000L;

    /** 纯任务号：「123」「123 号」「任务 123」都归到数字本身。 */
    private static final Pattern DIGITS = Pattern.compile("\\d{1,9}");
    /** 「第 137 条」——只有解释差异的追问才按差异号解。 */
    private static final Pattern DIFF_INDEX = Pattern.compile("第\\s*(\\d{1,9})\\s*条");

    private SlotProtocol() {
    }

    /**
     * 给一组挂起参数盖上创建时间。拷贝再盖，不改调用方的原 Map（有的是不可变的 {@code Map.of}）。
     */
    public static Map<String, String> stamped(Map<String, String> args) {
        Map<String, String> out = new LinkedHashMap<>(args == null ? Map.of() : args);
        out.put(STAMP, Long.toString(System.currentTimeMillis()));
        return out;
    }

    /** 只造一个带时间戳的单键参数：追问场景九成是这种。 */
    public static Map<String, String> stampedQuestion(String question) {
        return stamped(Map.of("question", question == null ? "" : question));
    }

    /**
     * 这个挂起是不是已经过期。起草（{@link DraftIntake#ACTION}）永不过期——
     * 攒参数可以攒很久；没有时间戳的老挂起也不过期（向后兼容，别把存量会话的确认弄丢）。
     */
    public static boolean pendingExpired(ChatSlots slots, long nowMs) {
        if (slots == null || !slots.hasPending()) {
            return false;
        }
        if (DraftIntake.ACTION.equals(slots.pendingAction())) {
            return false;
        }
        String at = slots.pendingArgs().get(STAMP);
        if (at == null || at.isBlank()) {
            return false;
        }
        try {
            return nowMs - Long.parseLong(at.trim()) > TTL_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 这些挂起动作可以由「一个数字」补答。
     */
    public static boolean handles(String pendingAction) {
        return switch (pendingAction == null ? "" : pendingAction) {
            case "RETRY", "JOB_SUMMARY", "LIST_DIFFS", "EXPLAIN_JOB", "EXPLAIN_DIFF" -> true;
            default -> false;
        };
    }

    /**
     * 把用户的补答填进挂起动作。
     *
     * <p>填不出来返回空（用户换了话题、说了一句没有编号的话），调用方落回正常路由——
     * 宁可当新话题处理，也不要把「不是这个任务」猜成某个任务号。
     */
    public static Optional<ChatIntentRouter.Call> fill(String pendingAction, String message) {
        if (!handles(pendingAction) || message == null || message.isBlank()) {
            return Optional.empty();
        }
        String text = message.trim();
        ChatIntent intent = ChatIntent.valueOf(pendingAction);
        if ("EXPLAIN_DIFF".equals(pendingAction)) {
            Matcher m = DIFF_INDEX.matcher(text);
            if (m.find()) {
                return Optional.of(ChatIntentRouter.Call.of(intent).withDiff(Long.parseLong(m.group(1))));
            }
            // 裸数字也当差异号：上一问就是「第几条」
            Matcher d = DIGITS.matcher(text);
            if (d.matches()) {
                return Optional.of(ChatIntentRouter.Call.of(intent).withDiff(Long.parseLong(d.group())));
            }
            return Optional.empty();
        }
        // 任务号类追问：裸数字，或者带着少量任务措辞（「123 号任务怎么样」「重跑 7」）。
        // 判据：非数字部分去掉空白后不超过 8 字、且确实带着 任务/号/job 字样——
        // 这样追问原话里示范的说法（「123 号任务怎么样」）能接住，
        // 而「帮我看看前面 3 个任务里哪个差异最多」这种长句不会被当成编号。
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return Optional.empty();
        }
        String rest = text.replaceAll("[0-9\\s]", "");
        boolean bareNumber = rest.isEmpty();
        boolean jobWording = rest.length() <= 8
                && (rest.contains("任务") || rest.contains("号")
                        || rest.toLowerCase(java.util.Locale.ROOT).contains("job"));
        if (!bareNumber && !jobWording) {
            return Optional.empty();
        }
        return Optional.of(ChatIntentRouter.Call.of(intent).withJob(Long.parseLong(digits)));
    }
}
