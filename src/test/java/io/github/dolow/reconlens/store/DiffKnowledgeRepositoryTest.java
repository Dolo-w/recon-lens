package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库的持久化语义（K2 多候选）。
 *
 * <p>三条被刻意固定下来的"不做"：
 * <ul>
 *   <li><b>写路径只有 INSERT</b>：同键多候选并存（9 月的税、10 月的手续费都是真实病因），
 *       人工确认过的条目在结构上不可能被模型的下一次胡说冲掉——不再靠调用方的纪律；</li>
 *   <li><b>同键同措辞不堆重复</b>：多候选的代价是垃圾行，去重闸在写入方；</li>
 *   <li><b>置信度不由模型给出</b>：LLM 写 0.5 是硬编码的信任上限。</li>
 * </ul>
 */
class DiffKnowledgeRepositoryTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS recon_diff_kb (
              id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
              cause_key         VARCHAR(64)   NOT NULL,
              rule_code         VARCHAR(64)   NOT NULL,
              diff_type         VARCHAR(64)   NOT NULL,
              field_name        VARCHAR(64)   NOT NULL DEFAULT '',
              feature_signature VARCHAR(256)  NOT NULL DEFAULT '',
              cause             VARCHAR(1000) NOT NULL,
              action            VARCHAR(1000) NULL,
              confidence        DECIMAL(4,3)  NOT NULL DEFAULT 0.500,
              source            VARCHAR(16)   NOT NULL,
              hits              INT           NOT NULL DEFAULT 0,
              misses            INT           NOT NULL DEFAULT 0,
              created_by        VARCHAR(64)   NULL,
              created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at        TIMESTAMP     NULL
            )
            """;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:diff_kb;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));

    private final DiffKnowledgeRepository repository = new DiffKnowledgeRepository(jdbc);

    @BeforeEach
    void reset() {
        jdbc.execute(DDL);
        // 多候选：cause_key 是普通索引。唯一索引回不来（一键一答的旧世界不回来了）
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kb_key ON recon_diff_kb (cause_key)");
        jdbc.update("DELETE FROM recon_diff_kb");
    }

    private static DiffKnowledgeRepository.Entry entry(String key, String cause, String source, String confidence) {
        return entry(key, cause, source, confidence, null, 0);
    }

    private static DiffKnowledgeRepository.Entry entry(String key, String cause, String source,
                                                       String confidence, String action, int hits) {
        return new DiffKnowledgeRepository.Entry(0L, key, "BILL_VS_SETTLE", "AMOUNT_MISMATCH", "amount",
                "BOTH|N/N|R<1P", cause, action, new BigDecimal(confidence), source, hits, 0, "agent", null, null);
    }

    @Test
    void insertedEntryCanBeFoundAsTopCandidate() {
        assertThat(repository.insert(entry("k1", "入账时点不同", "LLM", "0.500"))).isTrue();

        DiffKnowledgeRepository.Entry found = repository.findTopByKey("k1").orElseThrow();
        assertThat(found.cause()).isEqualTo("入账时点不同");
        assertThat(found.source()).isEqualTo("LLM");
        assertThat(found.confidence()).isEqualByComparingTo("0.5");
        assertThat(found.createdAt()).isNotNull();
    }

    /** K2 核心：同键多候选并存，人工条目不被模型冲掉，top-1 是置信度最高的那条。 */
    @Test
    void humanCandidateWinsOverLaterModelCandidates() {
        repository.insert(entry("k1", "模型初版：批次延迟", "LLM", "0.500"));
        repository.insert(entry("k1", "人工确认：税率调整", "HUMAN", "0.900"));
        // 模型又胡说一次：追加，不覆盖
        repository.insert(entry("k1", "模型第二版：单位错了", "LLM", "0.500"));

        assertThat(repository.findCandidates("k1", 10)).hasSize(3);
        DiffKnowledgeRepository.Entry top = repository.findTopByKey("k1").orElseThrow();
        assertThat(top.source()).isEqualTo("HUMAN");
        assertThat(top.cause()).isEqualTo("人工确认：税率调整");
    }

    /** 同置信度时 hits 高的排前 —— 被反复验证过的候选更可信。 */
    @Test
    void moreHitsBreakTiesAmongSameConfidence() {
        repository.insert(entry("k1", "候选A", "LLM", "0.500", null, 0));
        repository.insert(entry("k1", "候选B", "LLM", "0.500", null, 5));

        assertThat(repository.findTopByKey("k1").orElseThrow().cause()).isEqualTo("候选B");
    }

    /**
     * 仓储层是"哑 INSERT"：去重契约在写入方（{@code DiffKnowledgeBase} 用
     * {@code existsByCauseKeyAndCause} 闸），仓储自己不做判断 —— 哑层好测、好换、好排障。
     */
    @Test
    void repositoryIsADumbInsertDedupBelongsToTheCaller() {
        repository.insert(entry("k1", "同一句话", "HUMAN", "0.900"));
        repository.insert(entry("k1", "同一句话", "HUMAN", "0.900"));

        assertThat(repository.findCandidates("k1", 10)).as("哑层不做去重").hasSize(2);
        assertThat(repository.existsByCauseKeyAndCause("k1", "同一句话"))
                .as("去重判据由仓储提供，调用方据此跳过第二次写").isTrue();
    }

    /** 旧键的候选不影响新键：键下候选按键隔离。 */
    @Test
    void candidatesAreScopedByCauseKey() {
        repository.insert(entry("k1", "键1的病因", "LLM", "0.500"));
        repository.insert(entry("k2", "键2的病因", "LLM", "0.500"));

        assertThat(repository.findCandidates("k1", 10))
                .hasSize(1)
                .first().extracting(DiffKnowledgeRepository.Entry::cause)
                .isEqualTo("键1的病因");
        assertThat(repository.findTopByKey("没有这个键")).isEmpty();
    }

    @Test
    void hitsAndMissesAreCountedSeparately() {
        assertThat(repository.insert(entry("k1", "原因", "LLM", "0.500"))).isTrue();
        long id = repository.findTopByKey("k1").orElseThrow().id();

        repository.bumpHits(id);
        repository.bumpHits(id);
        repository.bumpMisses(id);

        DiffKnowledgeRepository.Entry found = repository.findTopByKey("k1").orElseThrow();
        assertThat(found.hits()).isEqualTo(2);
        assertThat(found.misses()).isEqualTo(1);
    }

    @Test
    void countBySourceShowsWhereTheKnowledgeComesFrom() {
        repository.insert(entry("k1", "a", "LLM", "0.500"));
        repository.insert(entry("k2", "b", "LLM", "0.500"));
        repository.insert(entry("k1", "c", "HUMAN", "0.900"));

        assertThat(repository.countBySource()).containsEntry("LLM", 2L).containsEntry("HUMAN", 1L);
    }
}
