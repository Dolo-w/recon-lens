package io.github.dolow.reconlens.eval;

import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务级端到端回归（清单 P0-5 / 7.4）。
 *
 * <p>跑的是<b>真引擎</b>：规则解析 → SQL 生成 → 归并匹配 → 字段比较 → 差异落库 → 分类汇总，
 * 整条链路没有一处是 mock。单元测试证明零件对，这个类证明装起来对。
 *
 * <p><b>它同时是发版门槛</b>（清单 7.6）：改规则解析、改比较器、改容差口径，
 * 只要打坏了一条业务语义，这里必然红。而普通单测可能全绿——因为它们的断言写的是"函数返回值"，
 * 不是"业务结论"。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        "recon.rules-location=classpath:eval/rules/",
        "spring.datasource.url=jdbc:h2:mem:eval_suite;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class EvalSuiteContractTest {

    @Autowired
    private ReconEngine engine;
    @Autowired
    private ReconRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("全部 eval 用例必须通过 —— 任何一条红都说明业务语义被改坏了")
    void allCasesPass() {
        List<EvalSuiteRunner.Task> tasks = EvalTaskLoader.loadFromClasspath("eval/tasks.yml");

        assertThat(tasks)
                .as("回归集不能是空集，也不能悄悄缩水 —— 空集全绿是最坏的假绿")
                .hasSizeGreaterThanOrEqualTo(30);

        EvalSuiteRunner.Report report =
                new EvalSuiteRunner(engine, repository, new H2EvalDatabase(jdbc)).runAll(tasks);

        report.outcomes().forEach(o -> System.out.printf("[eval] %s %-34s %s%n",
                o.passed() ? "PASS" : "FAIL", o.key(), o.passed() ? "" : o.detail()));

        System.out.printf("[eval] 通过 %d/%d（%.1f%%）%n",
                report.passed(), report.total(), report.passRate() * 100);

        assertThat(report.failures())
                .as("以下用例的业务结论与期望不符：\n%s", report.describeFailures())
                .isEmpty();
    }

    /** 真 H2 准备数据：每个用例重建两张表，用例之间零耦合。 */
    private record H2EvalDatabase(JdbcTemplate jdbc) implements EvalSuiteRunner.EvalDatabase {

        @Override
        public void resetTo(List<Map<String, Object>> left, List<Map<String, Object>> right) {
            recreate("eval_left");
            recreate("eval_right");
            insert("eval_left", left);
            insert("eval_right", right);
        }

        private void recreate(String table) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
            // 列类型与生产的数据源表同构：金额用 DECIMAL（绝不用 DOUBLE —— 那会引入浮点误差），
            // 状态用 VARCHAR，键用 VARCHAR 存（数字键由规则 keyType 决定转换口径）
            jdbc.execute("CREATE TABLE " + table
                    + " (biz_key VARCHAR(64), amount DECIMAL(20,6), status VARCHAR(32))");
        }

        private void insert(String table, List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                jdbc.update("INSERT INTO " + table + " (biz_key, amount, status) VALUES (?,?,?)",
                        str(row.get("biz_key")),
                        row.get("amount") == null ? null : new BigDecimal(String.valueOf(row.get("amount"))),
                        str(row.get("status")));
            }
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}
