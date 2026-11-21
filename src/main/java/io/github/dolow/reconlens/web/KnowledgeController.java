package io.github.dolow.reconlens.web;

import io.github.dolow.reconlens.explain.KnowledgeRoiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识层 ROI 端点：GET /api/knowledge/roi。
 * GET 免 key（与差异/知识查询同一口径）。报表是给人看的，Agent 要看同走 MCP 的 knowledge 出口。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeRoiService roiService;

    public KnowledgeController(KnowledgeRoiService roiService) {
        this.roiService = roiService;
    }

    @GetMapping("/roi")
    public Map<String, Object> roi() {
        KnowledgeRoiService.Roi roi = roiService.roi();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("knowledgeEnabled", roi.knowledgeEnabled());
        body.put("llmEnabled", roi.llmEnabled());
        body.put("lookups", roi.lookups());
        body.put("knowledgeHits", roi.knowledgeHits());
        body.put("knowledgeHitRate", roi.knowledgeHitRate());
        body.put("absent", roi.absent());
        body.put("lowConfidence", roi.lowConfidence());
        body.put("patternHits", roi.patternHits());
        body.put("llmExplained", roi.llmExplained());
        body.put("templateFallbacks", roi.templateFallbacks());
        body.put("llmCallsSaved", roi.llmCallsSaved());
        body.put("kbBySource", roi.kbBySource());
        body.put("note", roi.note());
        return body;
    }
}
