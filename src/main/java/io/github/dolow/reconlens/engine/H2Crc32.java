package io.github.dolow.reconlens.engine;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * H2 上的 {@code CRC32()} 兼容实现（MySQL 有同名内置函数，H2 没有）。
 *
 * <p><b>为什么需要它</b>：起草提示词教模型用 {@code MOD(CRC32(<业务键>), N) = i} 做分片 —— 那是
 * MySQL 的写法，生产库上是对的；但 demo 与单测跑在 H2 上，H2 报
 * {@code Function "crc32" not found [90022-224]}，于是<b>每一条模型起草的规则试算都必然
 * INCONCLUSIVE</b>，闸门 2 永远给不出证据。
 *
 * <p><b>为什么是别名而不是改规则</b>：改提示词等于让"生成的规则"去迁就演示库，
 * 生产上反而拿不到高效的取模分片；正确做法是让演示库补上这个函数（见
 * {@code config.H2Compatibility}），规则本身保持 MySQL 语义。
 *
 * <p>返回 {@code long} 而不是 {@code int}：MySQL 的 {@code CRC32()} 返回无符号 32 位，
 * H2 的 {@code MOD} 对负数取模结果是负的，用无符号值可以避开"分片号为负"。
 */
public final class H2Crc32 {

    private H2Crc32() {
    }

    /** {@code CRC32(value)}：UTF-8 编码后取无符号 32 位值，语义与 MySQL 一致。 */
    public static long crc32(String value) {
        if (value == null) {
            return 0L;
        }
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
