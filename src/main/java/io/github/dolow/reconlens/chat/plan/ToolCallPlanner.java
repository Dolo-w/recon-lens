package io.github.dolow.reconlens.chat.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.chat.ChatIntentRouter;
import io.github.dolow.reconlens.tool.ToolNames;
import io.github.dolow.reconlens.tool.ToolRegistry;
import io.github.dolow.reconlens.tool.ToolSpec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型直接选工具的"协议层"：把工具清单写成提示词、把模型的回答解成三选一。
 *
 * <p><b>为什么不另起一次模型调用</b>：工具选择与意图判断看的是同一份上下文（事实/焦点/摘要/历史），
 * 分成两次不但多花一次往返，还会出现"判了 RUN、却选了 list_jobs"这种两份答案打架的情况。
 * 所以这里是纯函数：{@link LlmPlanner} 负责发请求，本类负责"清单怎么写、回答怎么解"。
 *
 * <p><b>工具清单只有一份</b>：名字与参数名都从 {@link ToolSpec#inputSchema()} 读，
 * 不在这里手抄一遍。手抄的清单会随工具演进而过期，而过期的清单比没有清单更坏——
 * 模型会照着旧参数名生成，工具那边收到空值，表现成"调用成功但没答案"。
 */
public final class ToolCallPlanner {

    private static final Logger log = LoggerFactory.getLogger(ToolCallPlanner.class);
    private static final int MAX_DESCRIPTION = 90;
    private static final int MAX_REPLY_CHARS = 1200;

    /** 工具名 → 意图枚举的别名（名字与枚举不同名的少数几个）。 */
    private static final Map<String, ChatIntent> ALIASES = Map.of(
            ToolNames.LIST_TABLES, ChatIntent.LIST_DATA,
            ToolNames.DIAGNOSE, ChatIntent.HELP);

    private final ToolRegistry tools;
    private final ObjectMapper json;
    private final String section;

    public ToolCallPlanner(ToolRegistry tools, ObjectMapper json) {
        this.tools = tools;
        this.json = json;
        this.section = render();
    }

    /** 没有工具可用时（单元测试、装配缺失）：提示词里不出现工具段，解析也不认工具动作。 */
    public static ToolCallPlanner none(ObjectMapper json) {
        return new ToolCallPlanner(new EmptyRegistry(), json);
    }

    public boolean enabled() {
        return !tools.specs().isEmpty();
    }

    public List<ToolSpec> specs() {
        return tools.specs();
    }

    /** 拼进系统提示词的那一段（含三选一协议）。没有工具时返回空串。 */
    public String section() {
        return section;
    }

    /**
     * 解模型的一句话。
     *
     * <p>三种形态：
     * <ul>
     *   <li>{@code {"action":"tool","name":"recon_list_diffs","args":{...}}} —— 点工具；</li>
     *   <li>{@code {"action":"recon_list_diffs","args":{...}}} —— 模型直接把工具名当了动作名（很常见）；</li>
     *   <li>{@code {"action":"reply","text":"..."}} / {@code {"action":"done"}} —— 说话 / 收尾。</li>
     * </ul>
     *
     * <p>名字认不出、不是 JSON、缺 name，一律返回空：调用方（{@link LlmPlanner}）随后按老路径
     * 当成意图枚举再解一次。这个顺序很重要——工具协议是新增能力，不能让它把原来能听懂的话变听不懂。
     */
    public Optional<Decision> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(LlmPlanner.stripFence(raw));
        } catch (Exception e) {
            log.debug("工具协议：不是合法 JSON，交回老路径", e);
            return Optional.empty();
        }
        return parse(root);
    }

    /**
     * 已经解析成树的那一份直接解：{@link LlmPlanner} 为了"不是 JSON"的告警已经解过一次，
     * 再解一次纯属浪费，也会让两处的宽容度（代码块围栏、空节点）有机会悄悄分叉。
     */
    public Optional<Decision> parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String action = root.path("action").asText("").trim();
        String lower = action.toLowerCase(Locale.ROOT);
        if (lower.equals("done") || lower.equals("end") || lower.equals("结束")) {
            // 收尾允许顺带一句总结（可选）：模型给了就用，没给就由运行时补一句固定收尾
            String text = firstText(root, "text", "reply", "content", "message");
            return Optional.of(Decision.done(text));
        }
        if (lower.equals("reply") || lower.equals("say") || lower.equals("answer") || lower.equals("ask")) {
            String text = firstText(root, "text", "reply", "content", "message");
            if (text == null) {
                return Optional.empty();
            }
            return Optional.of(Decision.reply(
                    text.length() <= MAX_REPLY_CHARS ? text : text.substring(0, MAX_REPLY_CHARS) + "…"));
        }
        String name = lower.equals("tool") || lower.equals("call")
                ? firstText(root, "name", "tool", "toolName")
                : action;
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<ToolSpec> spec = resolve(name);
        if (spec.isEmpty()) {
            log.debug("工具协议：模型点了不存在的工具 {}，交回老路径", name);
            return Optional.empty();
        }
        return Optional.of(Decision.tool(spec.get().name(), argsOf(root.path("args"))));
    }

    /** 按名字找工具：大小写不敏感，也认 {@code recon.xxx} 这种写法。 */
    public Optional<ToolSpec> resolve(String name) {
        String cleaned = name.trim();
        if (cleaned.startsWith("recon.")) {
            cleaned = "recon_" + cleaned.substring("recon.".length());
        }
        final String target = cleaned;
        Optional<ToolSpec> exact = tools.specs().stream()
                .filter(spec -> spec.name().equals(target)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return tools.specs().stream()
                .filter(spec -> spec.name().equalsIgnoreCase(target)).findFirst();
    }

    /**
     * 工具对应的意图枚举：只用于回执话术、审计与 P1 表单复用，<b>不参与执行</b>。
     * 认不出就给 {@link ChatIntent#HELP}（不是写动作，不会误触发确认）。
     */
    public static ChatIntent intentFor(String tool) {
        ChatIntent alias = ALIASES.get(tool);
        if (alias != null) {
            return alias;
        }
        ChatIntent parsed = LlmPlanner.toIntent(tool);
        return parsed == null ? ChatIntent.HELP : parsed;
    }

    /**
     * 把工具参数还原成 {@link ChatIntentRouter.Call}：回执里那句"我理解你要跑一次对账 X"
     * 与"要不要先确认"的判据都读它，所以工具路径和意图路径必须共用同一个 Call。
     */
    public static ChatIntentRouter.Call callFor(String tool, Map<String, String> args) {
        Map<String, String> a = args == null ? Map.of() : args;
        return new ChatIntentRouter.Call(intentFor(tool), a.get("ruleCode"), number(a, "jobId"),
                number(a, "diffId"), a.get("diffType"), a.get("ruleBody"), a.get("table"));
    }

    private static Long number(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> argsOf(JsonNode args) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null || !args.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = args.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String as = value.isValueNode() ? value.asText() : value.toString();
            if (as != null && !as.isBlank()) {
                out.put(field.getKey(), as);
            }
        }
        return out;
    }

    private static String firstText(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.path(key);
            if (node.isTextual() || node.isNumber()) {
                String text = node.asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 提示词

    private String render() {
        List<ToolSpec> specs = tools.specs();
        if (specs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\n## 可用工具（调用即执行，参数名就是这个工具真正认的键）\n");
        for (ToolSpec spec : specs()) {
            sb.append("- ").append(spec.name());
            String args = argNames(spec);
            if (!args.isEmpty()) {
                sb.append("（args: ").append(args).append("）");
            }
            String description = firstSentence(spec.description());
            if (!description.isEmpty()) {
                sb.append("：").append(description);
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("## 输出格式（三选一，只输出一个 JSON，不要 markdown、不要解释）\n");
        sb.append("1. 要动系统里的真实数据：{\"action\":\"tool\",\"name\":\"recon_list_diffs\",")
                .append("\"args\":{\"jobId\":12},\"reason\":\"看这个任务的差异\"}\n");
        sb.append("2. 只需要说话/反问（这一轮不执行任何动作）：{\"action\":\"reply\",\"text\":\"你指的是哪两张表？\"}\n");
        sb.append("3. 这件事已经聊完：{\"action\":\"done\"}\n");
        sb.append("能直接回答的就 reply，不要为了用工具而用工具；能用一个工具拿到的，别拆成两个。\n");
        return sb.toString();
    }

    /** 从 inputSchema 里读参数名，不在本类另抄一份。 */
    private String argNames(ToolSpec spec) {
        String schema = spec.inputSchema();
        if (schema == null || schema.isBlank()) {
            return "";
        }
        try {
            JsonNode properties = json.readTree(schema).path("properties");
            if (!properties.isObject()) {
                return "";
            }
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            return String.join(" / ", names);
        } catch (Exception e) {
            // schema 坏了只是少一行提示，不该让整个提示词拼不出来
            log.debug("工具 {} 的 inputSchema 读不出参数名", spec.name(), e);
            return "";
        }
    }

    private static String firstSentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        int stop = -1;
        for (String mark : new String[] {"。"}) {
            int at = flat.indexOf(mark);
            if (at > 0 && (stop < 0 || at < stop)) {
                stop = at;
            }
        }
        String first = stop > 0 ? flat.substring(0, stop) : flat;
        return first.length() <= MAX_DESCRIPTION ? first : first.substring(0, MAX_DESCRIPTION) + "…";
    }

    /**
     * 一次工具选择的结果。
     *
     * <p>三个静态工厂对应三种出口；{@code name} 与 {@code reply} 同时为空即"结束"。
     */
    public record Decision(String name, Map<String, String> args, String reply) {

        public static Decision tool(String name, Map<String, String> args) {
            return new Decision(name, args == null ? Map.of() : Map.copyOf(args), null);
        }

        public static Decision reply(String text) {
            return new Decision(null, Map.of(), text);
        }

        public static Decision done() {
            return new Decision(null, Map.of(), null);
        }

        /** 收尾时模型愿意补一句话就用它（不强制，协议里 text 是可选的）。 */
        public static Decision done(String text) {
            return new Decision(null, Map.of(), text == null || text.isBlank() ? null : text);
        }

        public boolean isTool() {
            return name != null;
        }

        public boolean isReply() {
            return reply != null;
        }

        public boolean isDone() {
            return name == null && reply == null;
        }
    }

    /** 空注册表：只给"没有工具"的装配用，任何查找都落空。 */
    private static final class EmptyRegistry implements ToolRegistry {
        @Override
        public List<ToolSpec> specs() {
            return List.of();
        }

        @Override
        public Optional<ToolSpec> find(String name) {
            return Optional.empty();
        }
    }
}
