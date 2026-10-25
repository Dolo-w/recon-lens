package io.github.dolow.reconlens.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给一条新规则起个**像人话的编码**。
 *
 * <p><b>为什么需要它</b>：编码是这条规则在审批单、任务记录、审计里的唯一标识。
 * 如果三条规则都叫 {@code AUTO_BILL_LEFT_BILL_RIGHT_2/_3}，用户和模型都分不清谁是谁——
 * "认不出哪个草稿"这个问题，一半来自名字里没有信息。
 *
 * <p><b>为什么让模型起而不是拼表名</b>：表名只说明"从哪来"，说不清"对什么"。
 * 用户说的是"订单和状态对账"，模型能译成 {@code ORDER_STATUS_RECON}；
 * 按表名拼只能得到 {@code AUTO_BILL_LEFT_BILL_RIGHT}，三条规则会撞成 {@code _2 _3}。
 *
 * <p><b>模型只出建议，最终由代码裁决</b>：格式必须过白名单正则，重名由 {@link #unique} 加序号。
 * 模型给个中文名、给个 SQL 片段、给个不存在的表名，都在这里被挡掉。
 */
public final class RuleNamer {

    private static final Logger log = LoggerFactory.getLogger(RuleNamer.class);

    /** 与 {@code RuleParser} 同一口径：大写字母开头，字母数字下划线，总长 3–40。 */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,39}");

    private static final String SYSTEM = """
            你给一条对账规则起编码。要求：
            1. 只用大写字母、数字、下划线，以字母开头，长度 4–30；
            2. 英文语义，能看出一眼对的是什么，例如 ORDER_STATUS_RECON、CUSTOMER_AMOUNT_RECON；
            3. 不要用 AUTO_ 前缀，不要用表名直接拼接；
            4. 只输出 JSON：{"code":"..."}，不要解释、不要 markdown。
            """;

    private final LlmChatClient client;
    private final ObjectMapper json;

    public RuleNamer(LlmChatClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public boolean configured() {
        return client != null && client.configured();
    }

    /**
     * 让模型给个编码建议。任何一步不成立都返回空，调用方退回 {@code AUTO_} 方案——
     * 起名是"锦上添花"，不能因为它失败让起草卡住。
     */
    public Optional<String> propose(String userMessage, String left, String right, String key, String compare) {
        if (!configured()) {
            return Optional.empty();
        }
        String prompt = "用户原话：" + (userMessage == null ? "（无）" : userMessage.trim())
                + "\n左表：" + left + "，右表：" + right
                + "\n对账维度：" + key + "，比对字段：" + compare;
        Optional<String> raw = client.completeJson(SYSTEM, prompt);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = json.readTree(raw.get().trim());
            String code = root.path("code").asText("").trim().toUpperCase(Locale.ROOT);
            return CODE.matcher(code).matches() ? Optional.of(code) : Optional.empty();
        } catch (Exception e) {
            log.debug("起名结果不是合法 JSON：{}", raw.get());
            return Optional.empty();
        }
    }

    /**
     * 唯一化：清洗成合法标识符，重名加序号。
     *
     * <p>不加序号就落库会被唯一索引拒（或更糟：覆盖到别的规则上），
     * 而"同名不同物"恰恰是这个场景最不能出的错。
     */
    public static String unique(String proposal, Collection<String> existing) {
        String base = sanitize(proposal);
        String code = base;
        int suffix = 2;
        while (containsIgnoreCase(existing, code)) {
            code = base + "_" + suffix++;
        }
        return code;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String code) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    /** 清洗：非字母数字下划线一律替换，长度截到 40，保证以字母开头。 */
    static String sanitize(String raw) {
        String text = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        text = text.replaceAll("[^A-Z0-9_]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (text.isEmpty()) {
            return "SESSION_RECON";
        }
        if (!Character.isLetter(text.charAt(0))) {
            text = "R_" + text;
        }
        return text.length() > 40 ? text.substring(0, 40) : text;
    }
}
