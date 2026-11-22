package io.github.dolow.reconlens.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * fallback 挖掘的轻量集成测试：内存 H2 直接建表，不起 Spring 容器。
 */
class FallbackMiningServiceTest {

    private JdbcTemplate jdbc;
    private FallbackMiningService mining;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:fallback_mining;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        // DB_CLOSE_DELAY=-1 让内存库跨方法存活（同 JVM 一个库），先清表防止串台
        jdbc.execute("DROP TABLE IF EXISTS recon_chat_fallback");
        jdbc.execute("CREATE TABLE recon_chat_fallback ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "session_id BIGINT NULL, query_text VARCHAR(512) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        mining = new FallbackMiningService(jdbc);
    }

    private void insert(String text) {
        jdbc.update("INSERT INTO recon_chat_fallback (session_id, query_text, created_at) VALUES (?, ?, ?)",
                1L, text, new Timestamp(System.currentTimeMillis()));
    }

    @Test
    @DisplayName("归一化分组：同义说法并成一组，频次=真实痛点次数")
    void canonicalGrouping() {
        insert("账单对不上");
        insert("账单对不上");
        insert("账单不平");
        insert("金额对不上");

        List<FallbackMiningService.Group> groups = mining.mine(2, 10);
        assertThat(groups).hasSize(1);
        // CANON 把「不平」归到「差异」一族，「对不上」同样归一——三句并一组
        assertThat(groups.get(0).count()).isEqualTo(3);
        assertThat(groups.get(0).examples()).hasSize(3);
    }

    @Test
    @DisplayName("频次降序 + minCount 过滤：反复出现的排前面，一次性的被滤掉")
    void sortedAndFiltered() {
        insert("帮我导出报表");
        insert("帮我导出报表");
        insert("帮我导出报表");
        insert("把价格表导出来");
        insert("把价格表导出来");
        insert("孤零零的一句话");

        List<FallbackMiningService.Group> groups = mining.mine(2, 10);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).count()).isEqualTo(3);
        assertThat(groups.get(1).count()).isEqualTo(2);
        // minCount=3 时只剩一组
        assertThat(mining.mine(3, 10)).hasSize(1);
    }

    @Test
    @DisplayName("limit 封顶：最多吐 N 组")
    void limitCapped() {
        for (int i = 1; i <= 5; i++) {
            insert("问题甲 " + i);
            insert("问题甲 " + i);
            insert("问题乙 " + i);
            insert("问题乙 " + i);
            insert("问题丙 " + i);
            insert("问题丙 " + i);
        }
        assertThat(mining.mine(2, 2)).hasSize(2);
    }

    @Test
    @DisplayName("goldenYaml 可直接粘贴：片段带出现次数与 TODO 占位")
    void goldenSnippet() {
        insert("帮我导出报表");
        insert("帮我导出报表");
        String yaml = mining.goldenYaml(2, 10);
        assertThat(yaml).contains("intent: TODO").contains("帮我导出报表").contains("出现 2 次");
        // 语义里带双引号的原句要被安全替换，不能破坏 YAML 结构
        insert("说\"引号\"的话");
        insert("说\"引号\"的话");
        insert("说\"引号\"的话");
        String yaml2 = mining.goldenYaml(2, 10);
        assertThat(yaml2).doesNotContain("text: \"说\"");
    }

    @Test
    @DisplayName("表不存在不炸：老库返回空列表，stats 页面不被带炸")
    void missingTableGraceful() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:h2:mem:fallback_no_table;MODE=MySQL;DB_CLOSE_DELAY=-1");
        FallbackMiningService noTable = new FallbackMiningService(new JdbcTemplate(ds));
        assertThat(noTable.mine(1, 10)).isEmpty();
        assertThat(noTable.goldenYaml(1, 10)).contains("暂无候选");
    }
}
