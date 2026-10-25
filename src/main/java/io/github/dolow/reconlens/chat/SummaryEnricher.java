package io.github.dolow.reconlens.chat;

import io.github.dolow.reconlens.chat.plan.LlmChatClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摘要的"归纳层"：在截断版前面补一段〔结论〕/〔未决〕。
 *
 * <p><b>为什么不是"把截断换成模型"</b>：截断版是三个不变量的载体——没有 key 也能用（N4 同理）、
 * 结果可复现、压缩不改审计（N3/N6）。把它整个换掉，等于拿掉兜底。所以这里是<b>叠加</b>：
 * 模型只写"结论/未决"两段，事实/坐标/被否决仍是代码生成的原文。
 *
 * <p><b>为什么模型只能写这两段</b>：它最容易做对的是"这几轮聊出了什么结论"，最容易做错的是
 * id、任务号、规则编码——后者写错一个，复盘时就会照着错的线索去翻原文。
 *
 * <p><b>校验是硬门槛</b>（{@link #guard}）：模型段里出现的规则编码/任务号必须真的在材料里出现过，
 * 否则整段丢弃、退回纯截断。宁可少归纳，不要多编造。
 */
public final class SummaryEnricher {

    private static final Logger log = LoggerFactory.getLogger(SummaryEnricher.class);

    /** 规则编码（大写标识符）与任务号（#12）——这两类 id 写错最贵。 */
    private static final Pattern RULE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,40}");
    private static final Pattern JOB_ID = Pattern.compile("#\\s*\\d{1,9}");
    /** 模型段上限：它只是"读起来先有个结论"，不是正文。 */
    private static final int MAX_MODEL_CHARS = 300;

    private static final String SYSTEM = """
            你在压缩一段对账会话的摘要。只输出两段，用固定标题：
            〔结论〕：1-3 条已经定下来的事，每行以 "- " 开头
            〔未决〕：0-2 条还没确认的问题，每行以 "- " 开头
            要求：
            1. 只使用材料里出现过的规则编码（如 BILL_VS_SETTLE）和任务号（如 #12），不许编造；
            2. 不要复述材料原句，要说"结论"；
            3. 总长不超过 200 字，不要 markdown 代码块。
            """;

    private final LlmChatClient client;
    private final boolean enabled;

    public SummaryEnricher(LlmChatClient client, boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    public boolean active() {
        return enabled && client != null && client.configured();
    }

    /**
     * 给截断版加一段归纳。<b>任何一步不成立都返回原截断版</b>——调用方不需要判断。
     */
    public String enrich(String truncated, List<ChatTurn> turns) {
        if (!active() || truncated == null || truncated.isBlank() || turns == null || turns.isEmpty()) {
            return truncated;
        }
        // 事实行由代码生成，永远在最前面：id 是复盘时最贵的线索，不能指望摘要正文或模型记得住。
        // 实测：没有这一行时，任务号 #2 在压缩后彻底消失，模型只能猜。
        String facts = factsLine(turns);
        Optional<String> model = client.completeText(SYSTEM, material(turns), 320);
        String composed = model.isEmpty() ? truncated : compose(truncated, model.get(), turns);
        return facts.isBlank() ? composed : facts + "\n" + composed;
    }

    /** 业务键（差异里的 `C005|ORD20260006` 这种）：复盘时"这单怎么处理的"全靠它定位。 */
    private static final Pattern DIFF_KEY = Pattern.compile("[A-Z]\\d{2,4}\\|[A-Z]{2,}\\d{4,}");

    /** 代码生成的事实行：**只记本次**（最近一次"跑一下"之后的）规则编码 + 任务号 + 业务键。 */
    static String factsLine(List<ChatTurn> turns) {
        // 一次会话里可能跑了多次、还起草了别的规则、试算又建了任务。
        // 早先这里"把出现过的 id 各取前 3 个"，结果是：规则清单里的别的规则、试算的新任务号
        // 全被写进事实行，模型回答"这次跑的是哪条"时六成会挑错（实测 38% 命中）。
        // 现在只从**最近一次跑账**开始算——那之后出现的 id 才属于"这一次"。
        int start = 0;
        for (int i = 0; i < turns.size(); i++) {
            ChatTurn turn = turns.get(i);
            if ("user".equalsIgnoreCase(turn.role()) && turn.content() != null
                    && turn.content().trim().startsWith("跑一下")) {
                start = i;
            }
        }
        List<ChatTurn> scope = turns.subList(start, turns.size());

        String runRule = null;
        if (start > 0 || (start == 0 && !turns.isEmpty())) {
            ChatTurn runTurn = turns.get(start);
            if (runTurn.content() != null && runTurn.content().trim().startsWith("跑一下")) {
                String rest = runTurn.content().trim().substring(3).trim();
                int space = rest.indexOf(' ');
                runRule = (space > 0 ? rest.substring(0, space) : rest).toUpperCase(Locale.ROOT);
            }
        }
        Set<String> codes = new LinkedHashSet<>();
        Set<String> jobs = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (ChatTurn turn : scope) {
            if (turn.content() == null) {
                continue;
            }
            Matcher j = JOB_ID.matcher(turn.content());
            while (j.find() && jobs.size() < 2) {
                jobs.add(j.group().replaceAll("\\s+", ""));
            }
            Matcher d = DIFF_KEY.matcher(turn.content());
            while (d.find() && keys.size() < 3) {
                keys.add(d.group());
            }
        }
        if (runRule != null && !runRule.isBlank()) {
            codes.add(runRule);
        }
        if (codes.isEmpty() && jobs.isEmpty() && keys.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("〔事实〕");
        if (!codes.isEmpty()) {
            sb.append("ruleCode=").append(String.join("/", codes));
        }
        if (!jobs.isEmpty()) {
            sb.append(codes.isEmpty() ? "" : "；").append("jobId=").append(String.join("/", jobs));
        }
        if (!keys.isEmpty()) {
            sb.append(codes.isEmpty() && jobs.isEmpty() ? "" : "；").append("diffKey=")
                    .append(String.join("/", keys));
        }
        return sb.toString();
    }

    /**
     * 纯函数：校验 + 拼装。抽出单独可见，是为了让"编造 id 要退货"这条能被单测钉住。
     */
    static String compose(String truncated, String modelText, List<ChatTurn> turns) {
        String cleaned = modelText == null ? "" : modelText.trim();
        if (cleaned.isEmpty()) {
            return truncated;
        }
        if (cleaned.length() > MAX_MODEL_CHARS) {
            // 按行截断：切在半句上比不切更糟（用户看到"但尚未确认是"这种半截结论，
            // 会以为摘要在胡说）。找不到换行才硬切。
            int cut = cleaned.lastIndexOf('\n', MAX_MODEL_CHARS);
            cleaned = cut > 0 ? cleaned.substring(0, cut) : cleaned.substring(0, MAX_MODEL_CHARS) + "…";
        }
        if (!guard(cleaned, turns)) {
            log.info("摘要归纳被拒：模型段里出现了材料里没有的规则编码/任务号");
            return truncated;
        }
        return cleaned + "\n" + truncated;
    }

    /**
     * 硬校验：模型段里出现的每个规则编码/任务号，必须在材料里出现过。
     *
     * <p>只查"编造"，不查"遗漏"——遗漏的后果是少一条结论，编造的后果是复盘时照着错的 id 去找。
     */
    static boolean guard(String modelText, List<ChatTurn> turns) {
        Set<String> known = new LinkedHashSet<>();
        StringBuilder all = new StringBuilder();
        for (ChatTurn turn : turns) {
            if (turn.content() != null) {
                all.append(turn.content()).append('\n');
            }
        }
        String material = all.toString();
        Matcher codes = RULE_CODE.matcher(material);
        while (codes.find()) {
            known.add(codes.group());
        }
        Matcher jobs = JOB_ID.matcher(material);
        while (jobs.find()) {
            known.add(jobs.group().replaceAll("\\s+", ""));
        }
        Matcher modelCodes = RULE_CODE.matcher(modelText);
        while (modelCodes.find()) {
            if (!known.contains(modelCodes.group())) {
                return false;
            }
        }
        Matcher modelJobs = JOB_ID.matcher(modelText);
        while (modelJobs.find()) {
            if (!known.contains(modelJobs.group().replaceAll("\\s+", ""))) {
                return false;
            }
        }
        return true;
    }

    private static String material(List<ChatTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : turns) {
            sb.append(turn.role()).append(": ").append(turn.content()).append('\n');
            if (sb.length() > 4000) {
                break;
            }
        }
        return sb.toString();
    }
}
