package io.github.dolow.reconlens.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一段轮次压成几行"读过就能接上话"的摘要（ADR-0001 D2 / D7）。
 *
 * <p><b>为什么不调模型</b>：内建会话的第一条承诺是"没有 key 也能用"。摘要若依赖模型，
 * 那么不配 key 的部署就会在聊到第 30 轮时退化——上下文越用越少，最后开始答非所问，
 * 而用户完全看不出发生了什么。<b>摘要必须是一个纯函数</b>：同样的轮次永远压出同样的字，
 * 出问题时能复现、能对比、能改。
 *
 * <p>代价要说清楚：这不是"理解后的概括"，是<b>挑出可复述的骨架</b>——每轮谁说了什么、
 * 得出了什么结论。它不擅长总结"讨论过程的微妙转折"，但对"上一次跑到哪个任务、
 * 哪条规则被否了"这类事实，比模型更可靠（模型会把 2 说成 3）。
 *
 * <p>{@link #estimateTokens} 同理是估算：中文按 1 字 ≈ 1 token，ASCII 按 4 字符 ≈ 1 token。
 * 估错的后果只是压缩早/晚发生，不会丢数据，所以不值得为此引入一个 tokenizer 依赖。
 */
public final class ChatSummarizer {

    /** 摘要上限。超过就砍中间那部分——开头是背景、结尾是最近，中间最容易丢而不影响接话。 */
    public static final int MAX_SUMMARY_CHARS = 1_200;

    /** 单轮摘录长度：够看清"问了什么/得到什么"，又不至于把一条长解释整个搬进来。 */
    private static final int LINE_CHARS = 64;

    /**
     * 清单类回答（一句话里提到 ≥2 条规则编码）的摘录长度。
     *
     * <p>64 字把"规则库里有 3 条规则：A 比金额、B 比金额和订单数、C 比金额允许 0.5%"
     * 永远砍在第 2 条规则上——实测摘要保持率判分里，口径类探针的失分几乎都来自这里：
     * 摘要不是"没总结好"，是后半句根本没留下。清单回答的信息密度在每条规则的差异上，
     * 所以给它 220 字（三条规则各留一句），普通回答仍是 64 字。
     */
    private static final int CATALOG_CLIP_CHARS = 220;

    /** 一句话里提到几条不同的规则编码：用来识别"清单类回答"。 */
    private static final int CATALOG_MIN_CODES = 2;

    /** `· 问（12）：` / `· 答（12）：` 里的轮次号。 */
    private static final Pattern SEQ = Pattern.compile("[问答]（(\\d+)）");

    /** 规则名、差异类型、任务状态这类词：大写开头、可含数字与下划线。 */
    private static final Pattern IDENT = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,}\\b");

    /** `#7` 这种任务号。 */
    private static final Pattern JOB_REF = Pattern.compile("#\\d{1,18}");

    /** 「被否决 / 走不通」段的段标记。ADR-0001 N3 要求摘要必须留下被否决的方案。 */
    public static final String REJECT_MARK = "〔被否决〕";

    /**
     * 判断一轮回答是不是"这条路走不通"。
     *
     * <p>为什么要专门标出来：接手的人最怕重复踩同一个坑。摘要里只写"答了什么"，
     * 新人会再问一次"草稿能不能直接跑"，再被拒一次。被否决的一轮是<b>负面结论</b>，
     * 信息密度比普通问答高，而且最容易被行数裁剪挤掉（它往往在会话中段）。
     */
    private static final Pattern REJECT = Pattern.compile(
            "还没生效|不能|不允许|不存在|未找到|拒绝|失败|没有通过|不建议批|无法|"
                    + "BLOCK_HARD|BLOCK_SIGNABLE|NOT_APPROVABLE|INCONCLUSIVE");

    /** 被否决段最多留 3 条：再多就把摘要挤成日志。 */
    private static final int MAX_REJECTIONS = 3;

    /** 大写但不带信息的词，收进索引行只会占地方。 */
    private static final Set<String> NOISE = Set.of(
            "JSON", "API", "ID", "SSE", "SQL", "YAML", "MCP", "HTTP", "LLM", "REST",
            "OK", "PASS", "FAIL", "AND", "NOT", "THE");

    private ChatSummarizer() {
    }

    /**
     * 压缩结果。
     *
     * @param text    摘要正文
     * @param uptoSeq 已压进去的最大轮次号（水位线）；没有可压的轮次时返回 0 表示"别动水位"
     */
    public record Result(String text, int uptoSeq) {
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    /**
     * 把 {@code turns}（应当只包含水位线之后的轮次）压进摘要。
     *
     * @param previousSummary 上一次的摘要，可为 null
     * @param previousUpto    上一次的水位线
     * @param turns           水位线之后的轮次，按 seq 升序
     */
    public static Result summarize(String previousSummary, int previousUpto, List<ChatTurn> turns) {
        List<String> lines = new ArrayList<>();
        List<String> fresh = new ArrayList<>();
        int maxSeq = previousUpto;
        int lastUserSeq = -1;
        String lastUserText = null;
        for (ChatTurn turn : turns) {
            if (turn.seq() <= previousUpto) {
                continue;
            }
            String role = turn.role() == null ? "" : turn.role().toLowerCase();
            if ("user".equals(role)) {
                lastUserSeq = turn.seq();
                lastUserText = oneLine(turn.content() == null ? "" : turn.content().strip());
            } else if ("assistant".equals(role) && isRejection(turn.content())) {
                fresh.add(rejectionLine(lastUserSeq, lastUserText, turn));
            }
            String line = line(turn);
            if (line != null) {
                lines.add(line);
            }
            maxSeq = Math.max(maxSeq, turn.seq());
        }
        if (lines.isEmpty() && fresh.isEmpty()) {
            return new Result(previousSummary == null ? "" : previousSummary, previousUpto);
        }

        String previous = previousSummary == null ? "" : previousSummary;
        List<String> all = new ArrayList<>();
        String body = bodyOf(previous);
        if (!body.isBlank()) {
            all.addAll(List.of(body.strip().split("\n")));
        }
        if (!lines.isEmpty()) {
            all.add("〔第 " + (previousUpto + 1) + '–' + maxSeq + " 轮〕");
            all.addAll(lines);
        }
        String text = clip(all);
        List<String> rejected = mergeRejections(rejectionsOf(previous), fresh);
        if (!rejected.isEmpty()) {
            text = text + "\n" + REJECT_MARK + "\n" + String.join("\n", rejected);
        }
        if (text.length() > MAX_SUMMARY_CHARS) {
            // 兜底仍只砍开头：被否决段在最后，不能丢
            text = "…（摘要超长，开头已省略；完整内容见轮次表）…\n"
                    + text.substring(text.length() - MAX_SUMMARY_CHARS);
        }
        return new Result(text.strip(), maxSeq);
    }

    /**
     * 这句话算不算"被否决"。
     *
     * <p><b>只看首行</b>（前 80 字）：曾经是"在整段回答里找关键词"，结果任何带
     * "不能/失败/无法"的正常回答都被记成"用户提的方案被否决"——实测把
     * 「看差异」「为什么差这么多」两轮也记进去了。被否决是一种**答复姿态**，
     * 它一定在开头，不会埋在明细第 40 行。
     */
    private static boolean isRejection(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String headline = content.strip().split("\n", 2)[0];
        if (headline.length() > 80) {
            headline = headline.substring(0, 80);
        }
        return REJECT.matcher(headline).find();
    }

    /** `· 问（3）→ 答（4）：这个动作没做成：…`；问句缺失时退化成只看回答。 */
    private static String rejectionLine(int userSeq, String userText, ChatTurn answer) {
        String why = oneLine(firstMeaningfulLine(answer.content() == null ? "" : answer.content()));
        String prefix = userSeq > 0
                ? "· 问（" + userSeq + "）→ 答（" + answer.seq() + "）："
                : "· 答（" + answer.seq() + "）：";
        return prefix + why;
    }

    /** 摘要正文（`〔被否决〕` 之前的部分）。上一轮的否决段要摘出来，避免越叠越长。 */
    private static String bodyOf(String summary) {
        int at = summary.indexOf(REJECT_MARK);
        return at < 0 ? summary : summary.substring(0, at);
    }

    private static List<String> rejectionsOf(String summary) {
        int at = summary.indexOf(REJECT_MARK);
        if (at < 0) {
            return List.of();
        }
        String tail = summary.substring(at + REJECT_MARK.length()).strip();
        if (tail.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : tail.split("\n")) {
            String s = line.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 老的新的一起排，去重，只留最后 {@link #MAX_REJECTIONS} 条（最近踩的坑最有用）。 */
    private static List<String> mergeRejections(List<String> carried, List<String> fresh) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.addAll(carried);
        seen.addAll(fresh);
        List<String> all = new ArrayList<>(seen);
        return all.size() <= MAX_REJECTIONS
                ? all
                : all.subList(all.size() - MAX_REJECTIONS, all.size());
    }

    /** 一轮 → 一行。工具轮不单独成行（它的产出已经在对应助手轮的正文里）。 */
    private static String line(ChatTurn turn) {
        String content = turn.content() == null ? "" : turn.content().replace("\r", "").strip();
        if (content.isEmpty()) {
            return null;
        }
        String role = turn.role() == null ? "" : turn.role().toLowerCase();
        return switch (role) {
            case "user" -> "· 问（" + turn.seq() + "）：" + oneLine(content);
            case "assistant" -> "· 答（" + turn.seq() + "）：" + firstMeaningfulLine(content, lineCap(content));
            case "tool" -> null;
            default -> "· " + role + "（" + turn.seq() + "）：" + oneLine(content);
        };
    }

    /**
     * 助手回答经常是"标题 + 空行 + 明细"，第一行往往最有信息量。若第一行太长（比如一整段
     * 规则说明），退而取第一条含结论字样的行，都没有就用第一行截断。
     */
    /**
     * 取首行当摘要行。
     *
     * <p>原来的写法是"第一个长度 ≤ LINE_CHARS 的行"——它会**跳过太长的第一行**去找一个短行。
     * 加上人话总结之后，第一行（结论）通常很长，摘要行于是落在分隔符「—— 明细 ——」上。
     * 现在改成：取第一行非空，超长就截断。
     */
    private static String firstMeaningfulLine(String content) {
        return firstMeaningfulLine(content, LINE_CHARS);
    }

    private static String firstMeaningfulLine(String content, int cap) {
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            return line.length() <= cap ? line : line.substring(0, cap) + "…";
        }
        return content;
    }

    /** 清单类回答给更长的摘录，普通回答 64 字；判断只看这句话里出现了几条不同的规则编码。 */
    private static int lineCap(String content) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(content);
        while (m.find()) {
            String token = m.group();
            if (!NOISE.contains(token)) {
                codes.add(token);
            }
        }
        return codes.size() >= CATALOG_MIN_CODES ? CATALOG_CLIP_CHARS : LINE_CHARS;
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= LINE_CHARS ? flat : flat.substring(0, LINE_CHARS) + "…";
    }

    /**
     * 行数超了就砍中间：保住开头（这条会话在聊什么）与结尾（最近发生的事）。
     *
     * <p>为什么按<b>行</b>而不是按字符砍：字符级截断会把最新那段的小标题（{@code 〔第 13–18 轮〕}）
     * 一起砍掉——那是实测踩到的坑，摘要看起来"压过了"却找不到最近发生了什么。按行砍，
     * 保证最近 10 行完整存在，新段落一定在里面。
     */
    private static String clip(List<String> lines) {
        int head = 4;
        int tail = 10;
        List<String> out;
        if (lines.size() <= head + tail) {
            out = lines;
        } else {
            List<String> dropped = lines.subList(head, lines.size() - tail);
            out = new ArrayList<>(head + tail + 1);
            out.addAll(lines.subList(0, head));
            out.add(marker(dropped));
            out.addAll(lines.subList(lines.size() - tail, lines.size()));
        }
        String text = String.join("\n", out);
        if (text.length() > MAX_SUMMARY_CHARS) {
            // 兜底再按字符砍，仍然只砍开头：新内容永远在尾部
            text = "…（摘要超长，开头已省略；完整内容见轮次表）…\n"
                    + text.substring(text.length() - MAX_SUMMARY_CHARS);
        }
        return text.strip();
    }

    /**
     * 被砍掉那段的占位行——不是干巴巴一句"略去 N 行"，而是把这一段的**坐标和关键词**留下来。
     *
     * <p>理由是实测里发现的：摘要只留头尾，中间那段就变成了"不知道发生过什么"。
     * 知识还在 {@code recon_chat_turn} 里，但人不会为了找一句话去几十页里翻。
     * 留一行"第 12–34 轮，提到 BILL_VS_SETTLE、#7"，等于给这段历史留了个**索引**：
     * 知道大概在哪、知道该搜什么，翻起来才有方向。这一行仍然由纯函数算出，不改不可复现的性质。
     */
    private static String marker(List<String> dropped) {
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        for (String line : dropped) {
            int seq = seqOf(line);
            if (seq > 0) {
                lo = Math.min(lo, seq);
                hi = Math.max(hi, seq);
            }
        }
        StringBuilder out = new StringBuilder("…（略去 ").append(dropped.size()).append(" 行");
        if (hi > 0) {
            out.append("，第 ").append(lo).append('–').append(hi).append(" 轮");
        }
        List<String> keys = keywords(dropped);
        if (!keys.isEmpty()) {
            out.append("；其中提到 ").append(String.join("、", keys));
        }
        return out.append("；完整原文见轮次表）…").toString();
    }

    /** 从 `· 问（12）：…` 里取出 12；取不到返回 -1。 */
    private static int seqOf(String line) {
        Matcher m = SEQ.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 抽"翻回去时用得上的词"：规则名/任务号这类大写标识符 + {@code #数字} 任务号。
     *
     * <p>只取 3 个标识符和 2 个任务号：这是一行索引，不是全文检索，给太多反而看不清。
     * 顺序按"先出现"排，所以同样的输入永远得到同样的输出。
     */
    private static List<String> keywords(List<String> dropped) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashSet<String> jobs = new LinkedHashSet<>();
        for (String line : dropped) {
            Matcher ids = IDENT.matcher(line);
            while (ids.find()) {
                String token = ids.group();
                if (NOISE.contains(token)) {
                    continue;
                }
                seen.add(token);
                counts.merge(token, 1, Integer::sum);
            }
            Matcher refs = JOB_REF.matcher(line);
            while (refs.find() && jobs.size() < 2) {
                jobs.add(refs.group());
            }
        }
        List<String> top = counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
        List<String> out = new ArrayList<>(top);
        out.addAll(jobs);
        return out;
    }

    /**
     * token 估算：中日韩字符按 1 算，其它按 4 字符 1 个算。
     *
     * <p>只用于"该不该压缩"这个判断，不用于计费，所以宁可粗一点也不要引入 tokenizer。
     * 实现搬到了 {@link io.github.dolow.reconlens.llm.Tokens} —— 提示词装配、预算账本、
     * 摘要触发三处要用同一把尺子，否则"按预算截断"和"按预算触发压缩"会得出两个结论。
     */
    public static int estimateTokens(String text) {
        return io.github.dolow.reconlens.llm.Tokens.estimate(text);
    }
}
