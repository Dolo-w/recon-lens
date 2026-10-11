package io.github.dolow.reconlens.web;

/**
 * 创建对账任务的请求体。
 *
 * @param ruleCode 规则编码，如 BILL_VS_SETTLE
 * @param batchId  业务批次号，用于串联同一批数据的多次对账
 * @param remark   备注
 * @param sync     true 表示等对账跑完再返回（适合小数据量或脚本调用），默认异步
 */
public record JobRequest(String ruleCode, String batchId, String remark, Boolean sync) {
}
