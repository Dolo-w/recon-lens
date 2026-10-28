package io.github.dolow.reconlens.config;

import io.github.dolow.reconlens.engine.H2Crc32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 给 H2 补上 MySQL 才有的 {@code CRC32()} 函数（只在检测到 H2 时执行，MySQL 上什么都不做）。
 *
 * <p><b>它解决的问题</b>：{@code RuleDrafter} 的系统提示词按 MySQL 方言教模型写分片表达式
 * {@code MOD(CRC32(customer_id), 4)}；demo/单测的 H2 没有 {@code CRC32}，于是试算的每个分片都
 * 报 {@code Function "crc32" not found [90022-224]} → canary 失败 → 结论永远是
 * {@code INCONCLUSIVE / CANARY_FAILED}，闸门 2 无法给"能不能批"提供任何证据。
 *
 * <p><b>为什么不改提示词</b>：改提示词会让"生成的规则"迁就演示库，生产上丢掉取模分片带来的
 * 并行度；这里补的是<b>环境</b>，规则保持 MySQL 语义不变。
 *
 * <p><b>失败不阻塞启动</b>：建别名失败只 WARN —— 没有它业务照常跑（只是试算会 INCONCLUSIVE），
 * 不能因为一个演示库的兼容函数让整个服务起不来。
 */
@Component
public class H2Compatibility implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(H2Compatibility.class);

    private static final String CRC32_ALIAS =
            "CREATE ALIAS IF NOT EXISTS CRC32 FOR \"" + H2Crc32.class.getName() + ".crc32\"";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public H2Compatibility(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            log.debug("无法识别数据库产品名，跳过 H2 兼容函数：{}", e.toString());
            return;
        }
        if (product == null || !product.toUpperCase().contains("H2")) {
            log.debug("数据库是 {}，不需要 H2 兼容函数", product);
            return;
        }
        try {
            jdbc.execute(CRC32_ALIAS);
            log.info("已为 H2 注册兼容函数 CRC32()：模型按 MySQL 方言生成的分片表达式可以正常试算");
        } catch (RuntimeException e) {
            log.warn("给 H2 注册 CRC32() 失败（试算可能因此 INCONCLUSIVE，不影响其他功能）：{}", e.toString());
        }
    }
}
