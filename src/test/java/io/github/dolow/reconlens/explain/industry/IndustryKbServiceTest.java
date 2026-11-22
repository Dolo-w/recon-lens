package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.store.IndustryKbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndustryKbServiceTest {

    private IndustryKbService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:industry_kb_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE recon_industry_kb ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "entry_key VARCHAR(96) NOT NULL, applies_when VARCHAR(2048) NOT NULL, "
                + "knowledge_text VARCHAR(500) NOT NULL, source VARCHAR(128) NOT NULL, "
                + "origin VARCHAR(16) NOT NULL, confidence DECIMAL(4,3) NOT NULL DEFAULT 0.400, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', used_count INT NOT NULL DEFAULT 0, "
                + "rejected_count INT NOT NULL DEFAULT 0, "
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "UNIQUE KEY uk_key (entry_key))");
        service = new IndustryKbService(new IndustryKbRepository(jdbc));
    }

    private IndustryKnowledge candidate(String key, String diffType, String text) {
        return new IndustryKnowledge(null, key,
                new IndustryKnowledge.AppliesWhen(List.of(), List.of(diffType), null, List.of()),
                text, "test-2026-11", IndustryKnowledge.Origin.SEED, 0.7,
                IndustryKnowledge.Status.ACTIVE, 0, 0, null);
    }

    @Test
    @DisplayName("机器准入：合法条目入库，全空条件拒绝，同 key 幂等更新")
    void admissionAndUpsert() {
        IndustryKbService.AdmitResult ok = service.admit(candidate("IND-A", "amount_mismatch", "先查税率档位"));
        assertThat(ok.outcome()).isEqualTo(IndustryKbService.AdmitResult.Outcome.CREATED);

        // 同 key 重放（种子幂等）
        IndustryKbService.AdmitResult replay = service.admit(candidate("IND-A", "amount_mismatch", "先查税率档位（修订）"));
        assertThat(replay.outcome()).isEqualTo(IndustryKbService.AdmitResult.Outcome.UPDATED);
        assertThat(service.listAll(50)).hasSize(1);

        IndustryKbService.AdmitResult rejected = service.admit(
                new IndustryKnowledge(null, "IND-BAD",
                        new IndustryKnowledge.AppliesWhen(List.of(), List.of(), null, List.of()),
                        "无条件条目", "test", IndustryKnowledge.Origin.HARVEST, 0.4,
                        IndustryKnowledge.Status.ACTIVE, 0, 0, null));
        assertThat(rejected.outcome()).isEqualTo(IndustryKbService.AdmitResult.Outcome.REJECTED);
        assertThat(service.countActive()).isEqualTo(1);
    }

    @Test
    @DisplayName("注入过滤：条件命中才注入，低置信带 AI 标注，预算封顶")
    void injectFiltering() {
        service.admit(candidate("IND-HI", "amount_mismatch", "高置信知识：先查舍入口径"));
        service.admit(new IndustryKnowledge(null, "IND-LO",
                new IndustryKnowledge.AppliesWhen(List.of(), List.of("amount_mismatch"), null, List.of()),
                "低置信知识：AI 蒸馏未经核证", "llm-common", IndustryKnowledge.Origin.HARVEST, 0.4,
                IndustryKnowledge.Status.ACTIVE, 0, 0, null));
        service.admit(candidate("IND-OTHER", "duplicate", "另一类差异的知识"));

        String context = service.injectContext(null, "AMOUNT_MISMATCH", null, null);
        assertThat(context).contains("高置信知识").contains("低置信知识").contains("[AI 生成·低置信]");
        assertThat(context).doesNotContain("另一类差异");

        // 不命中的差异类型：空上下文，且不计数
        assertThat(service.injectContext(null, "duplicate", null, null)).contains("另一类差异");

        // 命中条目 used_count 增长
        Integer used = jdbc.queryForObject(
                "SELECT used_count FROM recon_industry_kb WHERE entry_key = 'IND-HI'", Integer.class);
        assertThat(used).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("核销反馈：note 复述条目说法→采纳加权；改判→驳回降权；跌破阈值进 graveyard")
    void feedbackLifecycle() {
        service.admit(candidate("IND-FB", "amount_mismatch", "逐行计税再汇总的口径差异是常见根因"));
        io.github.dolow.reconlens.domain.DiffRecord diff = io.github.dolow.reconlens.domain.DiffRecord.of(
                1L, 1, "amount_mismatch", io.github.dolow.reconlens.domain.Severity.MEDIUM,
                "k1", "tax_amount", "100", "102", null, null);

        // note 复述了条目核心措辞（≥8 字公共子串）→ 采纳
        service.feedbackOnResolution(null, diff, "确认是逐行计税再汇总的口径差异", false);
        double afterAdopt = currentConfidence("IND-FB");
        assertThat(afterAdopt).isGreaterThan(0.7);

        // 改判（note 完全无关）→ 驳回 -0.1/次；连续驳回跌破 0.2 → graveyard
        for (int i = 0; i < 6; i++) {
            service.feedbackOnResolution(null, diff, "实际上是网络超时导致单边缺失", false);
        }
        assertThat(currentConfidence("IND-FB")).isLessThan(0.2);
        assertThat(service.graveyard()).anyMatch(e -> e.entryKey().equals("IND-FB"));
        // graveyard 不再被注入
        assertThat(service.injectContext(null, "amount_mismatch", null, null)).doesNotContain("逐行计税");
    }

    @Test
    @DisplayName("冷知识清单：零命中的 ACTIVE 条目被点名")
    void coldEntries() {
        service.admit(candidate("IND-COLD", "amount_mismatch", "从没人问过它的知识"));
        assertThat(service.coldEntries()).anyMatch(e -> e.entryKey().equals("IND-COLD"));
        service.injectContext(null, "amount_mismatch", null, null);
        assertThat(service.coldEntries()).noneMatch(e -> e.entryKey().equals("IND-COLD"));
    }

    @Test
    @DisplayName("知识层故障不挡归因：空数据源查询失败按无知识处理")
    void degradedGracefully() {
        IndustryKbService broken = new IndustryKbService(new IndustryKbRepository(
                new JdbcTemplate(new DriverManagerDataSource())));
        assertThat(broken.select(null, "amount_mismatch", null, null)).isEmpty();
        assertThat(broken.injectContext(null, "amount_mismatch", null, null)).isEmpty();
    }

    private double currentConfidence(String key) {
        Double v = jdbc.queryForObject(
                "SELECT confidence FROM recon_industry_kb WHERE entry_key = ?", Double.class, key);
        return v == null ? 0 : v;
    }
}
