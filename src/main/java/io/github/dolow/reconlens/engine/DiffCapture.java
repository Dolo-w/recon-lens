package io.github.dolow.reconlens.engine;

import io.github.dolow.reconlens.domain.DiffRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 试算专用：把引擎真正写出去的那批差异<b>留在内存里</b>。
 *
 * <p>为什么不能事后从 {@code recon_diff} 读回来：落库时已经截断过 ——
 * {@code biz_key → 128}、{@code left_value/right_value → 512}、{@code explanation → 1000}。
 * 两条前 128 字符相同的业务键会算出同一个摘要 → T2 <b>假通过</b>，
 * 而假通过比漏判更糟：它给的是"确定"的假象。
 *
 * <p>它套在 {@link DigestingDiffSink} <b>外面</b>：先落库、再算摘要、最后才留底。
 * 顺序反过来，那些落库失败被重试掉的记录就会出现在判定输入里。
 *
 * <p>每个分片一个实例（{@code ShardResult} 持有它的快照），不做跨分片共享 ——
 * 跨分片共享意味着加锁，而试算的并行度和生产一样，不应该为了"少建几个 list"去争同一把锁。
 */
public final class DiffCapture {

    private final List<DiffRecord> records = Collections.synchronizedList(new ArrayList<>());

    void add(DiffRecord diff) {
        records.add(diff);
    }

    public List<DiffRecord> snapshot() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public int size() {
        return records.size();
    }
}
