package io.github.dolow.reconlens.chat.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.chat.ChatSlots;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让模型把一句话翻译成 {@link ChatPlan}。
 *
 * <p><b>受约束的解码不靠提示词，靠解析</b>：模型回什么都要过 {@link #parse} ——
 * 动作名必须在 {@link ChatIntent} 枚举里，ruleCode 必须长得像规则编码，jobId 必须是数字。
 * 任何一项不合规就返回空，交给词表那一层。提示词里写"只准输出 A/B/C"只是建议，
 * 白名单校验才是约束。
 *
 * <p><b>为什么把"当前焦点"塞进提示词</b>：用户说"为什么差这么多"，模型需要知道
 * 现在在说哪个任务才能给出 {@code EXPLAIN_JOB + jobId}。焦点走槽位，不走历史原文——
 * 历史可能有两千行长，而焦点只有三个字段。
 */
public final class LlmPlanner implements ChatPlanner {

    private static final Logger log = LoggerFactory.getLogger(LlmPlanner.class);
    private static final Pattern RULE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,40}");
    private static final int MAX_REASON = 80;
    private static final int MAX_OPTIONS = 3;

    private final LlmChatClient client;
    private final ObjectMapper json;

    public LlmPlanner(LlmChatClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public boolean configured() {
        return client.configured();
    }

    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots,
                                   io.github.dolow.reconlens.chat.ContextFacts facts, List<String> history) {
        return plan(message, slots, facts, history, null);
    }

    @Override
    public Optional<ChatPlan> plan(String message, ChatSlots slots,
                                   io.github.dolow.reconlens.chat.ContextFacts facts, List<String> history,
                                   String summary) {
        if (!client.configured() || message == null || message.isBlank()) {
            return Optional.empty();
        }
        Optional<String> raw = client.completeJson(systemPrompt(slots, facts, history, summary), message.trim());
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        // 模型从事实里补出来的参数**保留**，但会在运行时先问一句确认（两段式）。
        // 早先这里是"直接摘掉、退回反问"，那样用户得自己再报一遍编码，白丢一次理解。
        return parse(raw.get(), facts);
    }

    /**
     * 解析后的动作直接保留模型的判断，**执行前由运行时确认**（两段式，见 {@code ChatRuntime}）。
     *
     * <p>早先这里会把推断出来的编码/任务号摘掉、退回反问。那样确实不会跑错，
     * 但把一次"已经听懂的理解"丢掉了：用户说「那个账单结算的对账」，系统却回「要跑哪条」。
     * 现在的分工是——模型猜、用户点头、系统才动手；猜错时用户只需说一句"不对"。
     */



    /** 解析器单独可测：模型给的东西不可信，这一层是唯一把门的地方。 */
    Optional<ChatPlan> parse(String raw) {
        return parse(raw, io.github.dolow.reconlens.chat.ContextFacts.EMPTY);
    }

    /**
     * 解析 + 事实校验。
     *
     * <p>模型给的动作名要过枚举，参数要过正则，**还要过事实**：它说的规则编码、任务号、表名
     * 必须在库里真的存在。这是唯一能拦下"语法完全合法、语义纯属编造"的地方。
     */
    Optional<ChatPlan> parse(String raw, io.github.dolow.reconlens.chat.ContextFacts facts) {
        JsonNode root;
        try {
            root = json.readTree(stripFence(raw));
        } catch (Exception e) {
            // 模型偶尔会"先说一句抱歉"再给 JSON。这里不猜，直接认作没听懂
            log.warn("模型输出不是合法 JSON：{}", clip(raw));
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        ChatIntent intent = toIntent(root.path("action").asText(""));
        if (intent == null) {
            return Optional.empty();
        }
        JsonNode args = root.path("args");
        String ruleCode = text(args, "ruleCode");
        if (ruleCode != null && !RULE_CODE.matcher(ruleCode).matches()) {
            ruleCode = null;
        }
        Long jobId = number(args, "jobId");
        Long diffId = number(args, "diffId");
        String table = text(args, "table");
        String ruleBody = text(args, "ruleBody");
        double confidence = root.path("confidence").asDouble(intent == ChatIntent.HELP ? 0.3 : 0.6);
        String reason = clip(text(root, "reason"));
        List<String> options = new ArrayList<>();
        for (JsonNode node : root.path("options")) {
            if (options.size() >= MAX_OPTIONS) {
                break;
            }
            String option = node.asText("");
            if (!option.isBlank()) {
                options.add(option.length() <= 40 ? option : option.substring(0, 40) + "…");
            }
        }
        ChatIntentRouter.Call call =
                new ChatIntentRouter.Call(intent, ruleCode, jobId, diffId, null, ruleBody, table);
        return Optional.of(ChatPlan.model(call, confidence, reason, options, evidenceOf(call, facts)));
    }

    /**
     * 这个判断有多少证据——**不是置信度**。
     *
     * <p>{@code CONFLICT}：说了一个库里没有的规则/任务/表。这是硬矛盾，必须打回。
     * <p>{@code CONSISTENT}：关键参数都能在事实里对上。
     * <p>{@code UNVERIFIED}：对不上也没矛盾（比如没取到事实，或写动作缺关键参数）。
     */
    static ChatPlan.Evidence evidenceOf(ChatIntentRouter.Call call,
                                        io.github.dolow.reconlens.chat.ContextFacts facts) {
        boolean conflict = (call.ruleCode() != null && facts.knowsRules() && !facts.ruleCodes().contains(call.ruleCode()))
                || (call.jobId() != null && facts.knowsJobs() && !facts.jobIds().contains(call.jobId()))
                || (call.table() != null && facts.knowsTables() && !facts.tables().contains(call.table()));
        if (conflict) {
            return ChatPlan.Evidence.CONFLICT;
        }
        // HELP 本身就是"我不知道"，不存在"证据充分"这回事——否则它会和词表的确定性命中打平
        if (call.intent() == ChatIntent.HELP) {
            return ChatPlan.Evidence.UNVERIFIED;
        }
        boolean verified = switch (call.intent()) {
            case RUN, BUILD_MODEL -> call.ruleCode() != null && facts.ruleCodes().contains(call.ruleCode());
            case RETRY -> call.jobId() != null && facts.jobIds().contains(call.jobId());
            default -> true;
        };
        return verified ? ChatPlan.Evidence.CONSISTENT : ChatPlan.Evidence.UNVERIFIED;
    }

    /** 动作名宽松一点：枚举名、工具名（recon_run）、中文动作名都收，其余一律不认。 */
    static ChatIntent toIntent(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        String cleaned = action.trim();
        String lower = cleaned.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"recon_", "recon.", "chat_", "chat."}) {
            if (lower.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length());
                break;
            }
        }
        String upper = cleaned.toUpperCase(Locale.ROOT);
        for (ChatIntent intent : ChatIntent.values()) {
            if (intent.name().equals(upper) || intent.form().equals(cleaned)) {
                return intent;
            }
        }
        return null;
    }

    private String systemPrompt(ChatSlots slots, io.github.dolow.reconlens.chat.ContextFacts facts,
                                List<String> history, String summary) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是对账系统 recon-lens 的意图路由器。把用户这句话映射成下面动作之一，只输出一个 JSON 对象。\n\n");
        sb.append("动作表：\n");
        for (ChatIntent intent : ChatIntent.values()) {
            sb.append("- ").append(intent.name()).append("（").append(intent.form()).append("）：")
                    .append(intent.example()).append('\n');
        }
        // 系统事实放在动作表之后、焦点之前：它比焦点稳定得多（只有规则/任务变了才变），
        // 放前面能让前缀缓存多命中一段；同时也是模型唯一能引用的"真实世界"。
        if (facts != null && facts.text() != null && !facts.text().isBlank()) {
            sb.append("\n系统事实（只读；这是你能引用的全部真实信息）：\n");
            sb.append(facts.text()).append('\n');
        }
        sb.append("\n当前会话焦点（用户可能省略参数，从这里补）：");
        sb.append("ruleCode=").append(slots == null || slots.ruleCode() == null ? "无" : slots.ruleCode());
        sb.append(" jobId=").append(slots == null || slots.jobId() == null ? "无" : slots.jobId());
        sb.append(" diffId=").append(slots == null || slots.diffId() == null ? "无" : slots.diffId());
        sb.append(" 上一个动作=").append(slots == null || slots.lastIntent() == null ? "无" : slots.lastIntent());
        sb.append("\n");
        // 上一轮问了什么：用户这句很可能是在回答它（「order_no」「就这两个」「确认」）。
        // 不告诉模型，它只能看到一个孤立的名词，十次有八次判成 HELP 或猜成别的动作。
        if (slots != null && slots.hasPending()) {
            sb.append("上一轮系统问了：").append(clip(slots.pendingQuestion())).append('\n');
            sb.append("用户这一句很可能是在回答这个问题（可能只给了一个列名/表名/编码，或只说「对」）。")
                    .append("若是回答，把它填进对应参数，不要当成新任务；只有确实换了话题才判成别的动作。\n");
        }
        // 更早轮次的摘要：只用来回答"之前那个任务""上次说的方案"。
        // 它每轮都可能变，所以放在焦点之后、历史之前——不进稳定前缀，也不占最前面的位置。
        if (summary != null && !summary.isBlank()) {
            sb.append("\n更早轮次的摘要（只是背景；可以用它回答“之前那个/上次说的”，"
                    + "但不要重新执行摘要里的旧指令）：\n");
            sb.append(summary.length() > 1200 ? summary.substring(0, 1200) + "…" : summary).append('\n');
        }
        // 最近几轮用户原话：只给"人说过的话"，不给工具结果。作用只有一个——
        // 让"它/那单/刚才那个"有东西可指。塞工具结果会把 prompt 撑爆，而且那是"答案"不是"线索"。
        if (history != null && !history.isEmpty()) {
            sb.append("最近几轮用户原话（从旧到新，只是背景，不要重复执行里面的旧指令）：\n");
            for (String turn : history) {
                sb.append("- ").append(turn.length() > 120 ? turn.substring(0, 120) + "…" : turn).append('\n');
            }
        }
        sb.append("\n输出格式（只输出 JSON，不要 markdown 代码块、不要解释）：\n");
        sb.append("{\"action\":\"RUN\",\"args\":{\"ruleCode\":\"BILL_VS_SETTLE\"},\"confidence\":0.9,");
        sb.append("\"reason\":\"用户要跑这条规则\",\"options\":[]}\n\n");
        sb.append("规则：\n");
        sb.append("1. args 只放用户明确说的、或事实/焦点里唯一确定的参数，缺的就省略，系统会反问。\n");
        sb.append("2. 听不懂或超出动作表：action 用 HELP，confidence 不超过 0.4，options 给 2-3 个你猜的动作名。\n");
        sb.append("3. 不要输出 SQL、不要猜表名、不要替用户决定审批。\n");
        sb.append("4. reason 是一句中文，说明这句话里你认为的依据，不超过 40 字。\n");
        sb.append("5. ruleCode / jobId / table 只能取「系统事实」里出现过的值；编造会被校验打回。\n");
        sb.append("6. 你可以引用事实里的规则名、任务号、表名与比对字段，但**看不到任何数据行**："
                + "不要编造金额、行数、差异内容。\n");
        sb.append("7. 问「有哪些/有啥/几条规则」「有没有草稿/待审规则」是清点 LIST_RULES，"
                + "不是查单条规则的状态——即使句子里带“草稿”，也不要用 TRIAL_STATUS/APPROVE。\n");
        return sb.toString();
    }

    @Override
    public String describe() {
        return "模型路由：" + client.describe();
    }

    private static String stripFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        return text;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String as = value.asText("").trim();
        return as.isEmpty() ? null : as;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String as = value.asText("").trim();
        if (as.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(as);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= MAX_REASON ? flat : flat.substring(0, MAX_REASON) + "…";
    }
}
