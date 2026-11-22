package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.domain.IndustryKnowledge;
import io.github.dolow.reconlens.explain.industry.IndustryKbService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业界知识层出口：条目清单 + 治理信号（冷知识清单 / graveyard）。
 * 人工的角色在这里——系统说有问题才看，其余时间零介入。
 */
@RestController
@RequestMapping("/api/knowledge")
public class IndustryKbController {

    private final IndustryKbService service;

    public IndustryKbController(IndustryKbService service) {
        this.service = service;
    }

    @GetMapping("/industry")
    public Map<String, Object> overview(@RequestParam(defaultValue = "100") int limit) {
        List<IndustryKnowledge> entries = service.listAll(Math.max(1, Math.min(limit, 500)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeTotal", service.countActive());
        body.put("entries", entries);
        body.put("coldEntries", service.coldEntries());
        body.put("graveyard", service.graveyard());
        body.put("note", "confidence 由核销反馈自动涨落：<0.2 自动停注入（graveyard）；"
                + "零命中条目在 coldEntries——要么适用面窄，要么过时了，值得人工看一眼");
        return body;
    }
}
