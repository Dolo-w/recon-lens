package io.github.dolow.reconlens.eval;

import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务级 eval 执行器（清单 P0-5 / 7.4）。
 *
 * <p><b>它解决什么问题</b>：在这之前项目有 1000+ 单元测试，都绿，但没人能回答
 * "系统整体跑一批对账任务，完成率和判定正确率是多少"。单元测试证明"每个零件对"，
 * 不证明"装起来对"——尤其判定的口径是跨越多层（规则解析 → SQL 生成 → 归并 → 比较 → 差异落库）
 * 才体现出来的。
 *
 * <p><b>为什么用真实引擎而不是 mock</b>：mock 掉任何一层，被测的就是我自己写的那层胶水，
 * 而不是系统。这里的价值恰恰在"整条链路真的跑一遍"。
 *
 * <p><b>解法与用例分离</b>：规则在 {@code eval/rules/}，数据与期望在 {@code eval/tasks.yml}。
 * 加一条用例只改数据文件，不动代码——这是回归集能长期活着的前提。
 */
public class EvalSuiteRunner {

    private final ReconEngine engine;
    private final ReconRepository repository;
    private final EvalDatabase db;

    public EvalSuiteRunner(ReconEngine engine, ReconRepository repository, EvalDatabase db) {
        this.engine = engine;
        this.repository = repository;
        this.db = db;
    }

    /** 跑全部用例。单个用例异常不中断整批——一条炸了要能看到其余 29 条的结果。 */
    public Report runAll(List<Task> tasks) {
        List<Outcome> outcomes = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            outcomes.add(runOne(task));
        }
        return new Report(outcomes);
    }

    public Outcome runOne(Task task) {
        try {
            db.resetTo(task.left(), task.right());
            ReconJob job = engine.run(task.rule(), "eval-" + task.key(), "eval suite");
            if (job == null || !"SUCCESS".equals(job.status().name())) {
                return new Outcome(task.key(), task.title(), false, -1, Map.of(),
                        "任务未成功：" + (job == null ? "job 为 null" : job.status() + " " + job.errorMsg()));
            }
            Map<String, Long> actual = repository.summaryByType(job.id());
            long actualTotal = actual.values().stream().mapToLong(Long::longValue).sum();

            List<String> problems = new ArrayList<>();
            if (actualTotal != task.expect().total()) {
                problems.add("差异总数期望 " + task.expect().total() + "，实际 " + actualTotal);
            }
            for (Map.Entry<String, Long> e : task.expect().types().entrySet()) {
                long got = actual.getOrDefault(e.getKey(), 0L);
                if (got != e.getValue()) {
                    problems.add(e.getKey() + " 期望 " + e.getValue() + "，实际 " + got);
                }
            }
            // 期望里没写的类型也应该为 0（防止"多报了别的类型"被漏掉）
            for (Map.Entry<String, Long> e : actual.entrySet()) {
                if (!task.expect().types().containsKey(e.getKey()) && e.getValue() > 0) {
                    problems.add("出现了期望外的差异类型 " + e.getKey() + "=" + e.getValue());
                }
            }

            return new Outcome(task.key(), task.title(), problems.isEmpty(), actualTotal, actual,
                    String.join("；", problems));
        } catch (RuntimeException e) {
            return new Outcome(task.key(), task.title(), false, -1, Map.of(), "执行异常：" + e);
        }
    }

    // ------------------------------------------------------------------
    // 数据契约
    // ------------------------------------------------------------------

    /** 一条用例：数据 + 期望。字段名与 YAML 一致。 */
    public record Task(String key, String title, String rule,
                       List<Map<String, Object>> left,
                       List<Map<String, Object>> right,
                       Expect expect) {
    }

    /** 期望结果：总数 + 分类型条数。 */
    public record Expect(long total, Map<String, Long> types) {
        public static Expect empty() {
            return new Expect(0, Map.of());
        }
    }

    /** 单条用例的判定结果。 */
    public record Outcome(String key, String title, boolean passed,
                          long actualTotal, Map<String, Long> actualTypes, String detail) {
    }

    /** 整批结果。 */
    public record Report(List<Outcome> outcomes) {

        public int total() {
            return outcomes.size();
        }

        public int passed() {
            return (int) outcomes.stream().filter(Outcome::passed).count();
        }

        public double passRate() {
            return total() == 0 ? 0.0 : (double) passed() / total();
        }

        public List<Outcome> failures() {
            return outcomes.stream().filter(o -> !o.passed()).toList();
        }

        /** 给测试失败时看的一行行明细：哪条挂了、期望什么、实际什么。 */
        public String describeFailures() {
            StringBuilder sb = new StringBuilder();
            for (Outcome o : failures()) {
                sb.append("  ✗ [").append(o.key()).append("] ").append(o.title())
                        .append("\n      ").append(o.detail()).append('\n');
            }
            return sb.toString();
        }

        /** 汇总（写入报告/日志用）。 */
        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total());
            m.put("passed", passed());
            m.put("failed", total() - passed());
            m.put("passRate", Math.round(passRate() * 10000) / 100.0);
            return m;
        }
    }

    /**
     * 数据准备抽象：让 Runner 不依赖 JdbcTemplate。
     *
     * <p>测试用真 H2；将来如果要在"预发环境跑真实规则集"复用这个 Runner，
     * 传另一个实现即可，不用改判定逻辑。
     */
    public interface EvalDatabase {
        /** 重建 eval_left / eval_right 并写入本用例数据。 */
        void resetTo(List<Map<String, Object>> left, List<Map<String, Object>> right);
    }
}
