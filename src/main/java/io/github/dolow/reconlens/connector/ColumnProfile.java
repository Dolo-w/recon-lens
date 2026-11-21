package io.github.dolow.reconlens.connector;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 厂商导出文件的列映射 profile（连接器层第二步）。
 *
 * <p>核心立场：{@code profile 是配置不是代码路径} —— 接 Stripe、接银行流水、接微信支付账单，
 * 都是加一份映射配置 + 一组按真实导出表头写的测试，不是写一条新的代码分支。
 * 每个内置 profile 的表头都从厂商源码/官方文档核证（Lago 的两个 profile
 * 逐字对齐 {@code api/app/services/data_exports/csv/*.rb} 的 {@code base_headers}）。
 *
 * <p>profile 干三件事：
 * <ol>
 *   <li><b>列重命名</b>：厂商的 {@code fee_total_amount_cents} 进库后变 {@code total_amount}，
 *       后面起草规则、写 SQL 的人不用背着厂商的命名习惯走；</li>
 *   <li><b>单位换算</b>：{@code divisor} 非空时该列在灌数时除以它（分 → 元）——
 *       对账两边口径一致才比得出真差异，口径差 100 倍只会比出一片假差异；</li>
 *   <li><b>强制类型</b>：spec 列的类型由 profile 定，不走推断 —— 机器生成的导出文件
 *       没有必要猜。</li>
 * </ol>
 *
 * <p>匹配规则从严：文件表头必须<b>按顺序</b>以 {@link #requiredHeaders()} 为前缀
 * （厂商在表尾追加可选列不影响，如 Lago 开启 progressive billing 后的附加列）；
 * 不匹配直接报错并列出期望表头，不做"像不像"的模糊匹配。
 * 未命中 spec 的尾部多余列走通用推断 + 金额归一，两套机制不互斥。
 */
public interface ColumnProfile {

    /** profile 标识，MCP {@code recon_import_file} 的 {@code profile} 参数取这个值。 */
    String id();

    /** 一句话说明（来源、适用文件），进报错信息和工具描述。 */
    String description();

    /**
     * 必须按顺序出现的源表头（厂商导出的<b>原始</b>列名，不是改名后的）。
     * 文件表头必须以它为前缀。
     */
    List<String> requiredHeaders();

    /** 源表头 → 目标列定义。requiredHeaders 里每一项都必须在这里有条目。 */
    Map<String, ColumnSpec> specs();

    /**
     * 一列的映射结果。
     *
     * @param column  目标列名（进 DDL，会再过标识符规范化）
     * @param type    强制 SQL 类型（如 DECIMAL(24,6) / VARCHAR(64) / BIGINT / DATETIME）
     * @param divisor 单位除数（null = 不换算）；非空时值在灌数时除以它，如分 → 元除 100
     */
    record ColumnSpec(String column, String type, BigDecimal divisor) {

        public static ColumnSpec of(String column, String type) {
            return new ColumnSpec(column, type, null);
        }

        public static ColumnSpec cents(String column) {
            return new ColumnSpec(column, "DECIMAL(24,6)", BigDecimal.valueOf(100));
        }
    }
}
