package io.github.dolow.reconlens.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.dolow.reconlens.llm.Tokens;

/**
 * 一轮对话进提示词之前的预算账本：谁能进、各占多少、超了先砍谁。
 *
 * <p><b>为什么需要它</b>：改动前 token 预算散在四个常量里 —— 提示词的 {@code fitContext(1200)}
 * 是字符数、{@code PromptGuard} 的 6000 是字符数、{@code ChatSummaryService} 的 55% 是窗口比例、
 * 工具结果的截断又是另一套。四个数各自拍板的结果是：任何一处放宽，总预算就悄悄涨了，
 * 而且没人能回答"这一轮到底花在哪"。
 *
 * <p>这里只做一件事：给定各段文本，按固定顺序装配、按桶截断、按 {@link #CUT_ORDER} 丢弃，
 * 并如实报告砍了什么。它不调模型，也不猜 token——估算精度的责任在 {@code Tokens}。
 *
 * <p><b>P2 起分两档</b>（{@link Phase}）：正在<b>弄懂用户要什么</b>时，事实与历史给足，
 * 因为理解错了后面全错；已经在<b>执行/看结果</b>时只带这一步需要的，省下来给工具结果。
 * 这不是"省 token"，是"把额度花在会决定对错的那一步"。
 */
public final class TurnBudget {

    /** 系统提示（规则、输出格式）：永远不砍，砍掉模型就不知道自己要输出什么。 */
    public static final int SYSTEM = 800;
    /** 系统事实（规则目录、最近任务、表清单）：砍到只剩规则名，模型就开始编实体。 */
    public static final int FACTS = 1200;
    /** 对话历史：只放 user 行，砍它比砍事实安全。 */
    public static final int HISTORY = 1500;
    /** 滚动摘要：最老的信息，最先让位。 */
    public static final int SUMMARY = 600;
    /** 工具结果：单条最大，也最容易被"再来一页"取代。 */
    public static final int TOOL = 2000;
    /** 工具清单（名字/参数/描述）：模型要能选工具，就得看得见清单。 */
    public static final int CATALOG = 2000;

    /** 输入端总额度（不含模型自己写的回答）。老口径 = {@link Phase#ACTING}。 */
    public static final int TOTAL = SYSTEM + FACTS + HISTORY + SUMMARY + TOOL;

    /** 回答的额度。它不进提示词，只用于判断"这一轮会不会太啰嗦"。 */
    public static final int REPLY = 1200;

    /**
     * 超预算时的腾位顺序：先砍工具结果（可以用再调一次工具补回来），最后才动事实。
     *
     * <p>反过来的排法（先砍历史）看着"更省"，实际上是拿"上一句说的口径"换"这一句的细节"，
     * 在多轮里会直接导致答非所问。
     */
    public static final List<String> CUT_ORDER = List.of("tool", "summary", "history", "facts");

    /**
     * 一轮处在哪一档。
     *
     * <p>{@link #UNDERSTANDING}：还没产出工具调用 —— 正要把一句话理解成"做什么"，
     * 事实与历史是判据，给足 4200 + 4000；{@link #ACTING}：已经在执行/看结果 ——
     * 事实只需要够认实体，历史只需要够认指代，省下来的额度给工具结果。
     *
     * <p>工具清单（{@code tools} 段）单独算 {@link #CATALOG}，不计入这两档的总额：
     * 它是常量资产，不是用户内容；两档都要看得见它（回灌后还要能继续选工具）。
     */
    public enum Phase {
        UNDERSTANDING(12000, SYSTEM, 2000, SUMMARY, 4000, 4400),
        ACTING(TOTAL, SYSTEM, FACTS, SUMMARY, HISTORY, TOOL);

        private final int total;
        private final int system;
        private final int facts;
        private final int summary;
        private final int history;
        private final int tool;

        Phase(int total, int system, int facts, int summary, int history, int tool) {
            this.total = total;
            this.system = system;
            this.facts = facts;
            this.summary = summary;
            this.history = history;
            this.tool = tool;
        }

        public int total() {
            return total;
        }

        public int system() {
            return system;
        }

        public int facts() {
            return facts;
        }

        public int summary() {
            return summary;
        }

        public int history() {
            return history;
        }

        public int tool() {
            return tool;
        }

        /** 某一段在这一档的桶大小。不认识的段按总额算（等同于不单独限制）。 */
        public int cap(String section) {
            return switch (section) {
                case "system" -> system;
                case "facts" -> facts;
                case "summary" -> summary;
                case "history" -> history;
                case "tool" -> tool;
                case "tools" -> CATALOG;
                default -> total;
            };
        }
    }

