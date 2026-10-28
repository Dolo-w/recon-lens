package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「口述一条规则」的解析契约。
 *
 * <p>这组用例守的是<b>一次只收一样、收过的不能丢</b>：用户不会把四样东西说在同一句里，
 * 而每漏一样就会多一轮反问。所以断言不只看"能不能认出来"，还看"跨轮合并之后还缺什么"。
 */
class DraftIntakeTest {

    private static final List<String> TABLES = List.of("bill_left", "bill_right", "customer");

    @Test
    @DisplayName("回答上一问的裸列名也算数：order_no 填业务键，金额 填比对字段")
    void 裸列名回答上一问() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");

        hints = DraftIntake.answer("order_no", hints, "业务键（左+右）", COLUMNS);
        assertThat(hints.key()).isEqualTo("order_no");

        hints = DraftIntake.answer("金额", hints, "比对字段（左+右）", COLUMNS);
        assertThat(hints.compare()).isEqualTo("amount");
        assertThat(hints.complete()).isTrue();
    }

    @Test
    @DisplayName("回答里认不出列名时不乱填，宁可继续问")
    void 裸列名认不出就不填() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");
        DraftIntake.Hints same = DraftIntake.answer("我也说不清，你看着办吧", hints, "业务键（左+右）", COLUMNS);
        assertThat(same).isEqualTo(hints);
    }

    @Test
    @DisplayName("句尾「对」也算对账：就这两张，用状态和订单对")
    void 句尾对也算() {
        DraftIntake.Hints hints = DraftIntake.parse("就这两张，用状态和订单对", TABLES, DraftIntake.Hints.EMPTY);
        assertThat(hints.hint()).isEqualTo("状态和订单");
    }

    @Test
    @DisplayName("一句话说全：左表、右表、业务键、比对字段都能认出来")
    void 一句话说全() {
        DraftIntake.Hints hints = DraftIntake.parse(
                "左 bill_left，右 bill_right，按 order_no 连，比 amount", TABLES, DraftIntake.Hints.EMPTY);

        assertThat(hints.complete()).isTrue();
        assertThat(hints.left()).isEqualTo("bill_left");
        assertThat(hints.right()).isEqualTo("bill_right");
        assertThat(hints.key()).isEqualTo("order_no");
        assertThat(hints.compare()).isEqualTo("amount");
    }

    @Test
    @DisplayName("只说业务意图时不算完，缺什么就问什么，而且一次只问一个")
    void 只说不全就只问下一个() {
        DraftIntake.Hints hints = DraftIntake.parse("订单号和状态对账", TABLES, DraftIntake.Hints.EMPTY);

        assertThat(hints.complete()).isFalse();
        assertThat(hints.missing()).hasSize(4);
        assertThat(hints.ask()).isEqualTo("左侧用哪张表？");
    }

    @Test
    @DisplayName("分四轮说：已收集的不能被后面的句子冲掉")
    void 分四轮收集() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY;
        hints = DraftIntake.parse("左 bill_left", TABLES, hints);
        assertThat(hints.ask()).isEqualTo("右侧用哪张表？");

        hints = DraftIntake.parse("右 bill_right", TABLES, hints);
        assertThat(hints.ask()).contains("业务键");

        hints = DraftIntake.parse("按 order_no 连", TABLES, hints);
        assertThat(hints.ask()).contains("比哪一个字段");

        hints = DraftIntake.parse("比 status", TABLES, hints);
        assertThat(hints.complete()).isTrue();
        assertThat(hints.toMap())
                .containsEntry("left", "bill_left")
                .containsEntry("right", "bill_right")
                .containsEntry("key", "order_no")
                .containsEntry("compare", "status");
    }

    @Test
    @DisplayName("没写左右时按出现顺序：第一张当左、第二张当右")
    void 没有方位词按顺序() {
        DraftIntake.Hints hints = DraftIntake.parse("bill_left 和 bill_right 对一下", TABLES, DraftIntake.Hints.EMPTY);
        assertThat(hints.left()).isEqualTo("bill_left");
        assertThat(hints.right()).isEqualTo("bill_right");
    }

    @Test
    @DisplayName("生成的骨架要能被解析：单列业务键带 keyField + orderBy，且必须有 missing 段")
    void 骨架形状() {
        DraftIntake.Hints hints = DraftIntake.parse(
                "左 bill_left，右 bill_right，按 order_no 连，比 amount", TABLES, DraftIntake.Hints.EMPTY);
        String yaml = hints.toYaml("AUTO_BILL_LEFT_BILL_RIGHT");

        assertThat(yaml).contains("code: AUTO_BILL_LEFT_BILL_RIGHT");
        assertThat(yaml).contains("keyField: order_no");
        // 引擎要求 orderBy 必须等于 keyField，缺了直接校验不过
        assertThat(yaml).contains("orderBy: order_no");
        // 金额类字段走 DECIMAL / AMOUNT_MISMATCH
        assertThat(yaml).contains("type: DECIMAL").contains("AMOUNT_MISMATCH");
        // missing 段是必填，少了它规则根本存不进去
        assertThat(yaml).contains("leftMissingDiffType: MISSING_LEFT");
    }

    @Test
    @DisplayName("复合业务键走 dimension，不写 orderBy（DSL 里两者互斥）")
    void 复合键走维度() {
        DraftIntake.Hints hints = DraftIntake.parse(
                "左 bill_left，右 bill_right，按 customer_id 和 order_no 连，比 status", TABLES, DraftIntake.Hints.EMPTY);
        String yaml = hints.toYaml("MULTI_KEY");

        assertThat(yaml).contains("dimension: [customer_id, order_no]");
        assertThat(yaml).doesNotContain("orderBy");
        assertThat(yaml).contains("type: STRING").contains("VALUE_MISMATCH");
    }

    @Test
    @DisplayName("规则编码只有大写标识符才算，表名不算")
    void 编码识别() {
        assertThat(DraftIntake.codeIn("起草 ORDER_RECON，左 bill_left 右 bill_right", TABLES))
                .isEqualTo("ORDER_RECON");
        assertThat(DraftIntake.codeIn("左 bill_left 右 bill_right", TABLES)).isNull();
    }

    @Test
    @DisplayName("「根据订单和状态对账」：先存业务词，等知道表了再翻成列名")
    void 业务词等表定了再翻译() {
        DraftIntake.Hints hints = DraftIntake.parse("给我起草个规则，根据订单和状态对账", TABLES,
                DraftIntake.Hints.EMPTY);
        // 这时候还不知道是哪张表，订单/状态无从对应到列，只能先记着
        assertThat(hints.hint()).isEqualTo("订单和状态");
        assertThat(hints.complete()).isFalse();

        // 「就这两个吧」= 上一句列出的两张候选表
        hints = hints.withTables("bill_left", "bill_right");
        hints = hints.resolveFrom(List.of("id", "order_no", "customer_id", "amount", "status", "updated_at"));

        // 多词 = 对账维度（复合键），不是"键 + 比对字段"：比对字段还得问
        assertThat(hints.key()).isEqualTo("order_no和status");
        assertThat(hints.compare()).isNull();
        assertThat(hints.complete()).isFalse();
        // 已经变成参数了，hint 要清掉，否则下一轮会用旧词覆盖用户的更正
        assertThat(hints.hint()).isNull();

        // 再补一句「比金额」才收齐
        DraftIntake.Hints done = hints.resolveFrom(List.of("amount")).resolveFrom(
                List.of("id", "order_no", "customer_id", "amount", "status", "updated_at"));
        DraftIntake.Hints withCompare = DraftIntake.parse("比金额吧", TABLES, done)
                .resolveFrom(List.of("id", "order_no", "customer_id", "amount", "status", "updated_at"));
        assertThat(withCompare.compare()).isEqualTo("amount");
        assertThat(withCompare.complete()).isTrue();
    }

    @Test
    @DisplayName("认不出列名时宁可继续问，也不猜一个不存在的列")
    void 认不出列名就不填() {
        DraftIntake.Hints hints = DraftIntake.parse("给我起草个规则，根据订单和状态对账", TABLES,
                DraftIntake.Hints.EMPTY).withTables("bill_left", "bill_right");
        DraftIntake.Hints resolved = hints.resolveFrom(List.of("id", "amount"));
        assertThat(resolved.key()).isNull();
        assertThat(resolved.compare()).isNull();
    }

    private static final List<String> COLUMNS =
            List.of("id", "order_no", "customer_id", "amount", "status", "updated_at");

    @Test
    @DisplayName("「就订单和状态对账吧」：口语填充词不能把业务键翻译带偏")
    void 填充词不影响翻译() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");
        hints = DraftIntake.parse("就订单和状态对账吧", TABLES, hints);
        hints = hints.resolveFrom(COLUMNS);

        // 线上是这样错的：hint 切成「就订单 / 状态」，订单没翻出来，业务键落成了 status
        assertThat(hints.key()).isEqualTo("order_no和status");
        assertThat(hints.compare()).isNull();
    }

    @Test
    @DisplayName("「比金额吧」这种中文说法也要翻成列名")
    void 中文比对字段() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");
        hints = DraftIntake.parse("比金额吧", TABLES, hints);
        hints = hints.resolveFrom(COLUMNS);

        assertThat(hints.compare()).isEqualTo("amount");
    }

    @Test
    @DisplayName("一句话里带别的诉求时，比对字段不能把整句吞进去")
    void 比对字段不跨句() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");
        hints = DraftIntake.parse("比金额吧，另外给我完整展示下我要起草的这个规则", TABLES, hints);
        hints = hints.resolveFrom(COLUMNS);

        // 线上错法：capture 把「金额吧，另外给我完整展示…」整段当成了字段名
        assertThat(hints.compare()).isEqualTo("amount");
    }

    @Test
    @DisplayName("「按订单号连、比金额」：中文的业务键也要认")
    void 中文业务键() {
        DraftIntake.Hints hints = DraftIntake.parse("起草个规则，按订单号连、比金额", TABLES,
                DraftIntake.Hints.EMPTY).withTables("bill_left", "bill_right");
        hints = hints.resolveFrom(COLUMNS);

        assertThat(hints.key()).isEqualTo("order_no");
        assertThat(hints.compare()).isEqualTo("amount");
        assertThat(hints.complete()).isTrue();
    }

    @Test
    @DisplayName("没收齐时也能给出预览：缺的写成 ?")
    void 预览半成品() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");
        String yaml = hints.toYamlPreview();

        assertThat(yaml).contains("left:").contains("table: bill_left");
        assertThat(yaml).contains("keyField: ?");
        assertThat(yaml).contains("field: ?");
    }

    @Test
    @DisplayName("比对字段落在业务键里：当场指出来，并把它清空重问")
    void 比对字段不能是业务键() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right")
                .withSlots(java.util.Map.of("key", "order_no", "compare", "order_no"));

        assertThat(hints.compareInKey()).contains("order_no").contains("业务键");
        assertThat(hints.withoutCompare().compare()).isNull();
        assertThat(hints.withoutCompare().complete()).isFalse();
    }

    @Test
    @DisplayName("比对字段与业务键不重叠时不误报")
    void 比对字段不在业务键里() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right")
                .withSlots(java.util.Map.of("key", "order_no和status", "compare", "amount"));

        assertThat(hints.compareInKey()).isNull();
    }

    @Test
    @DisplayName("「用订单和状态对金额」：业务键和比对字段一次说全，等表定了再翻成列名")
    void 用订单和状态对金额() {
        DraftIntake.Hints hints = DraftIntake.parse("起草个规则，用订单和状态对金额", TABLES, DraftIntake.Hints.EMPTY);

        // 线上错法：只认「对账 / 核对」，这句话一个字段都没记住，后面又问了两遍
        assertThat(hints.hint()).isEqualTo("订单和状态");

        hints = hints.withTables("bill_left", "bill_right").resolveFrom(COLUMNS);
        assertThat(hints.key()).isEqualTo("order_no和status");
        assertThat(hints.compare()).isEqualTo("amount");
        assertThat(hints.complete()).isTrue();
    }

    @Test
    @DisplayName("「翻一下顺序」：左右对调，不当作新参数")
    void 翻一下顺序() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");

        DraftIntake.Hints swapped = DraftIntake.parse("翻一下顺序", TABLES, hints);
        assertThat(swapped.left()).isEqualTo("bill_right");
        assertThat(swapped.right()).isEqualTo("bill_left");
        // 再翻一次要能翻回来
        assertThat(DraftIntake.parse("反过来", TABLES, swapped).left()).isEqualTo("bill_left");
    }

    @Test
    @DisplayName("「换个规则」不能被当成左右对调")
    void 换规则不是对调() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY.withTables("bill_left", "bill_right");

        assertThat(DraftIntake.parse("换个规则吧", TABLES, hints).left()).isEqualTo("bill_left");
    }

    @Test
    @DisplayName("模型补的列要在用户原话里指得到：说「日期」推不出 updated_at")
    void 模型补的列要能在原话里指到() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY
                .withTables("bill_left", "bill_right")
                .withSlots(java.util.Map.of("key", "order_no和updated_at", "compare", "amount"));

        // 用户说的是「订单、客户、日期」：order_no / amount 指得到，updated_at 指不到
        assertThat(hints.untraceable("订单、客户、日期吧，比金额", COLUMNS))
                .containsExactly("updated_at");
        // 用户自己说了这一列（或它的别名），就不再拦
        assertThat(hints.untraceable("就按 updated_at 和订单来，比金额", COLUMNS)).isEmpty();
        assertThat(hints.untraceable("按订单和更新时间配，比金额", COLUMNS)).isEmpty();
    }

    @Test
    @DisplayName("用户否掉一列：只摘这一列，其余已收的不动")
    void 否掉一列不动别的() {
        DraftIntake.Hints hints = DraftIntake.Hints.EMPTY
                .withTables("bill_left", "bill_right")
                .withSlots(java.util.Map.of("key", "order_no和updated_at", "compare", "amount"));

        DraftIntake.Hints kept = hints.withoutColumns(List.of("updated_at"));
        assertThat(kept.key()).isEqualTo("order_no");
        assertThat(kept.compare()).isEqualTo("amount");
        assertThat(kept.left()).isEqualTo("bill_left");

        DraftIntake.Hints noCompare = hints.withoutColumns(List.of("amount"));
        assertThat(noCompare.compare()).isNull();
        assertThat(noCompare.key()).isEqualTo("order_no和updated_at");
    }
}
