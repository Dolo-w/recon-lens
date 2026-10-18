package io.github.dolow.reconlens.web;

import java.util.Map;

/**
 * 创建对账任务的请求体。
 *
 * @param ruleCode 规则编码，如 BILL_VS_SETTLE（也可以用 {@code rule}/{@code ruleYaml} 内联一份规则，不用先入库）
 * @param batchId  业务批次号，用于串联同一批数据的多次对账；可省略，服务端会补 {@code api-<时间戳>} 并在响应里回显
 * @param remark   备注
 * @param sync     true 表示等对账跑完再返回（适合小数据量或脚本调用），默认异步
 * @param rule     内联规则体（JSON，结构与规则文件一致）：临时换对账维度、试算用，不落规则库
 * @param ruleYaml 内联规则体（YAML 文本），与 {@code rule} 二选一
 */
public record JobRequest(String ruleCode, String batchId, String remark, Boolean sync,
                         Map<String, Object> rule, String ruleYaml) {

    public boolean inline() {
        return (rule != null && !rule.isEmpty()) || (ruleYaml != null && !ruleYaml.isBlank());
    }
}