    private static final Map<String, Integer> CAPS = Map.of(
            "system", SYSTEM, "facts", FACTS, "history", HISTORY, "summary", SUMMARY, "tool", TOOL);

    static final String CUT_MARK = "…[预算截断]";

    private TurnBudget() {
    }

    /** 装配结果：拼好的文本、各段实际占用、以及砍了谁（给日志和测试看）。 */
    public record Result(String text, Map<String, Integer> used, List<String> cuts, int tokens) {

        public boolean truncated() {
            return !cuts.isEmpty();
        }
    }

    /**
     * 按 {@code system → facts → summary → history → tool} 的顺序装配（{@link Phase#ACTING} 档）。
     *
     * <p>传进来的段可以缺（比如没有摘要），也可以超（单段超自己那桶就先截自己）。
     */
    public static Result assemble(Map<String, String> sections) {
        return assemble(sections, Phase.ACTING);
    }

    /** 同一套装配规则，桶大小按 {@code phase} 取。 */
    public static Result assemble(Map<String, String> sections, Phase phase) {
        Phase p = phase == null ? Phase.ACTING : phase;
        LinkedHashMap<String, String> kept = new LinkedHashMap<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        List<String> cuts = new ArrayList<>();
        for (String name : List.of("system", "facts", "summary", "history", "tools", "tool")) {
            if (!sections.containsKey(name)) {
                continue;
            }
            String text = sections.get(name);
            if (text == null || text.isBlank()) {
                continue;
            }
            int cap = p.cap(name);
            String fitted = fit(text, cap);
            if (!fitted.equals(text)) {
                cuts.add(name);
            }
            kept.put(name, fitted);
            used.put(name, Tokens.estimate(fitted));
        }
        // 单段都合规、合起来超了：按 CUT_ORDER 从后往前缩，每次砍一半，
        // 砍到放得下为止；实在放不下就把整段拿掉（只有 tool 会走到这一步）。
        int total = total(used) - used.getOrDefault("tools", 0);
        for (String name : CUT_ORDER) {
            if (total <= p.total()) {
                break;
            }
            String text = kept.get(name);
            if (text == null) {
                continue;
            }
            String halved = fit(text, Math.max(40, Tokens.estimate(text) / 2));
            kept.put(name, halved);
            used.put(name, Tokens.estimate(halved));
            total = total(used) - used.getOrDefault("tools", 0);
            if (!cuts.contains(name)) {
                cuts.add(name);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String text : kept.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text);
        }
        return new Result(sb.toString(), used, cuts, total(used));
    }

    /** 记账用的估算入口：全仓只此一个，避免「字符数 / 字节数 / token 数」三种口径混着算。 */
    public static int estimate(String text) {
        return Tokens.estimate(text);
    }

    /** 把一段文本压到 {@code maxTokens} 以内；超了就在末尾留一个明确的截断标记。 */
    public static String fit(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return text == null ? "" : text;
        }
        if (Tokens.estimate(text) <= maxTokens) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int cost = Tokens.estimate(ch);
            if (used + cost > maxTokens) {
                break;
            }
            sb.append(text.charAt(i));
            used += cost;
        }
        return sb.append(CUT_MARK).toString();
    }

    /** 历史取舍的结果：放进去的原文、放了几轮、因为放不下而整轮丢掉几轮。 */
    public record History(String text, int kept, int dropped) {

        public boolean truncated() {
            return dropped > 0;
        }
    }

    /**
     * 历史<b>按整轮</b>取舍：从最新一轮往回装，装不下就整轮不要。
     *
     * <p><b>为什么不按比例砍每一轮</b>：实测里最刺眼的失败是"用户自己那句话被截掉一半"——
     * 8 轮之后每轮只剩 60 token，提示词里出现半句 `…[预算截断]`，模型照着半句去理解，
     * 人却以为自己说清楚了。理解阶段宁可少记两轮，也不能记半轮：半句比没有更误导。
     *
     * <p>没有任何截断标记：丢就是丢，丢了几轮由 {@link History#dropped()} 如实告诉调用方。
     */
    public static History rounds(List<String> turns, int capTokens) {
        if (turns == null || turns.isEmpty() || capTokens <= 0) {
            return new History("", 0, turns == null ? 0 : turns.size());
        }
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            String turn = turns.get(i);
            if (turn == null || turn.isBlank()) {
                continue;
            }
            int cost = estimate(turn) + 1;
            if (used + cost > capTokens) {
                break;
            }
            kept.add(0, turn);
            used += cost;
        }
        return new History(String.join("\n", kept), kept.size(), turns.size() - kept.size());
    }

    private static int total(Map<String, Integer> used) {
        int sum = 0;
        for (int v : used.values()) {
            sum += v;
        }
        return sum;
    }
}
