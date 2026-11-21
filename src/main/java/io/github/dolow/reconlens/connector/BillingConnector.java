package io.github.dolow.reconlens.connector;

import java.util.List;
import java.util.Map;

/**
 * 厂商账单连接器 SPI（v3 扩展架构 §三）：第二家厂商进来前的抽象层。
 *
 * <p>契约与 {@link LagoConnectorService} 类注释里钉死的边界完全一致，实现类必须遵守：
 * <ul>
 *   <li><b>拉取与导入分离</b>：连接器只做"取数 → 按 profile 表头映射 → 落盘 CSV"，
 *       <b>不建表不灌数</b>——导入走 {@code recon_import_file} 既有管道，口径只有一份；</li>
 *   <li><b>fail-loud</b>：凭证缺失/校验失败/空数据一律抛异常说人话，不静默吞、
 *       不落误导性产物（空 CSV 会让人误以为同步成功）；</li>
 *   <li><b>不回显凭证</b>：{@link #configured()} 只报布尔，任何出口不得返回 key 内容；</li>
 *   <li><b>落盘即凭证</b>：CSV 原子写入（临时文件 + ATOMIC_MOVE），留在导入目录可复跑可审计。</li>
 * </ul>
 *
 * <p>注册方式：实现为 Spring Bean 后自动被 {@code ConnectorController} 收集进多厂商目录，
 * 无需手工登记。
 */
public interface BillingConnector {

    /** 厂商标识（URL 路径段 + 目录键），如 {@code "lago"}；须全小写、无斜杠。 */
    String name();

    /** 凭证是否已配置。只报布尔，实现里绝不能把 key 拼进任何返回值。 */
    boolean configured();

    /** 该厂商可拉取的资源目录（拉哪类账单、按哪份 profile 落盘、一句话说明）。 */
    List<ConnectorResource> resources();

    /** 拉取一个资源并落盘。资源不存在/未配置凭证/拉到空数据 → {@link IllegalArgumentException} 说人话。 */
    SyncOutcome sync(String resourceId);

    /** 可拉取资源描述：id 是 sync 的入参，profileId 是导入时传给 {@code recon_import_file} 的映射档。 */
    record ConnectorResource(String id, String profileId, String description) {
    }

    /** 同步结果：落盘文件 + 拉取侧说明 + 下一步导入怎么做（说人话，不让人猜）。 */
    record SyncOutcome(String resource, String csvFile, long apiRows,
                       List<String> notes, Map<String, String> importHint) {
    }
}
