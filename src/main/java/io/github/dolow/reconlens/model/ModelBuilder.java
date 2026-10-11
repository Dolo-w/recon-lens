package io.github.dolow.reconlens.model;

import io.github.dolow.reconlens.dsl.FieldRule;
import io.github.dolow.reconlens.dsl.RuleSpec;
import io.github.dolow.reconlens.dsl.SideSpec;
import io.github.dolow.reconlens.rule.RuleCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预处理：把"每次对账都要重做的扫描 + 汇总"提前做一次，落成一张带 biz_key 索引与分片桶索引的模型表
 * （不建主键，理由见 {@link ModelDdl}），再注册一条读模型表的新规则（{@code <code>__MODEL}）。
 *
 * <p>收益与代价见 docs/benchmark.md §3.10：1000 万行宽表按 5 万行粒度汇总，
 * 现状每轮 21.5 s，物化后首轮 19.4 s、之后每轮 7.8~15.3 ms；代价是模型表与源表可能不同步（新鲜度）。
 *
 * <p>构建过程对线上是安全的：先建影子表 {@code <表>__new}，建完整张表后再改名切换，
 * 失败的构建不会留下半张表，重跑一次即可。
 */
@Service
public class ModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(ModelBuilder.class);
    private static final int MAX_BUCKETS = 1024;
    private static final int MAX_RULE_CODE = 64;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final RuleCatalog catalog;

    public ModelBuilder(JdbcTemplate jdbc, DataSource dataSource, RuleCatalog catalog) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.catalog = catalog;
    }

    /** 一次构建的结果。 */
    public record ModelBuild(String ruleCode,
                             Integer ruleVersion,
                             String modelRuleCode,
                             Integer modelRuleVersion,
                             int shards,
                             List<Table> tables) {

        /** 一侧模型表：行数与构建耗时（耗时是建表 + 建索引，不含数据源自身的查询开销）。 */
        public record Table(String side, String table, long rows, long buildMillis) {
        }

        public Table table(String side) {
            return tables.stream().filter(t -> t.side().equals(side)).findFirst().orElse(null);
        }
    }

    /**
     * 构建或重建一条规则的模型表。{@code shards} 为空时沿用规则自己的分片数。
     * 两侧用同一个桶函数，所以同一个业务键在两侧必然落到同一片 —— 否则会凭空多出一堆单边缺失。
     */
    public ModelBuild build(String ruleCode, Integer shards, String operator) {
        RuleCatalog.Resolved resolved = catalog.resolve(ruleCode);
        RuleSpec spec = resolved.spec();
        int buckets = shards == null ? Math.max(1, spec.shard().count()) : shards;
        if (buckets < 1 || buckets > MAX_BUCKETS) {
            throw new ModelException("shards 必须在 1 ~ " + MAX_BUCKETS + " 之间，当前 " + buckets);
        }
        // 后缀用大写：规则 code 只允许 [A-Z][A-Z0-9_]*（RuleParser.CODE_PATTERN），小写会被拒
        String modelRuleCode = spec.code() + "__MODEL";
        if (modelRuleCode.length() > MAX_RULE_CODE) {
            throw new ModelException("规则 code 太长，生成的模型规则 code 超过 " + MAX_RULE_CODE + " 字符: " + modelRuleCode);
        }
        String who = operator == null || operator.isBlank() ? "api" : operator;
        ModelDdl.Dialect dialect = ModelDdl.detect(dataSource);

        List<Target> targets = List.of(
                new Target("left", "l", spec.left(), modelSelect(spec, spec.left()), spec.code()),
                new Target("right", "r", spec.right(), modelSelect(spec, spec.right()), spec.code()));

        List<ModelBuild.Table> tables = new ArrayList<>(targets.size());
        long[] millis = new long[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            long started = System.nanoTime();
            for (String statement : ModelDdl.create(dialect, target.shadow(), target.select(), buckets)) {
                jdbc.execute(statement);
            }
            millis[i] = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        }
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            for (String statement : ModelDdl.switchTo(dialect, target.table(), target.shadow(), tableExists(target.table()))) {
                jdbc.execute(statement);
            }
            tables.add(new ModelBuild.Table(target.side(), target.table(), count(target.table()), millis[i]));
        }

        RuleCatalog.Resolved modelRule = catalog.defineMap(
                ModelRuleWriter.write(spec, modelRuleCode, targets.get(0).table(), targets.get(1).table(), buckets),
                "recon_build_model(" + spec.code() + ")", who);
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            ModelBuild.Table table = tables.get(i);
            jdbc.update("INSERT INTO recon_model (rule_code, rule_version, model_rule_code, side, model_table,"
                            + " dimension, aggregated, shards, row_count, build_ms, created_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    spec.code(), resolved.version(), modelRuleCode, table.side(), table.table(),
                    target.source().dimension(), target.source().aggregated(), buckets, table.rows(),
                    table.buildMillis(), who);
        }
        log.info("规则 {} 的模型表已就绪：{}（分片 {}，耗时 {} ms），模型规则 {} v{}",
                spec.code(), tables, buckets, millis[0] + millis[1], modelRuleCode, modelRule.version());
        return new ModelBuild(spec.code(), resolved.version(), modelRuleCode, modelRule.version(), buckets, tables);
    }

    /** 模型表的数据来源：按规则声明的粒度取数，聚合维度就地 GROUP BY，列只有 biz_key + 规则用得到的字段。 */
    private static String modelSelect(RuleSpec spec, SideSpec side) {
        List<String> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        columns.add(side.keyExpression() + " AS " + ModelDdl.KEY_COLUMN);
        names.add(ModelDdl.KEY_COLUMN);
        if (side.aggregated()) {
            for (Map.Entry<String, String> measure : side.measures().entrySet()) {
                if (names.add(measure.getKey().toLowerCase(Locale.ROOT))) {
                    columns.add(measure.getValue() + " AS " + measure.getKey());
                }
            }
        } else {
            // 行级规则只窄化：把要比的列带过来，行数不变（重复键仍能检出）
            for (FieldRule field : spec.compare()) {
                if (names.add(field.field().toLowerCase(Locale.ROOT))) {
                    columns.add(field.field());
                }
            }
        }
        String source = side.where() == null || side.where().isBlank()
                ? side.sourceSelect()
                : "SELECT * FROM (" + side.sourceSelect() + ") src WHERE " + side.where();
        String sql = "SELECT " + String.join(", ", columns) + " FROM (" + source + ") src";
        return side.aggregated() ? sql + " GROUP BY " + side.keyExpression() : sql;
    }

    private boolean tableExists(String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return rows == null ? 0 : rows;
    }

    private record Target(String side, String suffix, SideSpec source, String select, String ruleCode) {

        String table() {
            return ModelDdl.modelTable(ruleCode, suffix);
        }

        String shadow() {
            return ModelDdl.shadowOf(table());
        }
    }
}
