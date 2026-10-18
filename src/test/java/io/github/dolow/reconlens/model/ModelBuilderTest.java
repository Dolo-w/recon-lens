package io.github.dolow.reconlens.model;

import io.github.dolow.reconlens.domain.JobStatus;
import io.github.dolow.reconlens.domain.ReconJob;
import io.github.dolow.reconlens.engine.ReconEngine;
import io.github.dolow.reconlens.mcp.ReconMcpTools;
import io.github.dolow.reconlens.store.ReconRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预处理模型表的端到端验证：模型表不只是"建出来"，还要证明
 * <b>用模型表跑出来的差异与原规则逐条一致</b> —— 否则物化就是错的优化。
 *
 * <p>两条路径都覆盖：行级规则（窄化，行数不变，重复键仍要报 DUPLICATE_KEY）与
 * 声明式维度 + 聚合规则（按维度汇总，行数变少）。分片桶列必须两侧同函数，否则会凭空多出单边缺失差异。
 */
@SpringBootTest(properties = {
        "recon.engine.run-demo-on-startup=false",
        // 本测试验模型表与 __MODEL 规则的取数口径，不验审批流程：把草稿化关掉、保持 ACTIVE
        "recon.model.require-approval=false"
})
class ModelBuilderTest {

    @Autowired
    private ReconEngine engine;
    @Autowired
    private ReconRepository repository;
    @Autowired
    private ModelBuilder builder;
    @Autowired
    private ReconMcpTools tools;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rowLevelRuleKeepsEveryDiffKindThroughTheModelTable() {
        ReconJob baseline = engine.run("BILL_VS_SETTLE", "model-base", "基线");
        Map<String, Long> expected = repository.summaryByType(baseline.id());
        assertThat(expected).containsEntry("DUPLICATE_KEY", 1L);

        ModelBuilder.ModelBuild build = builder.build("BILL_VS_SETTLE", 4, "it-model");

        assertThat(build.modelRuleCode()).isEqualTo("BILL_VS_SETTLE__MODEL");
        assertThat(build.shards()).isEqualTo(4);
        assertThat(build.tables()).hasSize(2).allSatisfy(table -> assertThat(table.rows()).isPositive());
        // 行级规则是"窄化"：只带 keyField 与要比的列，行数不变
        assertThat(build.table("left").rows()).isEqualTo(baseline.leftRows());
        assertThat(build.table("right").rows()).isEqualTo(baseline.rightRows());

        ReconJob modelled = engine.run(build.modelRuleCode(), "model-run", "读模型表");

        assertThat(modelled.status()).isEqualTo(JobStatus.SUCCESS);
        assertThat(modelled.shardCount()).isEqualTo(4);
        assertThat(modelled.shardDone()).isEqualTo(4);
        // 行数也要一条不少：桶号算错（H2 的 ORA_HASH 会多出一个桶）时，漏掉的行不会报差异，只会行数对不上
        assertThat(modelled.leftRows()).isEqualTo(baseline.leftRows());
        assertThat(modelled.rightRows()).isEqualTo(baseline.rightRows());
        // 关键断言：差异口径逐条一致（含 1 条重复键 —— 模型表故意不建主键，重复键要留给引擎报）
        assertThat(repository.summaryByType(modelled.id())).isEqualTo(expected);
        assertThat(modelled.diffRows()).isEqualTo(baseline.diffRows());
    }

    @Test
    void aggregatedRuleIsMaterialisedAtTheDeclaredGrain() {
        ReconJob baseline = engine.run("CUSTOMER_STATUS_RECON", "model-agg-base", "聚合基线");

        ModelBuilder.ModelBuild build = builder.build("CUSTOMER_STATUS_RECON", 2, "it-model");
        Long leftGroups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT customer_id, status FROM bill_left GROUP BY customer_id, status) g",
                Long.class);
        Long detailRows = jdbc.queryForObject("SELECT COUNT(*) FROM bill_left", Long.class);

        // 模型表就是规则声明的粒度：10 行明细压成 9 组（C001 出现两次）
        assertThat(detailRows).isEqualTo(10L);
        assertThat(build.table("left").rows()).isEqualTo(leftGroups).isLessThan(detailRows);
        assertThat(baseline.leftRows()).isEqualTo(leftGroups);

        ReconJob modelled = engine.run(build.modelRuleCode(), "model-agg", "读模型表");

        // 读模型表时扫描量就是维度行数，源表的 20 行明细不用再扫
        assertThat(modelled.leftRows()).isEqualTo(leftGroups);
        assertThat(modelled.rightRows()).isEqualTo(build.table("right").rows());
        assertThat(repository.summaryByType(modelled.id())).isEqualTo(repository.summaryByType(baseline.id()));
    }

    @Test
    void singleShardAndRebuildBothWork() {
        ModelBuilder.ModelBuild first = builder.build("CUSTOMER_STATUS_RECON", 1, "it-model");
        assertThat(first.shards()).isEqualTo(1);
        // 第二次构建走"建影子表 + 改名切换"，旧表仍在服务
        ModelBuilder.ModelBuild second = builder.build("CUSTOMER_STATUS_RECON", 1, "it-model");

        assertThat(second.tables()).allSatisfy(table ->
                assertThat(table.rows()).isEqualTo(first.table(table.side()).rows()));
        assertThat(engine.run(second.modelRuleCode(), "model-rebuild", "重建后重跑").status())
                .isEqualTo(JobStatus.SUCCESS);
        // 每次构建的元信息都留一条，便于对比成本与排查新鲜度
        Integer recorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_model WHERE rule_code = 'CUSTOMER_STATUS_RECON'", Integer.class);
        assertThat(recorded).isGreaterThanOrEqualTo(4);
    }

    @Test
    void mcpToolTellsTheAgentWhichRuleToRunNext() {
        String text = tools.buildModel("BILL_VS_SETTLE", 1, "agent");

        assertThat(text).contains("已为规则 BILL_VS_SETTLE")
                .contains("已注册规则 BILL_VS_SETTLE__MODEL")
                .contains("recon_run")
                .contains("源数据变化后要重新构建");
    }

    @Test
    void unknownRuleAndIllegalShardCountAreRejectedWithPlainWords() {
        assertThatThrownBy(() -> builder.build("NO_SUCH_RULE", null, null))
                .hasMessageContaining("NO_SUCH_RULE");
        assertThatThrownBy(() -> builder.build("BILL_VS_SETTLE", 0, null))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("shards");
    }
}
