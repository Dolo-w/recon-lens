package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.llm.PromptGuard;
import io.github.dolow.reconlens.store.ChatClaimRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话结论板：把「已经定下来的事」按主题记住，供提示词每轮读取。
 *
 * <p><b>它解决什么</b>：长会话压缩之后，摘要里可能只剩「提到过 BILL_VS_SETTLE」的索引，
 * 旧结论已经不可读；而用户后来又改了口。结论板不压过程：同一主题后写的覆盖先写的，
 * 读的时候只给最新一条，所以「早就说过、后来推翻」在提示词里永远是后者。
 *
 * <p><b>跨会话部分</b>：同一操作人、同一作用域的历史结论按「权威等级 → 时间」取最新，
 * 单独渲染成一段，排在当前会话结论之后（当前会话永远压过历史）。发现同级不同值时
 * 不替用户选，交给运行时反问。
 *
 * <p><b>它不解决什么</b>：自由文本的约定（"以后都按订单号连"）没有可靠的主题归属，
 * 这里宁可不记——记错了比不记更糟，会把一条旧话当成当前有效。
 */
public class ChatClaims {

    /**
     * 〔当前有效〕块的起始标记。
     *
     * <p>它是上下文文本里的一个约定：规划器裁剪长摘要时必须认出这一段并原样留下——
     * 结论板被截断，就等于"用户刚改的口"没告诉模型。
     *
     * <p>它<b>留在 SYS 块外面</b>：块外这一行是给人看的段落标题，也是 {@code fitContext}
     * 的切分基准；块内才是模型该当事实读的内容。以前整段只有这一行标题、没有边界，
     * 用户在自己的话里敲一句同样的标题就能冒充它——现在形状不同了，冒充不了。
     */
    public static final String HEADER = "〔当前有效〕后说的覆盖先说的；同一件事以〔已审批〕为准，〔草稿〕只是草案";

