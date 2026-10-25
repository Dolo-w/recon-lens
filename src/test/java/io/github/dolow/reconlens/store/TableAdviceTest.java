package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 起草建议：只从列名与类型推断，一次数据行都不读。
 *
 * <p>这段的定位是"给个起点"而不是"替人定规则"，所以测试守的不是"建议对不对"，
 * 而是三条边界：<b>理由必须写出来</b>、<b>另一侧对不上要说出来</b>、
 * <b>骨架必须是最小可跑集合</b>（写了不该写的东西，读的人会以为那些是必须的）。
 */
class TableAdviceTest {

    private TableInspector inspector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:table_advice;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS adv_order");
        jdbc.execute("DROP TABLE IF EXISTS adv_settle");
        jdbc.execute("DROP TABLE IF EXISTS adv_no_key");
        jdbc.execute("""
                CREATE TABLE adv_order (
                  id BIGINT NOT NULL PRIMARY KEY,
                  order_no VARCHAR(32),
                  customer_id VARCHAR(32),
                  amount DECIMAL(18,2),
                  fee DECIMAL(18,2),
                  created_at TIMESTAMP
                )
                """);
        // 右侧少 fee 一列、多一个 amount2：故意制造"两侧字段不一致"
        jdbc.execute("""
                CREATE TABLE adv_settle (
                  id BIGINT NOT NULL PRIMARY KEY,
                  order_no VARCHAR(32),
                  customer_id VARCHAR(32),
                  amount DECIMAL(18,2),
                  amount2 DECIMAL(18,2)
                )
                """);
        jdbc.execute("CREATE TABLE adv_no_key (id BIGINT NOT NULL PRIMARY KEY, note VARCHAR(32))");
        inspector = new TableInspector(jdbc, new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(RegisteredTableRepository.class));
    }

    @Test
    @DisplayName("建议从列名与类型推出来_而且每条都带理由")
    void 建议带理由() {
        TableInspector.Advice advice = inspector.advice("adv_order", null);

        assertThat(advice.dimensions()).extracting(TableInspector.Suggestion::column)
                .contains("order_no", "customer_id")
                // 技术列不参与：id / created_at 对账没意义
                .doesNotContain("id", "created_at");
        assertThat(advice.compares()).extracting(TableInspector.Suggestion::column)
                .contains("amount", "fee");
        assertThat(advice.dimensions()).allSatisfy(item -> assertThat(item.why()).isNotBlank());
        assertThat(advice.compares()).allSatisfy(item -> assertThat(item.why()).isNotBlank());
    }

    @Test
    @DisplayName("给了另一侧_只留两侧同名的数值列_并把对方没有的说出来")
    void 交集与差异() {
        TableInspector.Advice advice = inspector.advice("adv_order", "adv_settle");

        assertThat(advice.compares()).extracting(TableInspector.Suggestion::column).containsExactly("amount");
        assertThat(advice.notes()).anySatisfy(note -> assertThat(note).contains("只有本侧有的数值列").contains("fee"));
    }

    @Test
    @DisplayName("另一侧不存在_交集算不出来要说出来_而不是给一份跑不通的骨架")
    void 另一侧读不到() {
        TableInspector.Advice advice = inspector.advice("adv_order", "adv_not_exist");

        assertThat(advice.notes()).anySatisfy(note -> assertThat(note).contains("交集未计算"));
        // 本侧建议不受影响：读不到对面不等于本侧没东西可看
        assertThat(advice.compares()).extracting(TableInspector.Suggestion::column).contains("amount");
    }

    @Test
    @DisplayName("骨架是最小可跑集合_带齐 code与两侧与三组差异类型")
    void 骨架最小可跑() {
        TableInspector.Advice advice = inspector.advice("adv_order", "adv_settle");

        assertThat(advice.snippet())
                .contains("code: NEW_RECON")
                .contains("table: adv_order")
                .contains("table: adv_settle")
                .contains("dimension: [order_no, customer_id]")
                .contains("field: amount")
                .contains("leftMissingDiffType: MISSING_LEFT")
                .contains("rightMissingDiffType: MISSING_RIGHT")
                .contains("duplicate:")
                // 分片刻意留 1：自动生成取模条件比不生成危险
                .contains("count: 1");
    }

    @Test
    @DisplayName("没给另一侧时_骨架里留占位符而不是瞎填")
    void 没有另一侧留占位() {
        TableInspector.Advice advice = inspector.advice("adv_order", null);

        assertThat(advice.snippet()).contains("table: <右侧表>");
    }

    @Test
    @DisplayName("没有业务键列_要说出来_不要编一个维度")
    void 没有业务键() {
        TableInspector.Advice advice = inspector.advice("adv_no_key", null);

        assertThat(advice.dimensions()).isEmpty();
        assertThat(advice.notes()).anySatisfy(note -> assertThat(note).contains("没找到像业务键的列"));
        // 没建议也要给可编辑的骨架，否则用户还得从零抄
        assertThat(advice.snippet()).contains("dimension: [id]");
    }

    @Test
    @DisplayName("表名不合法_直接拒绝而不是拼进SQL")
    void 坏表名() {
        assertThatThrownBy(() -> inspector.advice("adv_order; DROP TABLE adv_settle", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名");
    }
}
