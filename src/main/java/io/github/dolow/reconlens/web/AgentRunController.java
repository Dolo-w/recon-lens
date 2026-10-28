package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.rule.RunResumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 会话的人工恢复入口。
 *
 * <p>为什么要有它：进程被杀时，正在跑的会话会停在 {@code RUNNING}，轨迹和产物都在库里。
 * 这时候只有两条路 —— 扔掉重来（浪费已经落库的草稿/试算），或者从最后一个产物接着做。
 * 这个接口是后者的明确入口，<b>不做自动重试</b>：恢复本身会消耗模型预算与生产查询，
 * 该不该救是人决定的（启动时只自动接管 {@code RUNNING}，见 {@code AgentRunRecovery}）。
 *
 * <p>为什么注入的是 {@link RunResumer} 而不是编排器本体：{@code AgentBoundaryTest} 断言
 * 「agent 之外只有 mcp.AgentMcpTools 可以依赖 agent」——web 直接碰一下，
 * {@code recon.agent.enabled=false} 就不再等于"回到引入 Agent 之前"。
 */
@RestController
@RequestMapping("/api/agent/runs")
// 会话表属于 Agent 模式：开关关掉时这两张表根本没被创建，控制器也必须一起消失
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class AgentRunController {

    private final RunResumer resumer;

    public AgentRunController(RunResumer resumer) {
        this.resumer = resumer;
    }

    /**
     * 从最后一个持久化产物接着做。
     *
     * @param operator 谁点的恢复；必填，和跑对账一样要留痕
     */
    @PostMapping("/{runId}/resume")
    public RunResumer.Outcome resume(@PathVariable long runId,
                                     @RequestHeader("X-Operator") String operator) {
        return resumer.resume(runId, operator);
    }

    /** 会话号不存在 → 404；状态不允许 → 409；两种都不该冒充 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> notResumable(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "not_resumable", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("message", message);
        return ResponseEntity.status(status).body(out);
    }
}