    /** 跨会话结论块的起始标记。同样不能被裁掉。 */
    public static final String SHARED_HEADER = "〔历史结论·同一操作人，当前会话优先〕";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatClaims.class);

    /** 提示词里只放最近 3 条：结论板是"别记错"，不是"多塞上下文"。 */
    private static final int MAX_LINES = 3;
    private static final int MAX_LINE = 180;

    private final ChatClaimRepository repository;
    private final boolean sharedEnabled;

    public ChatClaims(ChatClaimRepository repository) {
        this(repository, false);
    }

    public ChatClaims(ChatClaimRepository repository, boolean sharedEnabled) {
        this.repository = repository;
        this.sharedEnabled = sharedEnabled;
    }

    public boolean sharedEnabled() {
        return sharedEnabled;
    }

    /**
     * 记一条结论。主题相同的后写覆盖先写——这是"用户改口"的唯一表达方式。
     *
     * <p>写失败只记日志：结论板是辅助记忆，不能因为它写不进去，把一次成功的话轮变成错误。
     */
    public void record(long sessionId, String operator, String topic, String value, int seq) {
        record(sessionId, operator, topic, value, seq, ChatClaimRepository.AUTHORITY_DRAFT);
    }

    /** 记一条会话内的结论（不带操作人；只对本会话可见）。 */
    public void record(long sessionId, String topic, String value, int seq) {
        record(sessionId, null, topic, value, seq, ChatClaimRepository.AUTHORITY_DRAFT);
    }

    /**
     * 记一条结论并显式指定权威等级。
     *
     * <p>草稿落库是 {@code DRAFT_PENDING}（还没人签字），用户当面确认的口径是
     * {@code OPERATOR_STATED}，审批通过由 {@code promote} 写成 {@code HUMAN_APPROVED}。
     */
    public void record(long sessionId, String operator, String topic, String value, int seq, String authority) {
        if (sessionId <= 0 || topic == null || topic.isBlank() || value == null || value.isBlank()) {
            return;
        }
        try {
            repository.record(sessionId, operator, topic, value, seq, authority);
        } catch (RuntimeException e) {
            log.warn("结论板写入失败 session={} topic={}：{}", sessionId, topic, e.toString());
        }
    }

    /**
     * 原始结论（新的在前），给排查/接口用。
     *
     * <p>和 {@link #render(long)} 读的是同一份数据、同一个上限：开出去的视图如果比提示词里多，
     * 排查时就会看到"模型没看到的结论"，反而误导。
     */
    public List<ChatClaimRepository.ChatClaim> items(long sessionId) {
        try {
            return repository.recent(sessionId, MAX_LINES);
        } catch (RuntimeException e) {
            log.warn("结论板读取失败 session={}：{}", sessionId, e.toString());
            return List.of();
        }
    }

    /**
     * 给提示词用的〔当前有效〕块；没有结论时返回空串。
     *
     * <p>渲染在摘要之后、同一段文本里：模型看到的顺序是「压缩过的历史 → 此刻还算数的结论」，
     * 后者更近、更硬，冲突时自然压过前者。
     *
     * <p>每条都带权威等级（已审批 / 已确认 / 草稿）：以前只写"第 N 轮"，于是"用户随口确认的
     * 口径"和"人工审批过的口径"在提示词里长得一样，模型没法优先信后者；而跨会话那份
     * （{@link #renderShared}）一直是带等级的，两份视图不对称。
     */
    public String render(long sessionId) {
        List<ChatClaimRepository.ChatClaim> claims = items(sessionId);
        if (claims.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (ChatClaimRepository.ChatClaim claim : claims) {
            body.append("· ").append(label(claim.key())).append("：").append(clip(claim.value()))
                    .append("（第 ").append(claim.seq()).append(" 轮，")
                    .append(authorityLabel(claim.authority())).append("）\n");
        }
        return HEADER + "\n" + PromptGuard.systemBlock("claims", body.toString().stripTrailing());
    }

    /**
     * 跨会话结论块；开关关闭或没有历史时返回空串。
     *
     * <p>作用域由调用方给：本轮提到的规则、表。同一 (scope, key) 只显示最新一条；
     * 冲突不在这里解决，{@link #conflict} 会报给运行时去问用户。
     */
    public String renderShared(String operator, List<String> scopes, long excludeSessionId) {
        if (!sharedEnabled || operator == null || operator.isBlank() || scopes == null || scopes.isEmpty()) {
            return "";
        }
        List<ChatClaimRepository.SharedClaim> history = shared(operator, scopes, excludeSessionId, MAX_LINES);
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(SHARED_HEADER).append('\n');
        for (ChatClaimRepository.SharedClaim claim : history) {
            sb.append("· ").append(label(claim.key())).append("：").append(clip(claim.value()))
                    .append("（会话 ").append(claim.sessionId()).append("，")
                    .append(authorityLabel(claim.authority())).append("）\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 一个作用域内的历史结论（新的在前）。 */
    public List<ChatClaimRepository.SharedClaim> shared(String operator, List<String> scopes, long excludeSessionId,
                                                        int limit) {
        try {
            return repository.shared(operator, scopes, excludeSessionId, limit);
        } catch (RuntimeException e) {
            log.warn("历史结论读取失败 operator={}：{}", operator, e.toString());
            return List.of();
        }
    }

    /**
     * 同一作用域、同一语义键出现两种说法（同级不同值）时返回冲突；否则 null。
     *
     * <p>只比"最新两条"：历史里改过三次的，也只需要问用户最后这两次哪个算数。
     */
    public Conflict conflict(String operator, String scope, long excludeSessionId) {
        if (!sharedEnabled || operator == null || scope == null) {
            return null;
        }
        List<ChatClaimRepository.SharedClaim> rows = shared(operator, List.of(scope), excludeSessionId, 20);
        Map<String, List<ChatClaimRepository.SharedClaim>> byKey = new LinkedHashMap<>();
        for (ChatClaimRepository.SharedClaim row : rows) {
            byKey.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<ChatClaimRepository.SharedClaim>> entry : byKey.entrySet()) {
            List<ChatClaimRepository.SharedClaim> same = entry.getValue();
            if (same.size() < 2) {
                continue;
            }
            ChatClaimRepository.SharedClaim a = same.get(0);
            for (int i = 1; i < same.size(); i++) {
                ChatClaimRepository.SharedClaim b = same.get(i);
                if (a.authority().equals(b.authority()) && !a.value().equals(b.value())) {
                    return new Conflict(scope, entry.getKey(), a, b);
                }
            }
        }
        return null;
    }

    /** 两种说法的冲突：a 是按权威/时间取到的第一条，b 是同级里不同的另一条。 */
    public record Conflict(String scope, String key,
                           ChatClaimRepository.SharedClaim a, ChatClaimRepository.SharedClaim b) {

        public String describe() {
            return label(key) + " 历史上有两种说法：\n"
                    + "· " + a.value() + "（会话 " + a.sessionId() + "）\n"
                    + "· " + b.value() + "（会话 " + b.sessionId() + "）\n"
                    + "以哪个为准？（回「第一个」或「第二个」）";
        }
    }

    /** {@code draft:ORDER_STATUS_RECON} → 「ORDER_STATUS_RECON 起草参数」。 */
    private static String label(String key) {
        if (key != null && key.startsWith("draft:")) {
            return key.substring("draft:".length()) + " 起草参数";
        }
        if (key != null && key.startsWith("approved:v")) {
            return "已生效口径（" + key + "）";
        }
        return key;
    }

    private static String authorityLabel(String authority) {
        if (ChatClaimRepository.AUTHORITY_APPROVED.equals(authority)) {
            return "已审批";
        }
        if (ChatClaimRepository.AUTHORITY_STATED.equals(authority)) {
            return "已确认";
        }
        return "草稿";
    }

    private static String clip(String value) {
        String v = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return v.length() <= MAX_LINE ? v : v.substring(0, MAX_LINE - 1) + "…";
    }
}
