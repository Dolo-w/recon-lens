package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.config.ReconProperties;
import io.github.dolow.reconlens.dsl.SideSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 取数游标工厂：把"规则 + 分片条件"变成一条按业务键升序的 SELECT，
 * 并用 JDBC fetchSize 做流式读取（MySQL 需要 useCursorFetch=true）。
 */
@Component
public class ReconCursors {

    private static final Logger log = LoggerFactory.getLogger(ReconCursors.class);

    private final DataSource dataSource;
    private final ReconProperties properties;
    private final KeyOrder keyOrder;

    public ReconCursors(DataSource dataSource, ReconProperties properties, KeyOrder keyOrder) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.keyOrder = keyOrder;
    }

    public RowCursor open(SideSpec side, String where) {
        return new JdbcRowCursor(dataSource, sqlOf(side, where), fetchSize());
    }

    public int fetchSize() {
        return Math.max(1, properties.getEngine().getFetchSize());
    }

    public String sqlOf(SideSpec side, String where) {
        if (!side.derivedKey() && keyOrder.binary()) {
            // 老写法（表 + keyField + 自定义 orderBy）的排序由规则作者决定，引擎没法替他改；
            // 但这正是最容易踩「ci 排序 ≠ Java 码点序」的入口，所以要吭一声而不是默默出错。
            log.warn("规则使用自定义 orderBy（{}），MySQL 的排序规则可能与 Java 比较器不一致；"
                    + "建议改用声明式 dimension，由引擎统一按 utf16_bin 排序", side.orderBy());
        }
        return ShardPlanner.selectSql(side, where, keyOrder.orderKey(SideSpec.KEY_ALIAS));
    }
}
