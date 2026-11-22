package io.github.dolow.reconlens.explain.industry;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.llm.LlmChatClient;
import io.github.dolow.reconlens.store.IndustryKbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeHarvestServiceTest {

    private IndustryKbService service;
    private KnowledgeHarvestService harvester;
    private LlmChatClient llm;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:harvest_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
        llm = mock(LlmChatClient.class);
        harvester = new KnowledgeHarvestService(service, llm);
    }

    private static final String MODEL_OUTPUT = """
            [
              {"key":"AI-TAX-ROUND","appliesWhen":{"diffTypes":["amount_mismatch"],"fieldRegex":".*tax.*"},
               "text":"税额差异先对计税层级：逐行计税再汇总与汇总计税再分摊是两种口径"},
              {"key":"AI-BAD","appliesWhen":{},"text":"无条件条目应被准入闸拒绝"},
              {"key":"AI-BROKEN","appliesWhen":{"fieldRegex":"([bad"},"text":"坏正则应被拒绝"}
            ]
            """;

    @Test
    @DisplayName("蒸馏：模型输出解析→机器准入→入库；非法条目被闸拒绝但管道继续")
    void distillPipeline() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(java.util.Optional.of(MODEL_OUTPUT));

        KnowledgeHarvestService.HarvestReport report = harvester.distill("billing-pricing", 12);
        assertThat(report.failed()).isFalse();
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.rejected()).isEqualTo(2);

        List<IndustryKnowledge> all = service.listAll(10);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).entryKey()).isEqualTo("AI-TAX-ROUND");
        assertThat(all.get(0).origin()).isEqualTo(IndustryKnowledge.Origin.HARVEST);
        assertThat(all.get(0).confidence()).isEqualTo(0.4);
        assertThat(all.get(0).source()).startsWith("llm-common-billing-pricing-");
    }

    @Test
    @DisplayName("未配模型时采集如实失败，不假装成功")
    void unavailableFails() {
        when(llm.available()).thenReturn(false);
        assertThat(harvester.distill("any", 5).failed()).isTrue();
        assertThat(harvester.ingest("a.pdf", new byte[0]).failed()).isTrue();
    }

    @Test
    @DisplayName("多源摄取：HTML 清洗剥标签、分块后提炼入库；图片如实拒绝")
    void ingestPipeline() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(java.util.Optional.of(MODEL_OUTPUT));

        String html = "<html><head><style>body{color:red}</style></head><body>"
                + "<h1>对账手册</h1>" + "段落一：".repeat(1) + "税额口径说明，".repeat(30)
                + "<p>段落二：差异分类说明，</p>" + "内容填充。".repeat(30) + "</body></html>";
        KnowledgeHarvestService.HarvestReport report = harvester.ingest("recon-handbook.html",
                html.getBytes(StandardCharsets.UTF_8));
        assertThat(report.failed()).isFalse();
        assertThat(report.created() + report.updated() + report.rejected()).isGreaterThanOrEqualTo(1);

        // 图片：如实拒绝不猜内容
        assertThat(harvester.ingest("screenshot.png", new byte[]{1}).failed()).isTrue();
        // 不认识的类型：拒绝
        assertThat(harvester.ingest("data.bin", new byte[]{1}).failed()).isTrue();
    }

    @Test
    @DisplayName("清洗器：HTML 剥标签与实体，分块守住尺寸上限")
    void cleaning() {
        assertThat(KnowledgeHarvestService.cleanHtml("<p>a&amp;b</p><script>evil()</script><div>c</div>"))
                .doesNotContain("evil()").doesNotContain("<p>").contains("a&b");
        String longText = ("段落内容。".repeat(40) + "\n").repeat(10);
        List<String> chunks = KnowledgeHarvestService.chunk(KnowledgeHarvestService.cleanWhitespace(longText), 300);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(4);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(320));
    }

    @Test
    @DisplayName("模型输出夹带 markdown 代码块也能解析")
    void markdownFenceTolerated() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(
                java.util.Optional.of("```json\n" + MODEL_OUTPUT + "\n```"));
        assertThat(harvester.distill("any", 12).created()).isEqualTo(1);
    }
}
