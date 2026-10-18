package io.github.dolow.reconlens.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 试算的<b>证据体</b>（落 {@code recon_rule.trial_report}，人可读、可审计）。
 *
 * <p>它和 {@code verdict/coverage} 的分工：后两者回答"能不能上"，这份报告回答"<b>凭什么</b>说能上"。
 * 少了它，审核人只能看到三个枚举值 —— 而"这条规则为什么 PASS"恰恰是最容易骗人的地方
 * （一个什么都不做的闸门，输出和真闸门一模一样）。
 *
 * @param checks T1–T7 的逐项结论，值形如 {@code PASS} / {@code FAIL: ...} / {@code N/A: 无基线}
 * @param upstreamTruncation 上游自带截断的命中明细，形如 {@code "left: LIMIT"}；空列表表示没有。
 *              单独立一个结构化字段而不是只写进 {@code checks}：它决定 {@code coverage} 为什么是
 *              {@code SAMPLE_PARTIAL}，而"哪些规则的试算其实是在截断样本上做的"是要能筛的
 * @param notes  实装期的已知缺口（例如"collation 不一致时未实现半连接，直接降级"），
 *               写进报告而不是留在线程里 —— 下一次评审应该看得到它
 */
public record TrialReport(String code,
                          int version,
                          String gateVersion,
                          String baseVersion,
                          Window window,
                          TrialOutcome.SampleStats sample,
                          List<String> upstreamTruncation,
                          TrialOutcome.Canary canary,
                          Map<String, String> checks,
                          List<String> notes,
                          Instant createdAt) {

    /** 采样窗口。{@code lo}/{@code hi} 是两侧并集，不是交集 —— 交集恰好裁掉最该看的两端。 */
    public record Window(String lo, String hi, int leftRows, int rightRows,
                         String tableLeft, String tableRight) {
    }

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("code", code);
        root.put("version", version);
        root.put("gateVersion", gateVersion);
        root.put("baseVersion", baseVersion);
        root.put("createdAt", createdAt == null ? null : createdAt.toString());
        if (window != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("lo", window.lo());
            w.put("hi", window.hi());
            w.put("leftRows", window.leftRows());
            w.put("rightRows", window.rightRows());
            w.put("tableLeft", window.tableLeft());
            w.put("tableRight", window.tableRight());
            root.put("window", w);
        }
        if (sample != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("leftRows", sample.leftRows());
            s.put("rightRows", sample.rightRows());
            s.put("commonKeys", sample.commonKeys());
            s.put("shardsWithData", sample.shardsWithData());
            s.put("shardCount", sample.shardCount());
            s.put("baselineShardsWithData", sample.baselineShardsWithData());
            s.put("baselineDiffCount", sample.baselineDiffCount());
            s.put("newDiffCount", sample.newDiffCount());
            root.put("sample", s);
        }
        root.put("upstreamTruncation", upstreamTruncation == null ? List.of() : upstreamTruncation);
        if (canary != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("runnable", canary.runnable());
            c.put("passed", canary.passed());
            c.put("detail", canary.detail());
            root.put("canary", c);
        }
        root.put("checks", checks == null ? Map.of() : checks);
        root.put("notes", notes == null ? List.of() : notes);
        return root;
    }
}
