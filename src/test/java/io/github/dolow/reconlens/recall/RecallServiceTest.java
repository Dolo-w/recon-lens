package io.github.dolow.reconlens.recall;

import io.github.dolow.reconlens.store.CaseMemoryRepository;
import io.github.dolow.reconlens.store.DocChunkRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecallServiceTest {

    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private DocChunkService docs;
    private CaseMemoryService cases;

    @BeforeAll
    static void createSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:recall_service;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        dataSource = ds;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM recon_doc_chunk");
        jdbc.update("DELETE FROM recon_case_memory");
        docs = new DocChunkService(new DocChunkRepository(jdbc), new KeywordDocSearch(new DocChunkRepository(jdbc)));
        cases = new CaseMemoryService(new CaseMemoryRepository(jdbc));
    }

    private static final String MANUAL = """
            结算口径说明
            一、账期口径。账单侧以入账时间为准，结算侧以结算时间为准，两边跨月时会自然产生时间差，不算差异。
            二、金额口径。手续费与汇率进位在结算侧才发生，因此金额不一致在 0.01 元以内属于正常。
            三、重复键口径。同一业务键在同一批次出现两次以上，绝大多数是重复入账或消息重放，需要按幂等键去重后重跑。
            四、单边缺失口径。只有结算侧有记录时，通常是对方延迟入账；只有账单侧有记录时，通常是我方补单未同步。
            五、容差口径。绝对容差用于固定费用，百分比容差用于汇率类，两者不能同时忽略。
            """;

    @Test
    void 导入一篇文档_按句读切片且可以检索到() {
        DocChunkService.IndexResult result = docs.index("结算口径说明", "finance", null, MANUAL);
        assertThat(result.alreadyIndexed()).isFalse();
        assertThat(result.chunks()).isGreaterThanOrEqualTo(2);

        List<DocHit> hits = docs.search("结算口径", null, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk().title()).isEqualTo("结算口径说明");
        assertThat(hits.get(0).matched()).contains("结算");
    }

    @Test
    void 同一篇重复导入_不产生第二份切片() {
        DocChunkService.IndexResult first = docs.index("结算口径说明", "finance", null, MANUAL);
        int afterFirst = docs.count();
        DocChunkService.IndexResult second = docs.index("结算口径说明（重传）", "finance", null, MANUAL);

        assertThat(second.alreadyIndexed()).isTrue();
        assertThat(second.docId()).isEqualTo(first.docId());
        assertThat(docs.count()).isEqualTo(afterFirst);
    }

    @Test
    void 元数据过滤在前_来源不同检索不到() {
        docs.index("结算口径说明", "finance", null, MANUAL);
        assertThat(docs.search("容差", "finance", 5)).isNotEmpty();
        assertThat(docs.search("容差", "warehouse", 5)).isEmpty();
    }

    @Test
    void 中文按二元组切词_不依赖分词器() {
        assertThat(KeywordDocSearch.terms("金额不一致")).contains("金额", "额不", "不一", "一致");
        assertThat(KeywordDocSearch.terms("recon job")).contains("recon", "job");
        assertThat(KeywordDocSearch.terms("  ")).isEmpty();
    }

    @Test
    void 指定数据版本时_别的版本的案例不召回_没记版本的仍然召回() {
        long other = cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002", "旧版本的处理",
                "alice", 5L, 2L, "v1-abc");
        long same = cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002", "这一版的处理",
                "alice", 6L, 3L, "v2-def");
        long legacy = cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002", "没记版本的处理",
                "alice", 7L, 4L);

        List<CaseHit> hits = cases.recall("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "v2-def", 10);
        assertThat(hits).extracting(h -> h.memory().id())
                .contains(same, legacy)
                .doesNotContain(other);

        // 不指定版本时不能悄悄少给：调用方不知道版本，就该拿到全部候选
        assertThat(cases.recall("BILL_VS_SETTLE", "AMOUNT_MISMATCH", 10))
                .extracting(h -> h.memory().id())
                .contains(same, other, legacy);
    }

    @Test
    void 案例渲染会写出它的数据版本() {
        cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002", "按手续费进位处理",
                "alice", 5L, 2L, "v2-def");
        assertThat(cases.render("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "v2-def", 3))
                .contains("数据版本 v2-def");
    }

    @Test
    void 人确认的案例可信度09_模型自述的只有05() {
        long human = cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002|ORD20260003",
                "和财务确认是手续费进位，不改数据", "alice", 5L, 2L);
        long model = cases.rememberByModel("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "C002|ORD20260003",
                "疑似手续费进位，建议人工确认", 5L, 2L);

        assertThat(cases.recent("BILL_VS_SETTLE", 10))
                .filteredOn(m -> m.id() == human)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.confidence()).isEqualTo(0.9);
                    assertThat(m.source()).isEqualTo("HUMAN");
                    assertThat(m.operator()).isEqualTo("alice");
                });
        assertThat(cases.recent("BILL_VS_SETTLE", 10))
                .filteredOn(m -> m.id() == model)
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.confidence()).isEqualTo(0.5);
                    assertThat(m.operator()).isNull();
                });
    }

    @Test
    void 检索按可信度加新鲜度排序_人确认排在模型自述前面_并且累加命中次数() {
        cases.rememberByModel("BILL_VS_SETTLE", "MISSING_RIGHT", "C008|ORD20260009", "可能是对方延迟入账", null, null);
        cases.rememberByHuman("BILL_VS_SETTLE", "MISSING_RIGHT", "C008|ORD20260009", "已和对方确认是延迟入账", "alice", null, null);

        List<CaseHit> hits = cases.recall("BILL_VS_SETTLE", "MISSING_RIGHT", 3);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).memory().byHuman()).isTrue();
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());

        // 返回的是"读到时"的快照，累加在写库之后才可见：第一次读到 0，第二次读到 1
        List<CaseHit> again = cases.recall("BILL_VS_SETTLE", "MISSING_RIGHT", 3);
        assertThat(again.get(0).memory().hits()).isEqualTo(hits.get(0).memory().hits() + 1);
    }

    @Test
    void 没有任何案例时渲染空串_调用方就不追加这一段() {
        assertThat(cases.render("BILL_VS_SETTLE", "MISSING_LEFT", 3)).isEmpty();
    }

    @Test
    void 英文差异类型要翻成手册里的中文词_否则永远搜不到() {
        assertThat(RecallAugmenter.manualQuery("AMOUNT_MISMATCH", "amount")).contains("金额");
        assertThat(RecallAugmenter.manualQuery("MISSING_RIGHT", null)).contains("结算侧");
        assertThat(RecallAugmenter.manualQuery("DUPLICATE_KEY", null)).contains("幂等");
        assertThat(RecallAugmenter.manualQuery("SOMETHING_NEW", null)).isEmpty();
    }

    @Test
    void 处理方式为空要拒绝_因为案例的价值就在那句处理方式() {
        assertThatThrownBy(() -> cases.rememberByHuman("BILL_VS_SETTLE", "AMOUNT_MISMATCH", "k", "  ", "alice", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolution");
    }

    @Test
    void 归因追加段_有案例有时引用口径_都没有时不追加() {
        RecallAugmenter augmenter = new RecallAugmenter(cases, docs);
        assertThat(augmenter.augment("BILL_VS_SETTLE", "MISSING_RIGHT", "C008")).isEmpty();

        docs.index("结算口径说明", "finance", null, MANUAL);
        cases.rememberByHuman("BILL_VS_SETTLE", "MISSING_RIGHT", "C008|ORD20260009", "对方延迟入账，已确认", "alice", null, null);

        String text = augmenter.augment("BILL_VS_SETTLE", "MISSING_RIGHT", null, null);
        assertThat(text).contains("同类差异的历史处理");
        assertThat(text).contains("对方延迟入账");
        assertThat(text).contains("口径依据");
    }
}
