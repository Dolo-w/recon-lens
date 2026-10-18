package io.github.dolow.reconlens.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库的持久化语义。
 *
 * <p>两条被刻意固定下来的"不做"：{@code insertIfAbsent} 对已存在的键<b>什么都不改</b>，
 * 置信度也<b>不由模型给出</b>。它们看起来是细节，实际上是知识库会不会被模型污染的分水岭。
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
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_cause_key ON recon_diff_kb (cause_key)");
        jdbc.update("DELETE FROM recon_diff_kb");
    }

    private static DiffKnowledgeRepository.Entry entry(String key, String cause, String source, String confidence) {
        return new DiffKnowledgeRepository.Entry(0L, key, "BILL_VS_SETTLE", "AMOUNT_MISMATCH", "amount",
                "BOTH|N/N|R<1P", cause, null, new BigDecimal(confidence), source, 0, 0, "agent", null, null);
    }

    @Test
    void insertedEntryCanBeFoundByKey() {
        assertThat(repository.insertIfAbsent(entry("k1", "入账时点不同", "LLM", "0.500"))).isPresent();

        DiffKnowledgeRepository.Entry found = repository.findByKey("k1").orElseThrow();
        assertThat(found.cause()).isEqualTo("入账时点不同");
        assertThat(found.source()).isEqualTo("LLM");
        assertThat(found.confidence()).isEqualByComparingTo("0.5");
        assertThat(found.createdAt()).isNotNull();
    }

    /** 已存在就不写 —— 否则人工修正过的归因会被下一轮模型的一次胡说冲掉。 */
    @Test
    void insertIfAbsentDoesNotOverwriteAnExistingEntry() {
        repository.insertIfAbsent(entry("k1", "模型初版", "LLM", "0.500"));
        repository.correctByHuman("k1", "人工修正版", "去查渠道流水", "ops");

        assertThat(repository.insertIfAbsent(entry("k1", "模型第二版", "LLM", "0.500"))).isEmpty();

        DiffKnowledgeRepository.Entry found = repository.findByKey("k1").orElseThrow();
        assertThat(found.cause()).isEqualTo("人工修正版");
        assertThat(found.source()).isEqualTo("HUMAN");
    }

    @Test
    void humanCorrectionRaisesConfidenceAndRecordsWho() {
        repository.insertIfAbsent(entry("k1", "模型初版", "LLM", "0.500"));

        assertThat(repository.correctByHuman("k1", "人工修正版", "去查渠道流水", "ops")).isTrue();

        DiffKnowledgeRepository.Entry found = repository.findByKey("k1").orElseThrow();
        assertThat(found.confidence()).isEqualByComparingTo("0.9");
        assertThat(found.action()).isEqualTo("去查渠道流水");
        assertThat(found.createdBy()).isEqualTo("ops");
        assertThat(found.updatedAt()).isNotNull();
    }

    @Test
    void correctingAnUnknownKeyChangesNothing() {
        assertThat(repository.correctByHuman("没有这个键", "x", null, "ops")).isFalse();
        assertThat(repository.recent(10)).isEmpty();
    }

    @Test
    void hitsAndMissesAreCountedSeparately() {
        long id = repository.insertIfAbsent(entry("k1", "原因", "LLM", "0.500")).orElseThrow();

        repository.bumpHits(id);
        repository.bumpHits(id);
        repository.bumpMisses(id);

        DiffKnowledgeRepository.Entry found = repository.findByKey("k1").orElseThrow();
        assertThat(found.hits()).isEqualTo(2);
        assertThat(found.misses()).isEqualTo(1);
    }

    /** 唯一索引是最后一道防线：键算错了会在这里撞出来，而不是悄悄长出两条互相打擂的归因。 */
    @Test
    void duplicateCauseKeyIsRejectedByTheDatabase() {
        repository.insertIfAbsent(entry("k1", "原因", "LLM", "0.500"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO recon_diff_kb (cause_key, rule_code, diff_type, cause, source) VALUES (?,?,?,?,?)",
                "k1", "R", "AMOUNT_MISMATCH", "绕过仓储直接写", "LLM"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void countBySourceShowsWhereTheKnowledgeComesFrom() {
        repository.insertIfAbsent(entry("k1", "a", "LLM", "0.500"));
        repository.insertIfAbsent(entry("k2", "b", "LLM", "0.500"));

        assertThat(repository.countBySource()).containsEntry("LLM", 2L);
    }
}
