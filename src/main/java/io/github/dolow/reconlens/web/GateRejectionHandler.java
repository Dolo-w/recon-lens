package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.dsl.RuleGateRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 闸门 1 拒绝时回一份<b>能直接照着改</b>的 422，而不是一句中文。
 *
 * <p>为什么是 422 而不是 400：请求本身是合规的，是"这份规则过不了闸门"——
 * 与"参数写错"（400）区分开，调用方才能决定"改规则"还是"改请求"。
 *
 * <p>声明在 advice 里而不是各个 controller 里：{@code RuleTrialRunner} 被 REST、MCP、
 * Agent 三个入口共用，只要有一个入口忘了加 handler，它回出去的就是纯文本。
 *
 * <p>注意 controller 内的 {@code @ExceptionHandler(RuleException.class)} 比 advice 优先，
 * 而本异常是 {@code RuleException} 的子类 —— 所以仍要处理 {@code RuleException} 的 controller
 * 得显式挂一个 {@link #response} 的转发（见 {@code RuleReviewController}）。
 */
@RestControllerAdvice
public class GateRejectionHandler {

    @ExceptionHandler(RuleGateRejectedException.class)
    public ResponseEntity<Map<String, Object>> onGateRejected(RuleGateRejectedException e) {
        return response(e);
    }

    /** 渲染 422 的实体。controller 内的局部 handler 也复用它，避免两处措辞漂移。 */
    public static ResponseEntity<Map<String, Object>> response(RuleGateRejectedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "gate_rejected");
        body.put("message", e.getMessage());
        body.put("ruleCode", e.ruleCode());
        body.put("ruleVersion", e.ruleVersion());
        body.put("violations", e.violationViews());
        body.put("action", "按 violations[].location 改规则体；改完重新落草稿、再试算。草稿本身不会被这条闸门拒绝");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }
}
