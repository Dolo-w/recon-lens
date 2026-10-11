package io.github.dolow.reconlens.engine;

/**
 * 按业务键升序游标。peek/next 分离是为了让归并匹配能"同键分组"——读到相同业务键时要把
 * 一组重复键全部消费掉，所以必须能先看不消费。
 */
public interface RowCursor extends AutoCloseable {

    /** 返回下一行；到末尾返回 null。 */
    Row next();

    /** 不消费地看下一行；到末尾返回 null。 */
    Row peek();

    /** 已扫描行数（用于任务统计）。 */
    long scanned();

    /** 结果集列名（小写）。启动分片前用它校验规则里写的字段是否真的存在。 */
    java.util.Set<String> columns();

    @Override
    void close();
}
