package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.chat.ChatIntent;
import io.github.dolow.reconlens.store.ChatStatsRepository;
import io.github.dolow.reconlens.tool.ToolNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 的命中率看板：<b>会话在真实使用中被认成什么动作、调了哪些工具、有多少轮空手回</b>。
 *
 * <p>为什么放在 HTTP 上而不是写进日志：这些数字要能被反复看（今天涨了还是掉了），
 * 日志只能被 grep 一次。指标本身不需要权限——它全是计数，不含任何会话内容。
 */
@RestController
@RequestMapping("/api/chat/stats")
@ConditionalOnProperty(prefix = "recon.agent", name = "enabled", havingValue = "true")
public class ChatStatsController {

    private final ChatStatsRepository stats;

    public ChatStatsController(ChatStatsRepository stats) {
        this.stats = stats;
    }

    @GetMapping
    public Map<String, Object> stats() {
        List<String> intents = Arrays.stream(ChatIntent.values()).map(Enum::name).toList();
        List<String> tools = new ArrayList<>(ToolNames.BASE);
        tools.add(ToolNames.RECALL);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totals", stats.totals());
        body.put("byIntent", stats.byIntent(intents));
        body.put("byTool", stats.byTool(tools));
        long empty = stats.emptyToolTurns();
        Long assistant = (Long) stats.totals().get("assistantTurns");
        body.put("emptyToolTurns", empty);
        body.put("emptyToolRatio", assistant == null || assistant == 0
                ? 0.0
                : Math.round((double) empty / assistant * 1000.0) / 1000.0);
        body.put("avgUserTurnsPerSession", stats.avgTurnsPerSession());
        body.put("note", "emptyToolTurns 是「空手回」的轮次数，含 HELP/审批说明这类合理空手回，只适合看趋势");
        return body;
    }
}
